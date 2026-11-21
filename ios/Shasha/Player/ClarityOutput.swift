import AVFoundation
import os

/// The "Clearer sound" sliders. Clearer and Much clearer are the tuned presets; any change
/// makes a custom setting, saved per series, film or channel.
struct ClaritySettings: Codable, Equatable {
    /// Lift where speech is understood (2.5 kHz), dB.
    var dialogue: Float
    /// Exciter: rebuilds the top end a bad encode cut off. 0...1.5.
    var air: Float
    /// Treble shelf above 6 kHz, dB.
    var treble: Float
    /// Cut of boxy low-mid "mud" at 300 Hz, dB.
    var mud: Float
    /// Dialogue (middle of the mix) against music and effects (sides). 0...1.
    var focus: Float
    /// How much already high-pitched sound is held back from the lift. 0...1 (0 = off).
    var tame: Float

    static let clearer = ClaritySettings(dialogue: 4, air: 0.5, treble: 3, mud: 2, focus: 0.25, tame: 0)
    static let muchClearer = ClaritySettings(dialogue: 6, air: 0.9, treble: 4.5, mud: 3, focus: 0.5, tame: 0)

    // Gains along the "Dialogue vs music" slider; 0.25 and 0.5 are exactly the presets' values.
    var midGain: Float { Self.along(focus, [1, 1.12, 1.25, 1.5]) }
    var sideGain: Float { Self.along(focus, [1, 1, 0.85, 0.6]) }
    var outGain: Float { Self.along(focus, [0.9, 0.85, 0.8, 0.7]) }

    /// Straight lines between values at focus 0, 0.25, 0.5 and 1.
    private static func along(_ f: Float, _ v: [Float]) -> Float {
        let x = min(max(f, 0), 1)
        if x <= 0.25 { return v[0] + (v[1] - v[0]) * x / 0.25 }
        if x <= 0.5 { return v[1] + (v[2] - v[1]) * (x - 0.25) / 0.25 }
        return v[2] + (v[3] - v[2]) * (x - 0.5) / 0.5
    }

    /// "clarity_1" / "clarity_2" for the presets, "clarity_custom" otherwise.
    var labelKey: String {
        if self == .clearer { return "clarity_1" }
        if self == .muchClearer { return "clarity_2" }
        return "clarity_custom"
    }
}

/// "Clearer sound" for dull, muffled soundtracks.
///
/// Dialogue sits in the middle of a stereo mix; music and effects are spread to the sides. So the
/// sound is split into its middle (L+R) and sides (L−R), and only the middle is worked on: less
/// boxy low-mid "mud", a lift where speech is understood (2–3 kHz), an exciter that recreates the
/// "air" a bad encode cut off (only above the background noise, so hiss isn't turned into "rain"),
/// and a little treble. The middle is then turned up against the sides. Music on the sides is left
/// alone, so it isn't made harsh. A smooth limiter (gain riding, not clipping) keeps peaks in
/// check without the buzzing a clipper makes. No settings: the sound is left untouched.
final class ClarityDSP {
    private var settings: ClaritySettings?
    private var rate = 0.0
    private var lowCutL = Biquad.highPass(48000, 80), lowCutR = Biquad.highPass(48000, 80)
    private var mud = Biquad.highPass(48000, 80), presence = Biquad.highPass(48000, 80), treble = Biquad.highPass(48000, 80)
    private var exciteIn = Biquad.highPass(48000, 80), exciteOut = Biquad.highPass(48000, 80), exciteOut2 = Biquad.highPass(48000, 80)
    private var topCutL = Biquad.highPass(48000, 80), topCutR = Biquad.highPass(48000, 80)
    private var hiDetect = Biquad.highPass(48000, 3000)
    private var envelope: Float = 0, noiseFloor: Float = 1
    private var attack: Float = 0, release: Float = 0, floorRise: Float = 0, limiterRelease: Float = 0
    private var hiEnv: Float = 0, fullEnv: Float = 0, tameAttack: Float = 0, tameRelease: Float = 0
    private var gain: Float = 1

    func reset() { rate = 0 }

