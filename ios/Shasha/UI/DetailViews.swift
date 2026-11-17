import SwiftUI

struct DetailView: View {
    let item: StreamItem

    var body: some View {
        switch item.type {
        case .series: SeriesDetailView(item: item)
        default: MovieDetailView(item: item)
        }
    }
}

/// Big cover with the title over it, shared by films and series.
private struct DetailHeader: View {
    let title: String
    let image: String?
    let facts: [String]

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            CachedImage(url: image, placeholder: "film")
                .frame(height: 300)
                .blur(radius: 24)
                .opacity(0.5)
                .clipped()
            LinearGradient(colors: [.clear, Theme.background], startPoint: .top, endPoint: .bottom)
            HStack(alignment: .bottom, spacing: 16) {
                CachedImage(url: image, placeholder: "film")
                    .frame(width: 120, height: 180)
                    .background(Theme.card)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .shadow(radius: 12)
                VStack(alignment: .leading, spacing: 6) {
                    Text(title).font(.title2.bold()).lineLimit(3)
                    if !facts.isEmpty {
                        Text(facts.joined(separator: " · "))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(16)
        }
        .frame(height: 300)
    }
}

// MARK: - Film

struct MovieDetailView: View {
    let item: StreamItem
    @ObservedObject private var session = Session.shared
    @ObservedObject private var downloads = Downloads.shared
    @State private var info: VodInfo?
    @State private var loading = true

    private var entry: WatchEntry? { session.historyEntry(.movie, item.id) }
    private var ext: String? { info?.ext ?? item.ext }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                DetailHeader(title: info?.name ?? item.name, image: info?.cover ?? item.icon, facts: facts)

                VStack(alignment: .leading, spacing: 12) {
                    if let entry, entry.resumable {
                        Button {
                            Playback.movie(item.id, title: item.name, image: item.icon, ext: ext)
                        } label: {
                            Label(L("resume_from", formatTime(entry.position)), systemImage: "play.fill")
                                .pill(prominent: true)
                        }
                        ProgressBar(value: entry.progress)
                        Button {
                            Playback.movie(item.id, title: item.name, image: item.icon, ext: ext, fromStart: true)
                        } label: {
                            Label(L("start_over"), systemImage: "backward.end.fill").pill()
                        }
                    } else {
                        Button {
                            Playback.movie(item.id, title: item.name, image: item.icon, ext: ext, fromStart: true)
                        } label: {
                            Label(L("play"), systemImage: "play.fill").pill(prominent: true)
                        }
                    }

                    HStack(spacing: 10) {
                        DownloadButton(kind: .movie, streamId: item.id) {
                            DownloadActions.movie(item.id, title: item.name, image: info?.cover ?? item.icon, ext: ext)
                        }
                        Button {
                            Actions.toggleFavourite(item)
                        } label: {
                            Image(systemName: session.isFavourite(item) ? "star.fill" : "star")
                                .foregroundStyle(session.isFavourite(item) ? Color.yellow : Color.white)
                                .pill(square: true)
                        }
                        .accessibilityLabel(session.isFavourite(item) ? L("remove_favourite") : L("add_favourite"))
                        Menu {
                            Button { MultiView.shared.add(Playback.movieItem(item.id, item.name, item.icon, ext)) } label: {
                                Label(L("add_split"), systemImage: "rectangle.split.2x2")
                            }
                            WatchedButton(entry: entry ?? WatchEntry(kind: .movie, streamId: item.id, title: item.name, image: item.icon, ext: ext))
                            if entry?.resumable == true, let entry {
                                Button(role: .destructive) { session.removeFromHistory(entry) } label: {
                                    Label(L("remove_from_continue"), systemImage: "clock.arrow.circlepath")
                                }
                            }
                        } label: {
                            Image(systemName: "ellipsis").pill(square: true)
                        }
                        .menuStyle(.button)
                    }
                    .buttonStyle(PressableStyle())

                    if entry?.finished == true {
                        Label(L("watched"), systemImage: "checkmark.circle.fill")
                            .font(.subheadline)
                            .foregroundStyle(.green)
                    }

                    if loading {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                    if let plot = info?.plot {
                        Text(plot).font(.body).foregroundStyle(.white.opacity(0.9))
                    }
                    if let director = info?.director {
                        Text(L("director", director)).font(.subheadline).foregroundStyle(.secondary)
                    }
                    if let cast = info?.cast {
                        Text(L("cast", cast)).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 16)
                .buttonStyle(PressableStyle())
            }
            .padding(.bottom, 24)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard info == nil, let api = session.api else { return }
            info = try? await api.vodInfo(item.id)
            loading = false
        }
    }

    private var facts: [String] {
        var out: [String] = []
        if let y = item.year ?? firstYear(in: info?.releaseDate) { out.append(String(y)) }
        if let g = info?.genre { out.append(g) }
        if let d = info?.duration { out.append(d) }
        if let r = info?.rating ?? item.rating, let v = Double(r), v > 0 { out.append("★ \(String(format: "%.1f", v))") }
        return out
    }
}

