import AVFoundation
import os

/// "Clearer sound" for dull, muffled soundtracks.
///
/// An equaliser can only turn up treble that is in the file. Badly encoded episodes have had
/// their top end cut off, so there is nothing to turn up. This rebuilds it instead: cuts the
/// boomy low end, folds the upper mids up an octave to recreate the missing "air" (an exciter),
/// lifts the speech range and the treble, and rounds off peaks so the result never clips.
/// Level 0 leaves the sound untouched.
final class ClarityDSP {
    private var level = -1
    private var rate = 0.0
    private var lowCut = [Biquad](), exciteIn = [Biquad](), exciteOut = [Biquad](), exciteOut2 = [Biquad]()
    private var presence = [Biquad](), treble = [Biquad]()
    private var mix: Float = 0, outGain: Float = 1

    func reset() { level = -1 }

    /// Processes interleaved stereo samples in place.
    func process(_ samples: UnsafeMutablePointer<Float>, frames: Int, level newLevel: Int, rate newRate: Double) {
        guard newLevel > 0 else { return }
        if newLevel != level || newRate != rate { build(newLevel, newRate) }
        for f in 0..<frames {
            for c in 0..<2 {
                let i = f * 2 + c
                var y = lowCut[c].run(samples[i])
                let upper = exciteIn[c].run(y)
                y += exciteOut2[c].run(exciteOut[c].run(abs(upper))) * mix
                y = presence[c].run(y)
                y = treble[c].run(y)
                samples[i] = ClarityDSP.limit(y * outGain)
            }
        }
    }

    private func build(_ level: Int, _ rate: Double) {
        self.level = level
        self.rate = rate
        let strong = level >= 2
        lowCut = (0..<2).map { _ in .highPass(rate, strong ? 110 : 80) }
        exciteIn = (0..<2).map { _ in .highPass(rate, strong ? 2200 : 2800) }
        exciteOut = (0..<2).map { _ in .highPass(rate, strong ? 4500 : 5500) }
        exciteOut2 = (0..<2).map { _ in .highPass(rate, strong ? 4500 : 5500) }
        presence = (0..<2).map { _ in .peak(rate, 2500, q: 1, db: strong ? 5 : 3) }
        treble = (0..<2).map { _ in .highShelf(rate, 4500, db: strong ? 9 : 6) }
        mix = strong ? 2 : 1
        outGain = strong ? 0.75 : 0.85
    }

    /// Leaves quiet sound alone and rounds off peaks instead of clipping them.
    static func limit(_ x: Float) -> Float {
        let a = abs(x)
        if a <= 0.7 { return x }
        let shaped = 0.7 + 0.3 * tanh((a - 0.7) / 0.3)
        return x < 0 ? -shaped : shaped
    }
}

/// One second-order filter (RBJ audio cookbook), with its own memory.
struct Biquad {
    let b0, b1, b2, a1, a2: Float
    var z1: Float = 0, z2: Float = 0

    init(_ b0: Double, _ b1: Double, _ b2: Double, _ a0: Double, _ a1: Double, _ a2: Double) {
        self.b0 = Float(b0 / a0); self.b1 = Float(b1 / a0); self.b2 = Float(b2 / a0)
        self.a1 = Float(a1 / a0); self.a2 = Float(a2 / a0)
    }