    /// Processes interleaved stereo samples in place.
    func process(_ samples: UnsafeMutablePointer<Float>, frames: Int, settings new: ClaritySettings?, rate newRate: Double) {
        guard let s = new else { return }
        if newRate != rate { build(newRate) }
        if s != settings { tune(s) }
        let mix = s.air, midGain = s.midGain, sideGain = s.sideGain, outGain = s.outGain, tameAmount = s.tame
        for f in 0..<frames {
            let l = lowCutL.run(samples[f * 2])
            let r = lowCutR.run(samples[f * 2 + 1])
            let side = (l - r) * 0.5 * sideGain
            let base = mud.run((l + r) * 0.5)
            let lifted = presence.run(base)
            // Exciter: square the upper mids (scaled to their level) for smooth harmonics an octave up.
            let upper = exciteIn.run(lifted)
            let a = abs(upper)
            let k = a > envelope ? attack : release
            let env = max(k * envelope + (1 - k) * a, 1e-6)
            envelope = env
            noiseFloor = env < noiseFloor ? env : noiseFloor + (env - noiseFloor) * floorRise
            let harmonics = exciteOut2.run(exciteOut.run(upper * upper / env))
            // Only while the sound stands clearly above the background noise (about 10 dB).
            let threshold = max(3 * noiseFloor, 3e-4)
            let bright = treble.run(lifted + harmonics * mix * (env * env / (env * env + threshold * threshold)))
            var amount: Float = 1
            if tameAmount > 0 {
                // Already high-pitched sound (whistles, "s" sounds, strings) gets less of the lift.
                let hi = abs(hiDetect.run(base))
                hiEnv = (hi > hiEnv ? tameAttack : tameRelease) * (hiEnv - hi) + hi
                let full = abs(base)
                fullEnv = (full > fullEnv ? tameAttack : tameRelease) * (fullEnv - full) + full
                let ratio = hiEnv / max(fullEnv, 1e-6)
                let tamed = max(0.15, min(1, 1.25 / (1 + (ratio / 0.4) * (ratio / 0.4))))
                amount = 1 - tameAmount * (1 - tamed)
            }
            let mid = (base + (bright - base) * amount) * midGain
            var outL = topCutL.run(mid + side) * outGain
            var outR = topCutR.run(mid - side) * outGain
            // Smooth limiter: drop the gain at once on a peak, bring it back over ~150 ms.
            let peak = max(abs(outL), abs(outR))
            let target: Float = peak > 0.89 ? 0.89 / peak : 1
            if target < gain { gain = target } else { gain += (target - gain) * limiterRelease }
            outL *= gain
            outR *= gain
            samples[f * 2] = min(max(outL, -1), 1)
            samples[f * 2 + 1] = min(max(outR, -1), 1)
        }
    }

    /// New sample rate: every filter from scratch.
    private func build(_ rate: Double) {
        self.rate = rate
        settings = nil
        lowCutL = .highPass(rate, 80)
        lowCutR = .highPass(rate, 80)
        exciteIn = .highPass(rate, 2500)
        exciteOut = .highPass(rate, 5000)
        exciteOut2 = .highPass(rate, 5000)
        topCutL = .lowPass(rate, 16000)
        topCutR = .lowPass(rate, 16000)
        hiDetect = .highPass(rate, 3000)
        mud = .peak(rate, 300, q: 1, db: 0)
        presence = .peak(rate, 2500, q: 0.9, db: 0)
        treble = .highShelf(rate, 6000, db: 0)
        envelope = 0
        noiseFloor = 1
        hiEnv = 0
        fullEnv = 0
        gain = 1
        attack = Float(exp(-1 / (0.002 * rate)))
        release = Float(exp(-1 / (0.06 * rate)))
        floorRise = Float(1 / (4 * rate))
        limiterRelease = Float(1 - exp(-1 / (0.15 * rate)))
        tameAttack = Float(exp(-1 / (0.005 * rate)))
        tameRelease = Float(exp(-1 / (0.08 * rate)))
    }