/// Download / progress / delete button for a film or episode.
struct DownloadButton: View {
    let kind: WatchKind
    let streamId: String
    let start: () -> Void
    @ObservedObject private var downloads = Downloads.shared

    var body: some View {
        let existing = downloads.find(kind, streamId)
        let status = existing.flatMap { downloads.status[$0.key] }
        Menu {
            DownloadMenuButton(kind: kind, streamId: streamId, start: start)
        } label: {
            HStack(spacing: 8) {
                switch status?.state {
                case .done:
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                    Text(L("downloaded"))
                case .running:
                    ProgressView(value: status?.fraction ?? 0).frame(width: 40).tint(Theme.accent)
                    Text([status?.fraction.map { "\(Int($0 * 100))%" } ?? L("downloading"),
                          status?.eta.map { formatDuration($0) }].compactMap { $0 }.joined(separator: " · "))
                        .monospacedDigit()
                case .queued, .paused:
                    Image(systemName: "clock")
                    Text(L("download_queued")).lineLimit(1)
                case .failed:
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                    Text(L("download_failed")).lineLimit(1)
                case nil:
                    Image(systemName: "arrow.down.circle")
                    Text(L("download"))
                }
            }
            .pill()
        } primaryAction: {
            if existing == nil { start() } else if status?.state == .failed, let existing {
                downloads.retry(existing)
            } else if status?.state == .done {
                AppNav.shared.goToDownloads()
            }
        }
        .menuStyle(.button)
        .buttonStyle(PressableStyle())
    }
}

// MARK: - Series

struct SeriesDetailView: View {
    let item: StreamItem
    @ObservedObject private var session = Session.shared
    @ObservedObject private var downloads = Downloads.shared
    @State private var info: SeriesInfo?
    @State private var failed = false
    @State private var season: Int?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                DetailHeader(title: info?.name ?? item.name, image: info?.cover ?? item.icon, facts: facts)

                VStack(alignment: .leading, spacing: 12) {
                    if let info {
                        content(info)
                    } else if failed {
                        EmptyState(icon: "exclamationmark.triangle", title: L("couldnt_load_series"),
                                   action: (L("try_again"), { Task { await load() } }))
                            .frame(height: 220)
                    } else {
                        VStack(spacing: 10) {
                            ProgressView()
                            Text(L("loading_episodes")).font(.subheadline).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 30)
                    }
                }
                .padding(.horizontal, 16)
            }
            .padding(.bottom, 24)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    Actions.toggleFavourite(item)
                } label: {
                    Image(systemName: session.isFavourite(item) ? "star.fill" : "star")
                        .foregroundStyle(session.isFavourite(item) ? .yellow : .primary)
                }
                .accessibilityLabel(session.isFavourite(item) ? L("remove_favourite") : L("add_favourite"))
            }
        }
        .task {
            if info == nil { await load() }
        }
    }

    private func load() async {
        failed = false
        guard let api = session.api else { return }
        do {
            let result = try await api.seriesInfo(item.id)
            info = result
            if season == nil {
                // Open on the season of the last episode watched.
                let latest = session.latestEpisode(item.id)
                season = result.seasonNumbers.first { s in result.seasons[s]?.contains { $0.id == latest?.streamId } == true }
                    ?? result.seasonNumbers.first
            }
        } catch {
            failed = true
        }
    }

    private var facts: [String] {
        var out: [String] = []
        if let y = item.year ?? firstYear(in: info?.releaseDate) { out.append(String(y)) }
        if let n = info?.seasonNumbers.count, n > 0 { out.append(LP("seasons", n)) }
        if let g = info?.genre { out.append(g) }
        if let r = info?.rating ?? item.rating, let v = Double(r), v > 0 { out.append("★ \(String(format: "%.1f", v))") }
        return out
    }

    @ViewBuilder
    private func content(_ info: SeriesInfo) -> some View {
        let name = info.name ?? item.name
        let cover = info.cover ?? item.icon
        if let action = seriesAction(info, latest: session.latestEpisode(item.id)) {
            Button {
                Playback.episodes(seriesId: item.id, seriesName: name, cover: cover,
                                  episodes: info.seasons[action.season] ?? [], index: action.index, fromStart: action.fromStart)
            } label: {
                Label(action.label, systemImage: "play.fill").pill(prominent: true)
            }
            .buttonStyle(PressableStyle())
        }

        if let plot = info.plot {
            Text(plot).font(.body).foregroundStyle(.white.opacity(0.9)).lineLimit(6)
        }

        if info.seasonNumbers.isEmpty {
            Text(L("no_episodes")).foregroundStyle(.secondary).padding(.top, 20)
        } else {
            let current = season ?? info.seasonNumbers[0]
            let episodes = info.seasons[current] ?? []
            HStack {
                if info.seasonNumbers.count > 1 {
                    Menu {
                        Picker(L("season_n", current), selection: Binding(get: { current }, set: { season = $0 })) {
                            ForEach(info.seasonNumbers, id: \.self) { n in
                                Text(L("season_n", n)).tag(n)
                            }
                        }
                    } label: {
                        HStack(spacing: 6) {
                            Text(L("season_n", current)).font(.headline)
                            Image(systemName: "chevron.down").font(.caption.bold())
                        }
                    }
                } else {
                    Text(L("season_n", current)).font(.headline)
                }
                Spacer()
                Button {
                    DownloadActions.episodes(seriesId: item.id, seriesName: name, cover: cover, episodes)
                } label: {
                    Label(L("download_season", current), systemImage: "arrow.down.circle")
                        .fixedSize()
                        .pill(compact: true)
                        .fixedSize()
                }
                .buttonStyle(PressableStyle())
            }
            .padding(.top, 8)

            LazyVStack(spacing: 10) {
                ForEach(Array(episodes.enumerated()), id: \.element.id) { i, ep in
                    EpisodeRow(episode: ep, seriesName: name, cover: cover)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            Playback.episodes(seriesId: item.id, seriesName: name, cover: cover, episodes: episodes, index: i)
                        }
                        .contextMenu {
                            Button {
                                Playback.episodes(seriesId: item.id, seriesName: name, cover: cover, episodes: episodes, index: i)
                            } label: { Label(L("play"), systemImage: "play.fill") }
                            if session.historyEntry(.episode, ep.id)?.resumable == true {
                                Button {
                                    Playback.episodes(seriesId: item.id, seriesName: name, cover: cover, episodes: episodes, index: i, fromStart: true)
                                } label: { Label(L("start_over"), systemImage: "backward.end.fill") }
                            }
                            Button {
                                MultiView.shared.add(Playback.episodeItem(seriesId: item.id, seriesName: name, cover: cover, ep))
                            } label: { Label(L("add_split"), systemImage: "rectangle.split.2x2") }
                            DownloadMenuButton(kind: .episode, streamId: ep.id) {
                                DownloadActions.episodes(seriesId: item.id, seriesName: name, cover: cover, [ep])
                            }
                            WatchedButton(entry: session.historyEntry(.episode, ep.id) ?? WatchEntry(
                                kind: .episode, streamId: ep.id, title: name,
                                subtitle: [L("episode_code", ep.season, ep.number), tidyEpisodeTitle(ep.title, seriesName: name)]
                                    .compactMap { $0 }.joined(separator: " · "),
                                image: ep.image ?? cover, ext: ep.ext, seriesId: item.id, seriesName: name
                            ))
                        }
                }
            }
        }
    }
}

