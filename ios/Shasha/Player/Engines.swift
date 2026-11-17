import AVFoundation
import AVKit
import UIKit
#if canImport(MobileVLCKit)
import MobileVLCKit
#endif

enum EngineState: Equatable {
    case loading
    case playing
    case paused
    case ended
    case failed(String)
}

enum VideoGravity: Int, CaseIterable {
    case fit, fill, stretch

    var labelKey: String {
        switch self {
        case .fit: return "fit"
        case .fill: return "fill"
        case .stretch: return "stretch"
        }
    }
}

struct MediaTrack: Identifiable, Hashable {
    let id: String
    let name: String
    let selected: Bool
}

/// One video player. Apple's player (HLS/MP4, with picture-in-picture) or VLC (TS, MKV and the rest).
protocol PlaybackEngine: AnyObject {
    var view: UIView { get }
    var onState: ((EngineState) -> Void)? { get set }
    var position: Double { get }
    /// 0 when unknown or live.
    var duration: Double { get }
    var isPlaying: Bool { get }
    var volume: Float { get set }
    var muted: Bool { get set }
    var gravity: VideoGravity { get set }
    var supportsPip: Bool { get }
    var pipActive: Bool { get }
    var onPipChange: ((Bool) -> Void)? { get set }

    func load(_ url: URL, start: Double)
    func play()
    func pause()
    func seek(to seconds: Double)
    func stop()
    func startPip()
    func stopPip()

    var audioTracks: [MediaTrack] { get }
    var subtitleTracks: [MediaTrack] { get }
    func selectAudio(_ id: String)
    /// nil turns subtitles off.
    func selectSubtitle(_ id: String?)
}

enum Engines {
    static var vlcAvailable: Bool {
        #if canImport(MobileVLCKit)
        return true
        #else
        return false
        #endif
    }

    /// Apple's player handles HLS and MP4; VLC everything else (when it's built in).
    static func usesApple(_ url: URL) -> Bool {
        let ext = url.pathExtension.lowercased()
        if ["m3u8", "mp4", "m4v", "mov", "m4a", "mp3"].contains(ext) { return true }
        return !vlcAvailable
    }

    static func make(for url: URL) -> PlaybackEngine {
        #if canImport(MobileVLCKit)
        if !usesApple(url) { return VLCEngine() }
        #endif
        return AVEngine()
    }
}

// MARK: - Apple's player

final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

final class AVEngine: NSObject, PlaybackEngine, AVPictureInPictureControllerDelegate {
    private let player = AVPlayer()
    private let layerView = PlayerLayerView()
    private var pip: AVPictureInPictureController?
    private var statusObserver: NSKeyValueObservation?
    private var itemObserver: NSKeyValueObservation?
    private var endObserver: Any?
    private var failObserver: Any?
    private var reported: EngineState?

    var view: UIView { layerView }
    var onState: ((EngineState) -> Void)?
    var onPipChange: ((Bool) -> Void)?