    /// New slider values: retune the filters but keep their memory, so moving a slider doesn't click.
    private func tune(_ s: ClaritySettings) {
        settings = s
        mud.retune(.peak(rate, 300, q: 1, db: -Double(s.mud)))
        presence.retune(.peak(rate, 2500, q: 0.9, db: Double(s.dialogue)))
        treble.retune(.highShelf(rate, 6000, db: Double(s.treble)))
    }
}

/// One second-order filter (RBJ audio cookbook), with its own memory.
struct Biquad {
    private(set) var b0, b1, b2, a1, a2: Float
    var z1: Float = 0, z2: Float = 0

    /// Takes another filter's settings, keeping this one's memory.
    mutating func retune(_ other: Biquad) {
        b0 = other.b0; b1 = other.b1; b2 = other.b2; a1 = other.a1; a2 = other.a2
    }

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

    static func lowPass(_ rate: Double, _ hz: Double, q: Double = 0.707) -> Biquad {
        let w = omega(rate, hz), alpha = sin(w) / (2 * q), c = cos(w)
        return Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
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

    /// nil = sound untouched.
    var settings: ClaritySettings? {
        get { state.withLockUnchecked { $0.settings } }
        set { state.withLockUnchecked { $0.settings = newValue } }
    }

    private struct State {
        var chunks: [Chunk] = []
        var settings: ClaritySettings?
        var volume: Float = 1
        var muted = false
        var pausedAt: Int64?
        /// Playing pieces back to back (false: line up with the clock first).
        var started = false
        /// How far the pieces are from when they're due, smoothed (µs).
        var drift: Double = 0
        /// What was playing when VLC dropped everything (a speed change, a jump), already faded
        /// to silence: played out first so the sound doesn't stop mid-wave with a click.
        var tail: [Float] = []
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
        // AirPods taken out, headphones plugged in, AirPlay: the sound moves to another output.
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { [weak self] _ in
            self?.restart()
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
            // A new stream starts unpaused (a pause from the last one mustn't silence it).
            self.state.withLockUnchecked { $0.pausedAt = nil }
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
        let settings = self.settings
        copy.withUnsafeMutableBufferPointer { dsp.process($0.baseAddress!, frames: frames, settings: settings, rate: rate) }
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
                s.started = false
            }
        }
        // Stop the audio engine while paused. A running engine (even playing silence) makes iOS
        // think we're still playing, so Control Center and the lock screen show "pause" instead of
        // "play" and other apps' sound can't take over cleanly.
        queue.async { [self] in
            if paused {
                if engine.isRunning { engine.pause() }
            } else {
                startEngine()
            }
        }
    }

    private var isPaused: Bool { state.withLockUnchecked { $0.pausedAt != nil } }