private struct EpisodeRow: View {
    let episode: Episode
    let seriesName: String
    let cover: String?
    @ObservedObject private var session = Session.shared
    @ObservedObject private var downloads = Downloads.shared

    var body: some View {
        let entry = session.historyEntry(.episode, episode.id)
        let name = tidyEpisodeTitle(episode.title, seriesName: seriesName)
        let dl = downloads.find(.episode, episode.id).flatMap { downloads.status[$0.key] }
        HStack(alignment: .top, spacing: 12) {
            ZStack(alignment: .bottom) {
                Theme.card
                CachedImage(url: episode.image ?? cover, placeholder: "play.rectangle")
                if let entry, entry.resumable { ProgressBar(value: entry.progress).padding(6) }
            }
            .frame(width: 130, height: 74)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay {
                Image(systemName: "play.fill")
                    .font(.caption)
                    .padding(8)
                    .background(.black.opacity(0.5), in: Circle())
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(L("episode_n", episode.number) + (name.map { " · \($0)" } ?? ""))
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(2)
                HStack(spacing: 6) {
                    if let d = episode.duration { Text(d) }
                    if entry?.finished == true {
                        Label(L("watched"), systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                    } else if let entry, entry.resumable, entry.position > 10 {
                        Text(L("stopped_at", formatTime(entry.position)))
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                if let dl {
                    HStack(spacing: 4) {
                        Image(systemName: dl.state == .done ? "arrow.down.circle.fill" : (dl.state == .failed ? "exclamationmark.triangle" : "arrow.down.circle"))
                        Text(dl.state == .running ? (dl.fraction.map { L("downloading_pct", Int($0 * 100)) } ?? L("downloading"))
                             : (dl.state == .done ? L("downloaded") : (dl.state == .failed ? L("download_failed") : L("download_queued"))))
                    }
                    .font(.caption2)
                    .foregroundStyle(dl.state == .done ? .green : (dl.state == .failed ? .orange : Theme.accent))
                }
                if let plot = episode.plot {
                    Text(plot).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(8)
        .background(Theme.card.opacity(0.6), in: RoundedRectangle(cornerRadius: 12))
    }
}