    mutating func run(_ x: Float) -> Float {
        let y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    private static func omega(_ rate: Double, _ hz: Double) -> Double { 2 * .pi * min(hz, rate * 0.45) / rate }

    static func highPass(_ rate: Double, _ hz: Double, q: Double = 0.707) -> Biquad {
        let w = omega(rate, hz), alpha = sin(w) / (2 * q), c = cos(w)
        return Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    static func peak(_ rate: Double, _ hz: Double, q: Double, db: Double) -> Biquad {
        let a = pow(10, db / 40), w = omega(rate, hz), alpha = sin(w) / (2 * q), c = cos(w)
        return Biquad(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    static func highShelf(_ rate: Double, _ hz: Double, db: Double) -> Biquad {
        let a = pow(10, db / 40), w = omega(rate, hz), c = cos(w)
        let s = 2 * sqrt(a) * sin(w) / 2 * sqrt(2.0)
        return Biquad(
            a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
            (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s
        )
    }
}

#if canImport(VLCKit)
import VLCKit

/// Takes VLC's decoded sound, runs it through `ClarityDSP` and plays it with AVAudioEngine,
/// each piece at the moment VLC says it's due, so it stays in sync with the picture.
final class ClarityOutput {
    private struct Chunk {
        var pts: Int64          // when the first frame should be heard (LibVLC clock, µs)
        var samples: [Float]    // interleaved stereo
        var read = 0            // frames already played
        var frames: Int { samples.count / 2 }
    }

    /// 0 = untouched, 1 = clearer, 2 = much clearer.
    var level: Int {
        get { state.withLockUnchecked { $0.level } }
        set { state.withLockUnchecked { $0.level = newValue } }
    }

    private struct State {
        var chunks: [Chunk] = []
        var level = 0
        var volume: Float = 1
        var muted = false
        var pausedAt: Int64?
    }

    private let state = OSAllocatedUnfairLock(initialState: State())
    private let dsp = ClarityDSP()
    private let engine = AVAudioEngine()
    private var source: AVAudioSourceNode?
    private var rate: Double = 48000
    private var latency: Int64 = 0
    private var observers: [Any] = []
    /// Sets up and restarts the audio engine one step at a time, off the main thread
    /// (VLC asks from its own thread, sometimes while the main thread waits for it).
    private let queue = DispatchQueue(label: "shasha.clarity")
    private static var retired: [ClarityOutput] = []

    init() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self] _ in
            self?.restart()
        })
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { [weak self] note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            if raw == AVAudioSession.InterruptionType.ended.rawValue { self?.restart() }
        })
    }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        engine.stop()
    }

    /// Keeps this alive for a few seconds after its player is gone, in case VLC still calls in.
    func retire() {
        ClarityOutput.retired.append(self)
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [self] in
            queue.sync { engine.stop() }
            ClarityOutput.retired.removeAll { $0 === self }
        }
    }

    /// Routes `player`'s sound through this. Takes effect from the next thing it opens.
    @discardableResult
    func attach(to player: VLCMediaPlayer) -> Bool {
        guard let mp = ClarityOutput.handle(of: player) else { return false }
        let me = Unmanaged.passUnretained(self).toOpaque()
        libvlc_audio_set_format_callbacks(mp, { opaque, format, rate, channels in
            guard let ctx = opaque?.pointee, let format, let rate, let channels else { return -1 }
            let out = Unmanaged<ClarityOutput>.fromOpaque(ctx).takeUnretainedValue()
            // 32-bit float, stereo, at the file's own sample rate.
            for (i, ch) in "FL32".utf8.enumerated() { format[i] = CChar(bitPattern: ch) }
            channels.pointee = 2
            if rate.pointee == 0 { rate.pointee = 48000 }
            return out.setUp(rate: Double(rate.pointee)) ? 0 : -1
        }, { _ in })
        libvlc_audio_set_callbacks(mp, { data, samples, count, pts in
            guard let data, let samples else { return }
            Unmanaged<ClarityOutput>.fromOpaque(data).takeUnretainedValue()
                .receive(samples.assumingMemoryBound(to: Float.self), frames: Int(count), pts: pts)
        }, { data, _ in
            guard let data else { return }
            Unmanaged<ClarityOutput>.fromOpaque(data).takeUnretainedValue().setPaused(true)
        }, { data, _ in
            guard let data else { return }
            Unmanaged<ClarityOutput>.fromOpaque(data).takeUnretainedValue().setPaused(false)
        }, { data in
            guard let data else { return }
            Unmanaged<ClarityOutput>.fromOpaque(data).takeUnretainedValue().flush()
        }, { _ in }, me)
        libvlc_audio_set_volume_callback(mp) { data, volume, mute in
            guard let data else { return }
            let out = Unmanaged<ClarityOutput>.fromOpaque(data).takeUnretainedValue()
            out.state.withLockUnchecked {
                $0.volume = volume
                $0.muted = mute
            }
        }
        return true
    }

    /// The LibVLC player inside VLCKit's VLCMediaPlayer (an internal property).
    private static func handle(of player: VLCMediaPlayer) -> OpaquePointer? {
        let sel = NSSelectorFromString("playerInstance")
        guard let method = class_getInstanceMethod(VLCMediaPlayer.self, sel) else { return nil }
        typealias Getter = @convention(c) (AnyObject, Selector) -> OpaquePointer?
        let getter = unsafeBitCast(method_getImplementation(method), to: Getter.self)
        return getter(player, sel)
    }

    // MARK: Called by VLC

    private func setUp(rate newRate: Double) -> Bool {
        let work = { () -> Bool in
            if self.source == nil || newRate != self.rate {
                self.engine.stop()
                if let old = self.source { self.engine.detach(old) }
                self.rate = newRate
                guard let format = AVAudioFormat(standardFormatWithSampleRate: newRate, channels: 2) else { return false }
                let node = AVAudioSourceNode(format: format) { [unowned self] silence, _, frames, list in
                    let buffers = UnsafeMutableAudioBufferListPointer(list)
                    guard buffers.count >= 2,
                          let left = buffers[0].mData?.assumingMemoryBound(to: Float.self),
                          let right = buffers[1].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
                    if !self.render(left, right, frames: Int(frames)) { silence.pointee = true }
                    return noErr
                }
                self.engine.attach(node)
                self.engine.connect(node, to: self.engine.mainMixerNode, format: format)
                self.source = node
            }
            self.flush()
            let session = AVAudioSession.sharedInstance()
            try? session.setActive(true)
            self.latency = Int64((session.outputLatency + session.ioBufferDuration) * 1_000_000)
            if !self.engine.isRunning {
                do { try self.engine.start() } catch { return false }
            }
            return true
        }
        return queue.sync(execute: work)
    }

    private func receive(_ samples: UnsafePointer<Float>, frames: Int, pts: Int64) {
        guard frames > 0 else { return }
        var copy = Array(UnsafeBufferPointer(start: samples, count: frames * 2))
        let level = self.level
        copy.withUnsafeMutableBufferPointer { dsp.process($0.baseAddress!, frames: frames, level: level, rate: rate) }
        state.withLockUnchecked { s in
            var chunk = Chunk(pts: pts, samples: copy)
            // A piece that arrives while paused was dated before the pause: keep it in line.
            if let paused = s.pausedAt { chunk.pts += libvlc_clock() - paused }
            s.chunks.append(chunk)
            // Never hold more than ~10 s (something has gone wrong if so).
            if s.chunks.count > 400 { s.chunks.removeFirst(s.chunks.count - 400) }
        }
    }

    private func setPaused(_ paused: Bool) {
        let now = libvlc_clock()
        state.withLockUnchecked { s in
            if paused {
                if s.pausedAt == nil { s.pausedAt = now }
            } else if let at = s.pausedAt {
                // Everything waiting is now due that much later.
                let shift = now - at
                for i in s.chunks.indices { s.chunks[i].pts += shift }
                s.pausedAt = nil
            }
        }
    }

    private func flush() {
        state.withLockUnchecked { $0.chunks.removeAll() }
        dsp.reset()
    }

    private func restart() {
        queue.async { [self] in
            guard source != nil, !engine.isRunning else { return }
            try? AVAudioSession.sharedInstance().setActive(true)
            try? engine.start()
        }
    }

    // MARK: Audio thread

    /// Fills the output with what's due now. Returns false when it was all silence.
    private func render(_ left: UnsafeMutablePointer<Float>, _ right: UnsafeMutablePointer<Float>, frames: Int) -> Bool {
        let now = libvlc_clock() + latency
        let rate = self.rate
        return state.withLockUnchecked { s in
            var written = 0
            var heard = false
            if s.pausedAt == nil {
                // Line up with the clock: skip what's late, wait for what's early.
                if var head = s.chunks.first {
                    let headTime = head.pts + Int64(Double(head.read) / rate * 1_000_000)
                    let drift = headTime - now
                    if drift > 40_000 {
                        written = min(frames, Int(Double(drift) / 1_000_000 * rate))
                    } else if drift < -40_000 {
                        var skip = Int(Double(-drift) / 1_000_000 * rate)
                        while skip > 0, !s.chunks.isEmpty {
                            head = s.chunks[0]
                            let remaining = head.frames - head.read
                            if skip >= remaining {
                                s.chunks.removeFirst()
                                skip -= remaining
                            } else {
                                s.chunks[0].read += skip
                                skip = 0
                            }
                        }
                    }
                }
                for f in 0..<written { left[f] = 0; right[f] = 0 }
                let gain: Float = s.muted ? 0 : s.volume
                while written < frames, !s.chunks.isEmpty {
                    let take = min(frames - written, s.chunks[0].frames - s.chunks[0].read)
                    let start = s.chunks[0].read * 2
                    s.chunks[0].samples.withUnsafeBufferPointer { buf in
                        var i = start
                        for f in written..<(written + take) {
                            left[f] = buf[i] * gain
                            right[f] = buf[i + 1] * gain
                            i += 2
                        }
                    }
                    s.chunks[0].read += take
                    written += take
                    heard = true
                    if s.chunks[0].read >= s.chunks[0].frames { s.chunks.removeFirst() }
                }
            }
            for f in written..<frames { left[f] = 0; right[f] = 0 }
            return heard
        }
    }
}
#endif