    /// (Re)connects the sound to the current output and starts the engine. Runs on `queue`.
    private func startEngine() {
        guard let source else { return }
        let session = AVAudioSession.sharedInstance()
        try? session.setActive(true)
        if !engine.isRunning, let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2) {
            engine.disconnectNodeOutput(source)
            engine.connect(source, to: engine.mainMixerNode, format: format)
            engine.prepare()
            try? engine.start()
        }
        latency = Int64((session.outputLatency + session.ioBufferDuration) * 1_000_000)
        state.withLockUnchecked { $0.started = false }
    }

    private func flush() {
        state.withLockUnchecked { s in
            // Keep a few milliseconds of what was about to play, faded out.
            var tail: [Float] = []
            if s.pausedAt == nil, s.started, let head = s.chunks.first {
                let n = min(256, head.frames - head.read)
                if n > 0 {
                    tail.reserveCapacity(n * 2)
                    for k in 0..<n {
                        let g = Float(n - k) / Float(n)
                        let i = (head.read + k) * 2
                        tail.append(head.samples[i] * g)
                        tail.append(head.samples[i + 1] * g)
                    }
                }
            }
            s.tail = tail
            s.chunks.removeAll()
            s.started = false
        }
        dsp.reset()
    }

    /// After the output changes (AirPods out, speaker in) the engine stops itself: connect the
    /// sound to the new output again, start, and line up with VLC's clock afresh.
    private func restart() {
        queue.async { [self] in
            // While paused, leave it stopped: resuming starts it on the new output.
            guard !isPaused else { return }
            startEngine()
        }
    }

    // MARK: Audio thread

    /// Fills the output with what's due now. Returns false when it was all silence.
    ///
    /// Plays the pieces back to back (no jumps while in sync). It lines up with VLC's clock when it
    /// starts, and only re-aligns when it has drifted well out (> 80 ms on average), fading out and
    /// back in so there's never a click. Running out of sound fades out too.
    private func render(_ left: UnsafeMutablePointer<Float>, _ right: UnsafeMutablePointer<Float>, frames: Int) -> Bool {
        let now = libvlc_clock() + latency
        let rate = self.rate
        let fadeFrames = 256
        return state.withLockUnchecked { s in
            var written = 0
            defer { for f in written..<frames { left[f] = 0; right[f] = 0 } }
            if !s.tail.isEmpty {
                // Fade out what was cut off first; the new sound fades in on a later pass.
                let n = min(frames, s.tail.count / 2)
                let g: Float = s.muted ? 0 : s.volume
                for f in 0..<n {
                    left[f] = s.tail[f * 2] * g
                    right[f] = s.tail[f * 2 + 1] * g
                }
                s.tail.removeFirst(n * 2)
                written = n
                return true
            }
            guard s.pausedAt == nil, !s.chunks.isEmpty else {
                s.started = false
                return false
            }
            let head = s.chunks[0]
            let headTime = head.pts + Int64(Double(head.read) / rate * 1_000_000)
            let offset = Double(headTime - now)
            var fadeIn = 0
            var fadeOutAfter = Int.max
            if !s.started {
                if offset > 0 {
                    // Early: wait until it's due.
                    let wait = Int(offset / 1_000_000 * rate)
                    if wait >= frames { return false }
                    for f in 0..<wait { left[f] = 0; right[f] = 0 }
                    written = wait
                } else if offset < -20_000 {
                    // Late: drop what's already past.
                    Self.skip(&s.chunks, frames: Int(-offset / 1_000_000 * rate))
                    if s.chunks.isEmpty { return false }
                }
                s.started = true
                s.drift = 0
                fadeIn = fadeFrames
            } else {
                s.drift = s.drift * 0.95 + offset * 0.05
                if abs(s.drift) > 80_000 {
                    // Drifted out of step: fade out now and line up again on the next pass.
                    fadeOutAfter = 0
                    s.started = false
                }
            }
            let gain: Float = s.muted ? 0 : s.volume
            let firstSound = written
            var played = 0
            let limit = fadeOutAfter == Int.max ? frames : min(frames, written + fadeFrames)
            while written < limit, !s.chunks.isEmpty {
                let take = min(limit - written, s.chunks[0].frames - s.chunks[0].read)
                let start = s.chunks[0].read * 2
                s.chunks[0].samples.withUnsafeBufferPointer { buf in
                    var i = start
                    for f in written..<(written + take) {
                        var g = gain
                        if played < fadeIn { g *= Float(played) / Float(fadeIn) }
                        left[f] = buf[i] * g
                        right[f] = buf[i + 1] * g
                        i += 2
                        played += 1
                    }
                }
                s.chunks[0].read += take
                written += take
                if s.chunks[0].read >= s.chunks[0].frames { s.chunks.removeFirst() }
            }
            let ranOut = written < frames && fadeOutAfter == Int.max
            if fadeOutAfter != Int.max || ranOut {
                // Fade the end of what was written so stopping doesn't click.
                if ranOut { s.started = false }
                let n = min(fadeFrames, written - firstSound)
                for k in 0..<n {
                    let f = written - n + k
                    let g = Float(n - k) / Float(n)
                    left[f] *= g
                    right[f] *= g
                }
            }
            return written > firstSound
        }
    }

    private static func skip(_ chunks: inout [Chunk], frames: Int) {
        var skip = frames
        while skip > 0, !chunks.isEmpty {
            let remaining = chunks[0].frames - chunks[0].read
            if skip >= remaining {
                chunks.removeFirst()
                skip -= remaining
            } else {
                chunks[0].read += skip
                skip = 0
            }
        }
    }
}
#endif
