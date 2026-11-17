import Foundation
import SwiftUI

struct PlayItem: Identifiable, Hashable {
    var title: String
    var url: URL
    var kind: WatchKind
    var streamId: String
    var accountId: String
    var subtitle: String? = nil
    var image: String? = nil
    var ext: String? = nil
    var seriesId: String? = nil
    var seriesName: String? = nil

    var isLive: Bool { kind == .live }
    var key: String { WatchEntry.key(kind, streamId) }
    var id: String { key }
    var isRemote: Bool { !url.isFileURL }
}

/// Short messages at the bottom of the screen.
@MainActor
final class Toast: ObservableObject {
    static let shared = Toast()
    @Published private(set) var message: String?
    private var hideTask: Task<Void, Never>?

    func show(_ text: String) {
        hideTask?.cancel()
        withAnimation(.easeOut(duration: 0.2)) { message = text }
        hideTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_600_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeIn(duration: 0.25)) { self?.message = nil }
        }
    }
}

/// Opens the full-screen player and split screen from anywhere in the app.
@MainActor
final class PlayerPresenter: ObservableObject {
    static let shared = PlayerPresenter()
    @Published var player: PlayerModel?
    @Published var multiView = false
    /// The last list played, offered as "Same list" when adding to split screen.
    private(set) var lastQueue: [PlayItem] = []

    func present(_ items: [PlayItem], index: Int, fromStart: Bool = false) {
        guard !items.isEmpty else { return }
        lastQueue = items
        let model = PlayerModel(items: items, index: min(max(index, 0), items.count - 1), fromStart: fromStart)
        if multiView {
            multiView = false
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 450_000_000)
                self.player = model
            }
        } else {
            player?.close()
            player = model
        }
    }

    func dismissPlayer() {
        player?.close()
        player = nil
    }
}

/// Streams picked for split screen (up to four).
@MainActor
final class MultiView: ObservableObject {
    static let shared = MultiView()
    static let max = 4
    @Published var items: [PlayItem] = []

    @discardableResult
    func add(_ item: PlayItem?) -> Bool {
        guard let item else { return false }
        if items.contains(where: { $0.key == item.key }) {
            Toast.shared.show(L("split_added", items.count))
            return true
        }
        guard items.count < MultiView.max else {
            Toast.shared.show(L("split_full"))
            return false
        }
        items.append(item)
        Toast.shared.show(L("split_added", items.count))
        return true
    }

    func remove(_ key: String) { items.removeAll { $0.key == key } }

    func clear() { items = [] }

    func open() {
        guard !items.isEmpty else { return }
        let presenter = PlayerPresenter.shared
        if presenter.player != nil {
            presenter.dismissPlayer()
            // Let the player finish closing before split screen opens.
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 450_000_000)
                presenter.multiView = true
            }
        } else {
            presenter.multiView = true
        }
    }
}

/// Builds play queues for each kind of content and starts the player.
@MainActor
enum Playback {
    private static var session: Session { Session.shared }

    private static func start(_ items: [PlayItem], _ index: Int, fromStart: Bool = false) {
        PlayerPresenter.shared.present(items, index: index, fromStart: fromStart)
    }

    // MARK: Item builders

    static func liveItem(_ item: StreamItem) -> PlayItem? { liveItem(item.id, item.name, item.icon) }

    static func liveItem(_ id: String, _ name: String, _ icon: String?) -> PlayItem? {
        guard let api = session.api, let acc = session.account,
              let url = URL(string: api.liveURL(id, ext: session.liveExtension())) else { return nil }
        return PlayItem(title: name, url: url, kind: .live, streamId: id, accountId: acc.id, image: icon)
    }

    static func movieItem(_ id: String, _ title: String, _ image: String?, _ ext: String?) -> PlayItem? {
        guard let acc = session.account else { return nil }
        let e = ext ?? "mp4"
        // A downloaded copy plays without internet.
        let url = Downloads.shared.localURL(.movie, id) ?? session.api.flatMap { URL(string: $0.movieURL(id, ext: e)) }
        guard let url else { return nil }
        return PlayItem(title: title, url: url, kind: .movie, streamId: id, accountId: acc.id, image: image, ext: e)
    }

