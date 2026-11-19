import AVFoundation
import AVKit
import UIKit
#if canImport(VLCKit)
import VLCKit
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
    /// True once picture-in-picture can actually start (the video is showing).
    var canStartPip: Bool { get }
    var pipActive: Bool { get }
    var onPipChange: ((Bool) -> Void)? { get set }

    func load(_ url: URL, start: Double)
    func play()
    func pause()
    func seek(to seconds: Double)
    func stop()
    func startPip()
    func stopPip()

    /// Only VLC can change the sound (equalizer); Apple's player can't.
    var supportsClarity: Bool { get }
    func setClarity(_ settings: ClaritySettings?)

    var audioTracks: [MediaTrack] { get }
    var subtitleTracks: [MediaTrack] { get }
    func selectAudio(_ id: String)
    /// nil turns subtitles off.
    func selectSubtitle(_ id: String?)
}

/// Which player to use, chosen in Settings.
enum PlayerChoice: String, CaseIterable {
    case auto, apple, vlc

    var labelKey: String {
        switch self {
        case .auto: return "player_auto"
        case .apple: return "player_apple"
        case .vlc: return "player_vlc"
        }
    }

    static var current: PlayerChoice { PlayerChoice(rawValue: Store.shared.player) ?? .auto }
}

enum Engines {
    static var vlcAvailable: Bool {
        #if canImport(VLCKit)
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

    static func make(for url: URL, preferVLC: Bool = false) -> PlaybackEngine {
        switch PlayerChoice.current {
        case .apple:
            // "Clearer sound" needs VLC's equalizer, so that title still plays in VLC.
            #if canImport(VLCKit)
            if preferVLC { return VLCEngine() }
            #endif
            return AVEngine()
        case .vlc:
            #if canImport(VLCKit)
            return VLCEngine()
            #else
            return AVEngine()
            #endif
        case .auto:
            #if canImport(VLCKit)
            if preferVLC || !usesApple(url) { return VLCEngine() }
            #endif
            return AVEngine()
        }
    }
}

// MARK: - Apple's player

final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    var onWindowChange: (() -> Void)?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        onWindowChange?()
    }
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
        // Picture-in-picture is set up once the video is on screen and ready (see setUpPip).
        layerView.onWindowChange = { [weak self] in self?.setUpPip() }
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

    /// Picture-in-picture needs the layer to be showing a player; (re)create it once the video is ready.
    private func setUpPip() {
        guard AVPictureInPictureController.isPictureInPictureSupported(), layerView.window != nil,
              player.currentItem?.status == .readyToPlay else { return }
        // A controller made too early can stay "not possible" forever; replace it if so.
        if let existing = pip, existing.isPictureInPicturePossible || existing.isPictureInPictureActive { return }
        pip?.delegate = nil
        let controller = AVPictureInPictureController(contentSource: .init(playerLayer: layerView.playerLayer))
        controller.delegate = self
        controller.canStartPictureInPictureAutomaticallyFromInline = Store.shared.autoPip
        pip = controller
    }

    /// False when sound plays but the picture can't be decoded (e.g. 4K HEVC inside a TS stream).
    var showsPicture: Bool {
        let size = player.currentItem?.presentationSize ?? .zero
        return size.width > 0 && size.height > 0
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
                    if item.status == .readyToPlay { self.setUpPip() }
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

    var supportsPip: Bool { AVPictureInPictureController.isPictureInPictureSupported() }
    var canStartPip: Bool {
        setUpPip()
        return (pip?.isPictureInPicturePossible ?? false) && showsPicture
    }
    var pipActive: Bool { pip?.isPictureInPictureActive ?? false }

    func startPip() { pip?.startPictureInPicture() }
    func stopPip() { pip?.stopPictureInPicture() }

    func setAutoPip(_ on: Bool) { pip?.canStartPictureInPictureAutomaticallyFromInline = on }

    var supportsClarity: Bool { false }
    func setClarity(_ settings: ClaritySettings?) {}

    /// Why picture-in-picture can't start, for the message shown instead.
    var pipProblem: String? {
        if !AVPictureInPictureController.isPictureInPictureSupported() { return "unsupported" }
        if !showsPicture { return "no-picture" }
        if pip == nil { return "not-ready" }
        return pip?.isPictureInPicturePossible == true ? nil : "not-possible"
    }

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

#if canImport(VLCKit)
/// The view VLC draws into. Conforming to VLCPictureInPictureDrawable turns on VLC's
/// picture-in-picture: VLC hands back a controller through `pictureInPictureReady`.
final class VLCVideoView: UIView, VLCPictureInPictureDrawable {
    weak var engine: VLCEngine?

    func mediaController() -> VLCPictureInPictureMediaControlling! {
        engine
    }

    func pictureInPictureReady() -> ((VLCPictureInPictureWindowControlling?) -> Void)! {
        { [weak self] controller in
            DispatchQueue.main.async { self?.engine?.pipReady(controller) }
        }
    }
}

/// Hosts VLC's video and scales it for "fill" and "stretch" (VLC itself always fits).
final class VLCSurface: UIView {
    let video = VLCVideoView()
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

final class VLCEngine: NSObject, PlaybackEngine, VLCPictureInPictureMediaControlling {
    private let player = VLCMediaPlayer()
    private let surface = VLCSurface()
    private var timer: Timer?
    private var reported: EngineState?
    private var hadPlayback = false
    private var stoppedByUs = false
    private var storedVolume: Float = 1
    private var isMuted = false
    private var lastPollTime: Double = -1
    /// Where to resume. Applied by seeking once playback starts: VLC's ":start-time" option makes
    /// it count time from that point, which broke the time bar and saved progress.
    private var pendingStart: Double = 0
    private var pipController: VLCPictureInPictureWindowControlling?
    private var pipOn = false
    private var resignObserver: Any?
    private var currentURL: URL?
    /// Our own sound path for "Clearer sound" (VLC's equalizer can't bring back lost treble).
    private var clarityOutput: ClarityOutput?

    var view: UIView { surface }
    var onState: ((EngineState) -> Void)?
    var onPipChange: ((Bool) -> Void)?

    override init() {
        super.init()
        surface.video.engine = self
        player.drawable = surface.video
        // Leaving the app while playing: carry on in picture-in-picture (if turned on in Settings).
        resignObserver = NotificationCenter.default.addObserver(
            forName: Notification.Name("UIApplicationWillResignActiveNotification"), object: nil, queue: .main
        ) { [weak self] _ in
            guard let self, Store.shared.autoPip, self.player.isPlaying, !self.pipOn, self.surface.window != nil else { return }
            self.pipController?.startPictureInPicture()
        }
    }

    deinit {
        timer?.invalidate()
        if let resignObserver { NotificationCenter.default.removeObserver(resignObserver) }
        player.stop()
        clarityOutput?.retire()
    }

    private func report(_ state: EngineState) {
        guard reported != state else { return }
        reported = state
        onState?(state)
        pipController?.invalidatePlaybackState()
    }

    func load(_ url: URL, start: Double) {
        reported = nil
        hadPlayback = false
        stoppedByUs = false
        lastPollTime = -1
        report(.loading)
        guard let media = VLCMedia(url: url) else {
            report(.failed(L("err_stream_generic", "VLC")))
            return
        }
        media.addOption(":http-user-agent=\(Http.userAgent)")
        media.addOption(":network-caching=1500")
        pendingStart = start
        currentURL = url
        player.media = media
        player.play()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in self?.poll() }
    }

    private func poll() {
        surface.videoSize = player.videoSize
        if pendingStart > 0, player.isPlaying, player.isSeekable, (player.media?.length.intValue ?? 0) > 0 {
            player.time = VLCTime(int: Int32(pendingStart * 1000))
            pendingStart = 0
            lastPollTime = -1
        }
        // VLC can stay in "buffering" while it plays; moving time means it's playing.
        let now = position
        defer { lastPollTime = now }
        if player.isPlaying, lastPollTime >= 0, now > lastPollTime {
            hadPlayback = true
            report(.playing)
            return
        }
        switch player.state {
        case .playing:
            hadPlayback = true
            report(player.isPlaying ? .playing : .paused)
        case .paused:
            report(.paused)
        case .opening, .buffering:
            if player.isPlaying && hadPlayback { report(.playing) } else { report(.loading) }
        case .error:
            report(.failed(hadPlayback ? L("err_stream_network") : L("err_stream_generic", "VLC")))
        case .stopped, .stopping:
            // VLC 4 has no "ended" state: stopping by itself after playing means it reached the end.
            if hadPlayback && !stoppedByUs {
                report(.ended)
            } else if !hadPlayback && reported == .loading && !stoppedByUs && lastPollTime >= 0 {
                report(.failed(L("err_stream_generic", "VLC")))
            }
        @unknown default:
            if player.isPlaying { report(.playing) }
        }
    }

    var position: Double {
        if pendingStart > 0 { return pendingStart }
        return Double(player.time.intValue) / 1000
    }

    var duration: Double {
        guard let media = player.media else { return 0 }
        return max(Double(media.length.intValue) / 1000, 0)
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
            hadPlayback = false
            if let media = player.media { player.media = media }
        }
        player.play()
    }

    func pause() { player.pause() }

    func seek(to seconds: Double) {
        pendingStart = 0
        player.time = VLCTime(int: Int32(max(seconds, 0) * 1000))
    }

    func stop() {
        stoppedByUs = true
        timer?.invalidate()
        timer = nil
        pipController?.stopPictureInPicture()
        player.stop()
    }

    // Picture-in-picture

    fileprivate func pipReady(_ controller: VLCPictureInPictureWindowControlling?) {
        pipController = controller
        controller?.stateChangeEventHandler = { [weak self] started in
            DispatchQueue.main.async {
                self?.pipOn = started
                self?.onPipChange?(started)
            }
        }
    }

    var supportsPip: Bool { AVPictureInPictureController.isPictureInPictureSupported() }
    var canStartPip: Bool { pipController != nil && player.hasVideoOut }
    var pipActive: Bool { pipOn }
    func startPip() { pipController?.startPictureInPicture() }
    func stopPip() { pipController?.stopPictureInPicture() }

    /// Why picture-in-picture can't start, for the message shown instead.
    var pipProblem: String? {
        if !player.hasVideoOut { return "no-picture" }
        if pipController == nil { return "vlc-not-ready" }
        return nil
    }

    // Clearer sound: less bass, more presence and treble, for dull soundtracks.

    var supportsClarity: Bool { true }

    func setClarity(_ settings: ClaritySettings?) {
        if settings != nil, clarityOutput == nil {
            let output = ClarityOutput()
            if output.attach(to: player) {
                clarityOutput = output
                // VLC picks up the new sound path when it next opens: reopen at the same spot.
                if let url = currentURL, player.media != nil {
                    let at = duration > 0 ? position : 0
                    output.settings = settings
                    load(url, start: at)
                }
            }
        }
        if let clarityOutput {
            clarityOutput.settings = settings
            player.equalizer = nil
            return
        }
        guard let settings else {
            player.equalizer = nil
            return
        }
        let level = settings.dialogue >= 5 ? 2 : 1
        // Fallback when our own sound path isn't available: VLC's equalizer.
        let eq = VLCAudioEqualizer()
        for band in eq.bands {
            let hz = band.frequency
            let gain: Float
            switch hz {
            case ..<120: gain = level == 1 ? -3 : -6
            case ..<400: gain = level == 1 ? -1 : -3
            case ..<1500: gain = level == 1 ? 2 : 3
            case ..<5000: gain = level == 1 ? 6 : 10
            default: gain = level == 1 ? 7 : 12
            }
            band.amplification = gain
        }
        // Leave headroom so the boosted treble doesn't distort.
        eq.preAmplification = level == 1 ? -2 : -4
        player.equalizer = eq
    }

    // VLCPictureInPictureMediaControlling: what the picture-in-picture window's buttons do.

    func mediaLength() -> Int64 { Int64(player.media?.length.intValue ?? 0) }
    func mediaTime() -> Int64 { Int64(player.time.intValue) }
    func isMediaSeekable() -> Bool { player.isSeekable }
    func isMediaPlaying() -> Bool { player.isPlaying }

    func seek(by offset: Int64, completion: @escaping () -> Void) {
        let target = max(Int64(player.time.intValue) + offset, 0)
        player.time = VLCTime(int: Int32(clamping: target))
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2, execute: completion)
    }

    // Tracks

    var audioTracks: [MediaTrack] {
        player.audioTracks.map { MediaTrack(id: $0.trackId, name: $0.trackName, selected: $0.isSelected) }
    }

    var subtitleTracks: [MediaTrack] {
        player.textTracks.map { MediaTrack(id: $0.trackId, name: $0.trackName, selected: $0.isSelected) }
    }

    func selectAudio(_ id: String) {
        player.audioTracks.first { $0.trackId == id }?.isSelectedExclusively = true
    }

    func selectSubtitle(_ id: String?) {
        if let id, let track = player.textTracks.first(where: { $0.trackId == id }) {
            track.isSelectedExclusively = true
        } else {
            player.deselectAllTextTracks()
        }
    }
}
#endif
