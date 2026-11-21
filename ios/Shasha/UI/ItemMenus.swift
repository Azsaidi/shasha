import SwiftUI

/// What the main series button does: resume, move on to the next episode, or start at the beginning.
struct SeriesAction {
    let label: String
    let season: Int
    let index: Int
    let fromStart: Bool
}

func seriesAction(_ info: SeriesInfo, latest: WatchEntry?) -> SeriesAction? {
    let numbers = info.seasonNumbers.filter { !(info.seasons[$0] ?? []).isEmpty }
    guard let firstSeason = numbers.first, let firstEp = info.seasons[firstSeason]?.first else { return nil }
    func code(_ ep: Episode) -> String { L("episode_code", ep.season, ep.number) }
    if let latest {
        for (si, season) in numbers.enumerated() {
            let episodes = info.seasons[season] ?? []
            guard let index = episodes.firstIndex(where: { $0.id == latest.streamId }) else { continue }
            let ep = episodes[index]
            if !latest.finished {
                let label = latest.position > 10
                    ? L("resume_ep", code(ep), formatTime(latest.position))
                    : L("play_ep", code(ep))
                return SeriesAction(label: label, season: season, index: index, fromStart: false)
            }
            // Finished: move on to the next episode, crossing into the next season if needed.
            if index + 1 < episodes.count {
                return SeriesAction(label: L("play_next_ep", code(episodes[index + 1])), season: season, index: index + 1, fromStart: true)
            }
            if si + 1 < numbers.count, let next = info.seasons[numbers[si + 1]]?.first {
                return SeriesAction(label: L("play_next_ep", code(next)), season: numbers[si + 1], index: 0, fromStart: true)
            }
            return SeriesAction(label: L("watch_again", code(firstEp)), season: firstSeason, index: 0, fromStart: true)
        }
    }
    return SeriesAction(label: L("play_ep", code(firstEp)), season: firstSeason, index: 0, fromStart: true)
}

@MainActor
enum Actions {
    static func toggleFavourite(_ item: StreamItem) {
        let session = Session.shared
        let was = session.isFavourite(item)
        session.toggleFavourite(item)
        Toast.shared.show(was ? L("removed_from_favourites", item.name) : L("added_to_favourites", item.name))
    }

    /// Plays a series straight from its poster.
    static func playSeries(_ item: StreamItem) {
        guard let api = Session.shared.api else { return }
        Opening.shared.run {
            guard let info = try? await api.seriesInfo(item.id),
                  let action = seriesAction(info, latest: Session.shared.latestEpisode(item.id)) else {
                Toast.shared.show(L("couldnt_load_series"))
                return
            }
            Playback.episodes(seriesId: item.id, seriesName: item.name, cover: info.cover ?? item.icon,
                              episodes: info.seasons[action.season] ?? [], index: action.index, fromStart: action.fromStart)
        }
    }

    static func open(_ item: StreamItem, list: [StreamItem] = []) {
        switch item.type {
        case .live:
            let channels = list.isEmpty ? [item] : list
            Playback.channels(channels, index: channels.firstIndex { $0.id == item.id } ?? 0)
        case .movie: Playback.movie(item.id, title: item.name, image: item.icon, ext: item.ext)
        case .series: playSeries(item)
        }
    }

    static func play(_ entry: WatchEntry) {
        Opening.shared.run { await Playback.resume(entry) }
    }
}

/// Options shown when you press and hold a channel or poster.
struct StreamItemMenu: View {
    let item: StreamItem
    var list: [StreamItem] = []
    // Not observed: menus are rebuilt with their screen, and observing here would redraw every poster.
    private var session: Session { .shared }
    private var downloads: Downloads { .shared }

    var body: some View {
        Button { Actions.open(item, list: list) } label: { Label(L("play"), systemImage: "play.fill") }
        Button { Actions.toggleFavourite(item) } label: {
            session.isFavourite(item)
                ? Label(L("remove_favourite"), systemImage: "star.slash")
                : Label(L("add_favourite"), systemImage: "star")
        }
        switch item.type {
        case .live:
            Button { MultiView.shared.add(Playback.liveItem(item)) } label: {
                Label(L("add_split"), systemImage: "rectangle.split.2x2")
            }
            if session.historyEntry(.live, item.id) != nil {
                Button(role: .destructive) { session.removeFromHistory(WatchEntry(kind: .live, streamId: item.id, title: item.name)) } label: {
                    Label(L("remove_from_recent"), systemImage: "clock.arrow.circlepath")
                }
            }
        case .movie:
            Button { MultiView.shared.add(Playback.movieItem(item.id, item.name, item.icon, item.ext)) } label: {
                Label(L("add_split"), systemImage: "rectangle.split.2x2")
            }
            DownloadMenuButton(kind: .movie, streamId: item.id) {
                DownloadActions.movie(item.id, title: item.name, image: item.icon, ext: item.ext)
            }
            let entry = session.historyEntry(.movie, item.id)
            if entry?.resumable == true, let entry {
                Button(role: .destructive) { session.removeFromHistory(entry) } label: {
                    Label(L("remove_from_continue"), systemImage: "clock.arrow.circlepath")
                }
            }
            WatchedButton(entry: entry ?? WatchEntry(kind: .movie, streamId: item.id, title: item.name, image: item.icon, ext: item.ext))
        case .series:
            if session.hasSeriesHistory(item.id) {
                Button(role: .destructive) { session.removeSeriesHistory(item.id) } label: {
                    Label(L("remove_from_continue"), systemImage: "clock.arrow.circlepath")
                }
            }
        }
    }
}