    static func episodeItem(seriesId: String, seriesName: String, cover: String?, _ ep: Episode) -> PlayItem? {
        guard let acc = session.account else { return nil }
        let url = Downloads.shared.localURL(.episode, ep.id) ?? session.api.flatMap { URL(string: $0.episodeURL(ep.id, ext: ep.ext)) }
        guard let url else { return nil }
        return PlayItem(
            title: "\(seriesName) · \(L("episode_code", ep.season, ep.number))",
            url: url, kind: .episode, streamId: ep.id, accountId: acc.id,
            subtitle: tidyEpisodeTitle(ep.title, seriesName: seriesName),
            image: ep.image ?? cover, ext: ep.ext, seriesId: seriesId, seriesName: seriesName
        )
    }

    /// A play item from a history entry (for Continue watching and split screen).
    static func entryItem(_ entry: WatchEntry) -> PlayItem? {
        switch entry.kind {
        case .live: return liveItem(entry.streamId, entry.title, entry.image)
        case .movie: return movieItem(entry.streamId, entry.title, entry.image, entry.ext)
        case .episode:
            guard let acc = session.account else { return nil }
            let e = entry.ext ?? "mp4"
            let url = Downloads.shared.localURL(.episode, entry.streamId)
                ?? session.api.flatMap { URL(string: $0.episodeURL(entry.streamId, ext: e)) }
            guard let url else { return nil }
            let code = entry.subtitle?.components(separatedBy: " · ").first
            let name = entry.subtitle?.components(separatedBy: " · ").dropFirst().joined(separator: " · ")
            return PlayItem(
                title: [entry.seriesName ?? entry.title, code].compactMap { $0 }.joined(separator: " · "),
                url: url, kind: .episode, streamId: entry.streamId, accountId: acc.id,
                subtitle: (name?.isEmpty ?? true) ? nil : name, image: entry.image, ext: e,
                seriesId: entry.seriesId, seriesName: entry.seriesName
            )
        }
    }

    /// A finished download, played from the file.
    static func downloadItem(_ item: DownloadItem) -> PlayItem? {
        guard let url = Downloads.shared.localURL(item.kind, item.streamId) else { return nil }
        let parts = item.subtitle?.components(separatedBy: " · ") ?? []
        let title: String
        if item.kind == .episode {
            title = [item.seriesName ?? item.title, parts.first].compactMap { $0 }.joined(separator: " · ")
        } else {
            title = item.title
        }
        let sub = parts.dropFirst().joined(separator: " · ")
        return PlayItem(
            title: title, url: url, kind: item.kind, streamId: item.streamId, accountId: item.accountId,
            subtitle: sub.isEmpty ? nil : sub, image: item.image, ext: item.ext,
            seriesId: item.seriesId, seriesName: item.seriesName
        )
    }

    // MARK: Starting playback

    static func channels(_ channels: [StreamItem], index: Int) {
        // Keep the queue small enough to build instantly, centred on the chosen channel.
        let lower = max(0, index - 300), upper = min(channels.count, index + 300)
        let slice = Array(channels[lower..<upper])
        start(slice.compactMap { liveItem($0) }, index - lower)
    }

    static func recentChannels(_ entries: [WatchEntry], index: Int) {
        start(entries.compactMap { liveItem($0.streamId, $0.title, $0.image) }, index)
    }

    static func movie(_ id: String, title: String, image: String?, ext: String?, fromStart: Bool = false) {
        guard let item = movieItem(id, title, image, ext) else { return }
        start([item], 0, fromStart: fromStart)
    }

    static func episodes(seriesId: String, seriesName: String, cover: String?, episodes: [Episode], index: Int, fromStart: Bool = false) {
        start(episodes.compactMap { episodeItem(seriesId: seriesId, seriesName: seriesName, cover: cover, $0) }, index, fromStart: fromStart)
    }