    override init() {
        super.init()
        layerView.backgroundColor = .black
        layerView.playerLayer.player = player
        layerView.playerLayer.videoGravity = .resizeAspect
        player.allowsExternalPlayback = true
        if AVPictureInPictureController.isPictureInPictureSupported() {
            pip = AVPictureInPictureController(playerLayer: layerView.playerLayer)
            pip?.delegate = self
            pip?.canStartPictureInPictureAutomaticallyFromInline = Store.shared.autoPip
        }
        statusObserver = player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.reportStatus() }
        }
    }

    deinit {
        stop()
    }

    private func report(_ state: EngineState) {
        guard reported != state else { return }
        reported = state
        onState?(state)
    }

    private func reportStatus() {
        if case .failed = reported { return }
        if reported == .ended { return }
        switch player.timeControlStatus {
        case .playing: report(.playing)
        case .paused: report(player.currentItem?.status == .readyToPlay ? .paused : .loading)
        case .waitingToPlayAtSpecifiedRate: report(.loading)
        @unknown default: break
        }
    }

    func load(_ url: URL, start: Double) {
        removeItemObservers()
        reported = nil
        report(.loading)
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": Http.userAgent]])
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 8
        itemObserver = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                if item.status == .failed {
                    self.report(.failed(AVEngine.describe(item.error)))
                } else {
                    self.reportStatus()
                }
            }
        }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            self?.report(.ended)
        }
        failObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] note in
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            self?.report(.failed(AVEngine.describe(error)))
        }
        player.replaceCurrentItem(with: item)
        if start > 0 {
            player.seek(to: CMTime(seconds: start, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        }
        player.play()
    }

    private func removeItemObservers() {
        itemObserver = nil
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        if let failObserver { NotificationCenter.default.removeObserver(failObserver) }
        endObserver = nil
        failObserver = nil
    }

    static func describe(_ error: Error?) -> String {
        guard let ns = error as NSError? else { return L("err_stream_generic", "?") }
        if ns.domain == NSURLErrorDomain {
            return L("err_stream_network")
        }
        let underlying = ns.userInfo[NSUnderlyingErrorKey] as? NSError
        if let code = underlying?.code, [-12938, -12660, -16845].contains(code) || ns.code == -11850 {
            return L("err_stream_refused")
        }
        if ns.code == -11828 || ns.code == -11829 || ns.code == -11821 {
            return L("err_stream_format")
        }
        return L("err_stream_generic", "\(ns.code)")
    }

    var position: Double {
        let t = player.currentTime().seconds
        return t.isFinite ? max(t, 0) : 0
    }

    var duration: Double {
        guard let d = player.currentItem?.duration.seconds, d.isFinite, d > 0 else { return 0 }
        return d
    }

    var isPlaying: Bool { player.timeControlStatus == .playing || player.rate > 0 }

    var volume: Float {
        get { player.volume }
        set { player.volume = newValue }
    }

    var muted: Bool {
        get { player.isMuted }
        set { player.isMuted = newValue }
    }

    var gravity: VideoGravity = .fit {
        didSet {
            switch gravity {
            case .fit: layerView.playerLayer.videoGravity = .resizeAspect
            case .fill: layerView.playerLayer.videoGravity = .resizeAspectFill
            case .stretch: layerView.playerLayer.videoGravity = .resize
            }
        }
    }

    func play() {
        if reported == .ended { seek(to: 0) }
        if case .failed = reported { return }
        player.play()
    }

    func pause() { player.pause() }

    func seek(to seconds: Double) {
        if reported == .ended { reported = nil }
        player.seek(to: CMTime(seconds: max(seconds, 0), preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    func stop() {
        pip?.stopPictureInPicture()
        player.pause()
        removeItemObservers()
        player.replaceCurrentItem(with: nil)
    }

    // Picture-in-picture

    var supportsPip: Bool { pip != nil }
    var pipActive: Bool { pip?.isPictureInPictureActive ?? false }

    func startPip() { pip?.startPictureInPicture() }
    func stopPip() { pip?.stopPictureInPicture() }

    func setAutoPip(_ on: Bool) { pip?.canStartPictureInPictureAutomaticallyFromInline = on }

    func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        onPipChange?(true)
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        onPipChange?(false)
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        completionHandler(true)
    }

    // Tracks

    private func group(_ characteristic: AVMediaCharacteristic) -> AVMediaSelectionGroup? {
        player.currentItem?.asset.mediaSelectionGroup(forMediaCharacteristic: characteristic)
    }

    private func tracks(_ characteristic: AVMediaCharacteristic) -> [MediaTrack] {
        guard let item = player.currentItem, let group = group(characteristic) else { return [] }
        let selected = item.currentMediaSelection.selectedMediaOption(in: group)
        return group.options.enumerated().map { i, option in
            MediaTrack(id: "\(i)", name: option.displayName, selected: option == selected)
        }
    }

    var audioTracks: [MediaTrack] { tracks(.audible) }
    var subtitleTracks: [MediaTrack] { tracks(.legible) }

    func selectAudio(_ id: String) {
        guard let item = player.currentItem, let group = group(.audible), let i = Int(id), i < group.options.count else { return }
        item.select(group.options[i], in: group)
    }

    func selectSubtitle(_ id: String?) {
        guard let item = player.currentItem, let group = group(.legible) else { return }
        if let id, let i = Int(id), i < group.options.count {
            item.select(group.options[i], in: group)
        } else {
            item.select(nil, in: group)
        }
    }
}

// MARK: - VLC

#if canImport(MobileVLCKit)
/// Hosts VLC's video and scales it for "fill" and "stretch" (VLC itself always fits).
final class VLCSurface: UIView {
    let video = UIView()
    var gravity: VideoGravity = .fit { didSet { setNeedsLayout() } }
    var videoSize: CGSize = .zero { didSet { if oldValue != videoSize { setNeedsLayout() } } }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        clipsToBounds = true
        video.backgroundColor = .black
        video.isUserInteractionEnabled = false
        addSubview(video)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        video.transform = .identity
        video.frame = bounds
        guard gravity != .fit, videoSize.width > 0, videoSize.height > 0, bounds.width > 0, bounds.height > 0 else { return }
        let scale = min(bounds.width / videoSize.width, bounds.height / videoSize.height)
        let fitW = videoSize.width * scale, fitH = videoSize.height * scale
        let sx = bounds.width / fitW, sy = bounds.height / fitH
        if gravity == .fill {
            let s = max(sx, sy)
            video.transform = CGAffineTransform(scaleX: s, y: s)
        } else {
            video.transform = CGAffineTransform(scaleX: sx, y: sy)
        }
    }
}

final class VLCEngine: NSObject, PlaybackEngine {
    private let player = VLCMediaPlayer()
    private let surface = VLCSurface()
    private var timer: Timer?
    private var reported: EngineState?
    private var hadPlayback = false
    private var pendingStart: Double = 0
    private var storedVolume: Float = 1
    private var isMuted = false

    var view: UIView { surface }
    var onState: ((EngineState) -> Void)?
    var onPipChange: ((Bool) -> Void)?

    override init() {
        super.init()
        player.drawable = surface.video
    }

    deinit {
        timer?.invalidate()
        player.stop()
    }

    private func report(_ state: EngineState) {
        guard reported != state else { return }
        reported = state
        onState?(state)
    }

    func load(_ url: URL, start: Double) {
        reported = nil
        hadPlayback = false
        report(.loading)
        let media = VLCMedia(url: url)
        media.addOption(":http-user-agent=\(Http.userAgent)")
        media.addOption(":network-caching=1500")
        if start > 0 { media.addOption(":start-time=\(Int(start))") }
        pendingStart = start
        player.media = media
        player.play()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in self?.poll() }
    }

    private func poll() {
        surface.videoSize = player.videoSize
        switch player.state {
        case .playing:
            hadPlayback = true
            report(player.isPlaying ? .playing : .paused)
        case .paused:
            report(.paused)
        case .opening, .buffering:
            if player.isPlaying && hadPlayback { report(.playing) } else { report(.loading) }
        case .ended:
            report(hadPlayback ? .ended : .failed(L("err_stream_generic", "VLC")))
        case .error:
            report(.failed(hadPlayback ? L("err_stream_network") : L("err_stream_generic", "VLC")))
        case .stopped:
            if hadPlayback, reported != nil, reported != .loading { report(.ended) }
        default:
            if player.isPlaying { report(.playing) }
        }
    }

    private func ms(_ time: VLCTime?) -> Double {
        guard let time else { return 0 }
        return Double(time.intValue) / 1000
    }

    var position: Double { ms(player.time as VLCTime?) }

    var duration: Double {
        guard let media = player.media as VLCMedia? else { return 0 }
        return max(ms(media.length as VLCTime?), 0)
    }

    var isPlaying: Bool { player.isPlaying }

    private func applyVolume() {
        if let audio = player.audio as VLCAudio? {
            audio.volume = Int32(isMuted ? 0 : storedVolume * 100)
        }
    }

    var volume: Float {
        get { storedVolume }
        set {
            storedVolume = min(max(newValue, 0), 1)
            applyVolume()
        }
    }

    var muted: Bool {
        get { isMuted }
        set {
            isMuted = newValue
            applyVolume()
        }
    }

    var gravity: VideoGravity = .fit { didSet { surface.gravity = gravity } }

    func play() {
        if reported == .ended {
            reported = nil
            player.stop()
            player.play()
            return
        }
        player.play()
    }

    func pause() { player.pause() }

    func seek(to seconds: Double) {
        player.time = VLCTime(int: Int32(max(seconds, 0) * 1000))
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        player.stop()
    }

    var supportsPip: Bool { false }
    var pipActive: Bool { false }
    func startPip() {}
    func stopPip() {}

    private func names(_ list: [Any]?) -> [String] { (list ?? []).map { "\($0)" } }
    private func indexes(_ list: [Any]?) -> [Int32] { (list ?? []).compactMap { ($0 as? NSNumber)?.int32Value } }

    var audioTracks: [MediaTrack] {
        let n = names(player.audioTrackNames as [Any]?)
        let ids = indexes(player.audioTrackIndexes as [Any]?)
        let current = player.currentAudioTrackIndex
        return zip(ids, n).filter { $0.0 >= 0 }.map { MediaTrack(id: "\($0.0)", name: $0.1, selected: $0.0 == current) }
    }

    var subtitleTracks: [MediaTrack] {
        let n = names(player.videoSubTitlesNames as [Any]?)
        let ids = indexes(player.videoSubTitlesIndexes as [Any]?)
        let current = player.currentVideoSubTitleIndex
        return zip(ids, n).filter { $0.0 >= 0 }.map { MediaTrack(id: "\($0.0)", name: $0.1, selected: $0.0 == current) }
    }

    func selectAudio(_ id: String) {
        if let i = Int32(id) { player.currentAudioTrackIndex = i }
    }

    func selectSubtitle(_ id: String?) {
        player.currentVideoSubTitleIndex = id.flatMap { Int32($0) } ?? -1
    }
}
#endif