/// Options for a Continue watching card or a recently watched channel.
struct HistoryMenu: View {
    let entry: WatchEntry
    /// Opens the film page, or the series page for an episode.
    var onOpen: (() -> Void)? = nil
    // Not observed: menus are rebuilt with their screen, and observing here would redraw every poster.
    private var session: Session { .shared }

    var body: some View {
        Button { Actions.play(entry) } label: { Label(L("play"), systemImage: "play.fill") }
        if let onOpen, entry.kind != .live {
            Button(action: onOpen) {
                Label(entry.kind == .episode ? L("open_series") : L("open"), systemImage: "rectangle.stack")
            }
        }
        Button { MultiView.shared.add(Playback.entryItem(entry)) } label: {
            Label(L("add_split"), systemImage: "rectangle.split.2x2")
        }
        if entry.kind != .live {
            DownloadMenuButton(kind: entry.kind, streamId: entry.streamId) {
                if entry.kind == .movie {
                    DownloadActions.movie(entry.streamId, title: entry.title, image: entry.image, ext: entry.ext)
                } else {
                    DownloadActions.entry(entry)
                }
            }
        }
        switch entry.kind {
        case .live:
            let item = StreamItem(id: entry.streamId, name: entry.title, icon: entry.image, categoryId: nil, type: .live,
                                  ext: nil, rating: nil, added: nil, year: nil)
            Button { Actions.toggleFavourite(item) } label: {
                session.isFavourite(item)
                    ? Label(L("remove_favourite"), systemImage: "star.slash")
                    : Label(L("add_favourite"), systemImage: "star")
            }
            Button(role: .destructive) { session.removeFromHistory(entry) } label: {
                Label(L("remove_from_recent"), systemImage: "clock.arrow.circlepath")
            }
        case .movie:
            let item = StreamItem(id: entry.streamId, name: entry.title, icon: entry.image, categoryId: nil, type: .movie,
                                  ext: entry.ext, rating: nil, added: nil, year: nil)
            Button { Actions.toggleFavourite(item) } label: {
                session.isFavourite(item)
                    ? Label(L("remove_favourite"), systemImage: "star.slash")
                    : Label(L("add_favourite"), systemImage: "star")
            }
            Button { session.markWatched(entry) } label: { Label(L("mark_watched"), systemImage: "checkmark.circle") }
            Button(role: .destructive) { session.removeFromHistory(entry) } label: {
                Label(L("remove_from_continue"), systemImage: "clock.arrow.circlepath")
            }
        case .episode:
            Button { session.markWatched(entry) } label: { Label(L("mark_watched"), systemImage: "checkmark.circle") }
            Button(role: .destructive) { session.removeFromHistory(entry) } label: {
                Label(L("remove_from_continue"), systemImage: "clock.arrow.circlepath")
            }
        }
    }
}

/// "Download", or cancel/delete when it's already downloading or downloaded.
struct DownloadMenuButton: View {
    let kind: WatchKind
    let streamId: String
    let start: () -> Void
    private var downloads: Downloads { .shared }

    var body: some View {
        if let existing = downloads.find(kind, streamId) {
            switch downloads.status[existing.key]?.state {
            case .done:
                Button(role: .destructive) { downloads.remove(existing) } label: {
                    Label(L("delete_download"), systemImage: "trash")
                }
            case .failed:
                Button {
                    downloads.remove(existing)
                    start()
                } label: {
                    Label(L("retry_download"), systemImage: "arrow.clockwise")
                }
            default:
                Button(role: .destructive) { downloads.remove(existing) } label: {
                    Label(L("cancel_download"), systemImage: "xmark.circle")
                }
            }
        } else {
            Button(action: start) { Label(L("download"), systemImage: "arrow.down.circle") }
        }
    }
}

struct WatchedButton: View {
    let entry: WatchEntry
    // Not observed: menus are rebuilt with their screen, and observing here would redraw every poster.
    private var session: Session { .shared }

    var body: some View {
        if session.historyEntry(entry.kind, entry.streamId)?.finished == true {
            Button { session.markUnwatched(entry) } label: { Label(L("mark_unwatched"), systemImage: "arrow.uturn.backward.circle") }
        } else {
            Button { session.markWatched(entry) } label: { Label(L("mark_watched"), systemImage: "checkmark.circle") }
        }
    }
}
