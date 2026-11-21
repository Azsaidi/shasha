import MediaPlayer
import UIKit

/// Shows what's playing on the lock screen and in Control Center, and makes their buttons work
/// (play/pause, next/previous channel or episode, ±10 s and the time bar for films and episodes).
@MainActor
final class NowPlaying {
    static let shared = NowPlaying()

    private weak var model: PlayerModel?
    private var targets: [(MPRemoteCommand, Any)] = []
    private var artworkURL: String?
    private var artwork: MPMediaItemArtwork?
    private var lastUpdate = Date.distantPast
    private var lastKey: String?

    func attach(_ model: PlayerModel) {
        detach()
        self.model = model
        let center = MPRemoteCommandCenter.shared()

        func on(_ command: MPRemoteCommand, _ action: @escaping @MainActor (PlayerModel, MPRemoteCommandEvent) -> Void) {
            command.isEnabled = true
            let target = command.addTarget { event in
                Task { @MainActor in
                    if let model = NowPlaying.shared.model { action(model, event) }
                }
                return .success
            }
            targets.append((command, target))
        }

        on(center.playCommand) { model, _ in model.setPlaying(true) }
        on(center.pauseCommand) { model, _ in model.setPlaying(false) }
        on(center.togglePlayPauseCommand) { model, _ in model.togglePlay() }
        on(center.nextTrackCommand) { model, _ in model.change(1) }
        on(center.previousTrackCommand) { model, _ in model.change(-1) }
        center.skipForwardCommand.preferredIntervals = [10]
        center.skipBackwardCommand.preferredIntervals = [10]
        on(center.skipForwardCommand) { model, _ in model.skip(10) }
        on(center.skipBackwardCommand) { model, _ in model.skip(-10) }
        on(center.changePlaybackPositionCommand) { model, event in
            if let e = event as? MPChangePlaybackPositionCommandEvent { model.seek(to: e.positionTime) }
        }
        update(force: true)
    }

    func detach(_ model: PlayerModel? = nil) {
        if let model, model !== self.model { return }
        for (command, target) in targets {
            command.removeTarget(target)
        }
        targets = []
        self.model = nil
        lastKey = nil
        artworkURL = nil
        artwork = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    /// Called as playback changes; cheap updates are throttled.
    func update(force: Bool = false) {
        guard let model, let item = model.current else { return }
        if !force && Date().timeIntervalSince(lastUpdate) < 2 && lastKey == item.key { return }
        lastUpdate = Date()
        if lastKey != item.key {
            lastKey = item.key
            loadArtwork(item.image)
        }

        let center = MPRemoteCommandCenter.shared()
        center.nextTrackCommand.isEnabled = model.canZap
        center.previousTrackCommand.isEnabled = model.canZap
        center.skipForwardCommand.isEnabled = model.canSkip
        center.skipBackwardCommand.isEnabled = model.canSkip
        center.changePlaybackPositionCommand.isEnabled = model.canSkip

        let parts = item.title.components(separatedBy: " · ")
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: item.kind == .episode && parts.count > 1
                ? parts.dropFirst().joined(separator: " · ") + (item.subtitle.map { " · \($0)" } ?? "")
                : item.title,
            // 0 while paused so the lock screen shows "play" and its clock stops.
            MPNowPlayingInfoPropertyPlaybackRate: model.wantsPlay && model.isPlaying ? Double(model.playbackRate) : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: Double(model.speed),
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue,
        ]
        if item.isLive {
            info[MPNowPlayingInfoPropertyIsLiveStream] = true
            info[MPMediaItemPropertyArtist] = model.epgLines.first ?? L("app_name")
        } else {
            info[MPMediaItemPropertyArtist] = item.kind == .episode ? (item.seriesName ?? parts.first ?? "") : (item.subtitle ?? L("app_name"))
            if model.duration > 0 {
                info[MPMediaItemPropertyPlaybackDuration] = model.duration
                info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = model.position
            }
        }
        if let artwork { info[MPMediaItemPropertyArtwork] = artwork }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func loadArtwork(_ url: String?) {
        artworkURL = url
        artwork = nil
        guard let url, !url.isEmpty else { return }
        Task { @MainActor in
            guard let image = await ImageCache.shared.load(url), self.artworkURL == url else { return }
            self.artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            self.update(force: true)
        }
    }
}