    /// Plays downloaded files; for episodes, the other downloaded episodes of that series follow.
    static func downloads(_ item: DownloadItem) {
        let dl = Downloads.shared
        let queue: [DownloadItem]
        if item.kind == .episode, let sid = item.seriesId {
            queue = dl.items
                .filter { $0.kind == .episode && $0.seriesId == sid && dl.status[$0.key]?.state == .done }
                .sorted { ($0.season, $0.episode) < ($1.season, $1.episode) }
        } else {
            queue = [item]
        }
        let items = queue.compactMap { downloadItem($0) }
        start(items, items.firstIndex { $0.streamId == item.streamId } ?? 0)
    }

    /// Continue a history entry with one tap. For episodes the rest of the season is queued too.
    static func resume(_ entry: WatchEntry) async {
        switch entry.kind {
        case .live: recentChannels([entry], index: 0)
        case .movie: movie(entry.streamId, title: entry.title, image: entry.image, ext: entry.ext)
        case .episode:
            var info: SeriesInfo?
            if let sid = entry.seriesId, let api = session.api {
                info = await withTimeout(8) { try? await api.seriesInfo(sid) }
            }
            if let sid = entry.seriesId, let info,
               let season = info.seasons.values.first(where: { $0.contains { $0.id == entry.streamId } }),
               let index = season.firstIndex(where: { $0.id == entry.streamId }) {
                episodes(seriesId: sid, seriesName: entry.seriesName ?? info.name ?? entry.title,
                         cover: info.cover ?? entry.image, episodes: season, index: index)
            } else if let item = entryItem(entry) {
                // Offline or the provider didn't answer: play just this one (from the download if there is one).
                start([item], 0)
            }
        }
    }
}

/// Runs an async job but gives up after a number of seconds.
func withTimeout<T>(_ seconds: Double, _ job: @escaping () async -> T?) async -> T? {
    await withTaskGroup(of: T?.self) { group in
        group.addTask { await job() }
        group.addTask {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            return nil
        }
        let first = await group.next() ?? nil
        group.cancelAll()
        return first
    }
}

/// Download helpers that need the open account.
@MainActor
enum DownloadActions {
    @discardableResult
    static func movie(_ id: String, title: String, image: String?, ext: String?) -> Bool {
        guard let api = Session.shared.api, let acc = Session.shared.account else { return false }
        let e = ext ?? "mp4"
        let ok = Downloads.shared.enqueue(url: api.movieURL(id, ext: e), kind: .movie, streamId: id, accountId: acc.id,
                                          title: title, subtitle: nil, image: image, ext: e)
        Toast.shared.show(ok ? L("download_started", title) : L("download_error"))
        return ok
    }

    /// Downloads every episode in the list that isn't already downloaded or downloading.
    @discardableResult
    static func episodes(seriesId: String, seriesName: String, cover: String?, _ episodes: [Episode]) -> Int {
        guard let api = Session.shared.api, let acc = Session.shared.account else { return 0 }
        let dl = Downloads.shared
        var started = 0
        var failed = false
        for ep in episodes {
            if let existing = dl.find(.episode, ep.id), dl.status[existing.key]?.state != .failed { continue }
            let code = L("episode_code", ep.season, ep.number)
            let name = tidyEpisodeTitle(ep.title, seriesName: seriesName)
            let ok = dl.enqueue(
                url: api.episodeURL(ep.id, ext: ep.ext), kind: .episode, streamId: ep.id, accountId: acc.id,
                title: seriesName, subtitle: [code, name].compactMap { $0 }.joined(separator: " · "),
                image: ep.image ?? cover, ext: ep.ext, seriesId: seriesId, seriesName: seriesName,
                season: ep.season, episode: ep.number
            )
            if ok { started += 1 } else { failed = true }
        }
        if started > 0 {
            Toast.shared.show(LP("episodes_downloading", started))
        } else {
            Toast.shared.show(failed ? L("download_error") : L("season_all_downloaded"))
        }
        return started
    }
}
