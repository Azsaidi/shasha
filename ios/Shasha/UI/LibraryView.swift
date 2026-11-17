import SwiftUI

/// Favourites and Downloads.
struct LibraryView: View {
    @ObservedObject private var nav = AppNav.shared

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $nav.libraryShowsDownloads) {
                Text(L("tab_favourites")).tag(false)
                Text(L("tab_downloads")).tag(true)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            if nav.libraryShowsDownloads {
                DownloadsList()
            } else {
                FavouritesList()
            }
        }
        .screenBackground()
        .navigationTitle(L("library"))
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) { SettingsButton() }
        }
        .navigationDestination(for: StreamItem.self) { item in
            DetailView(item: item)
        }
    }
}

private struct FavouritesList: View {
    @ObservedObject private var session = Session.shared

    var body: some View {
        let channels = session.favourites.filter { $0.type == .live }
        let vod = session.favourites.filter { $0.type != .live }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                Banners()
                if channels.isEmpty && vod.isEmpty {
                    EmptyState(icon: "star", title: L("no_favourites"), message: L("no_favourites_hint"))
                        .padding(.top, 40)
                }
                if !channels.isEmpty {
                    Text(L("favourite_channels")).font(.title3.bold()).padding(.horizontal, 16)
                    ForEach(channels) { item in
                        ChannelRow(item: item) { Actions.open(item, list: channels) }
                            .contextMenu { StreamItemMenu(item: item, list: channels) }
                            .padding(.horizontal, 16)
                    }
                }
                if !vod.isEmpty {
                    Text(L("favourite_vod")).font(.title3.bold()).padding(.horizontal, 16).padding(.top, 8)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 12, alignment: .top)], spacing: 16) {
                        ForEach(vod) { item in
                            NavigationLink(value: item) {
                                PosterCard(title: item.name, image: item.icon, subtitle: item.year.map(String.init), favourite: true)
                            }
                            .buttonStyle(.plain)
                            .contextMenu { StreamItemMenu(item: item) }
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
            .padding(.bottom, 20)
        }
    }
}

private struct DownloadsList: View {
    @ObservedObject private var downloads = Downloads.shared
    @ObservedObject private var session = Session.shared

    private struct DownloadGroup: Identifiable {
        let id: String
        let title: String
        let items: [DownloadItem]
    }

    private var groups: [DownloadGroup] {
        let mine = downloads.items.filter { $0.accountId == session.account?.id }
        var out: [DownloadGroup] = []
        let films = mine.filter { $0.kind == .movie }.sorted { $0.addedAt > $1.addedAt }
        if !films.isEmpty { out.append(DownloadGroup(id: "films", title: L("tab_movies"), items: films)) }
        let episodes = mine.filter { $0.kind == .episode }
        var order: [String] = []
        var bySeries: [String: [DownloadItem]] = [:]
        for e in episodes.sorted(by: { $0.addedAt < $1.addedAt }) {
            let key = e.seriesId ?? e.title
            if bySeries[key] == nil { order.append(key) }
            bySeries[key, default: []].append(e)
        }
        for key in order {
            let list = (bySeries[key] ?? []).sorted { ($0.season, $0.episode) < ($1.season, $1.episode) }
            out.append(DownloadGroup(id: "s:\(key)", title: list.first?.seriesName ?? list.first?.title ?? "", items: list))
        }
        return out
    }

    var body: some View {
        List {
            Section {
                Banners().listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                Text(L("storage_line", formatBytes(downloads.usedBytes), formatBytes(downloads.freeBytes)))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .listRowBackground(Color.clear)
            }
            if groups.isEmpty {
                Section {
                    EmptyState(icon: "arrow.down.circle", title: L("downloads_empty"), message: L("downloads_empty_hint"))
                        .listRowBackground(Color.clear)
                }
            }
            ForEach(groups) { group in
                Section(group.title) {
                    ForEach(group.items) { item in
                        DownloadRow(item: item)
                            .swipeActions {
                                Button(role: .destructive) {
                                    downloads.remove(item)
                                } label: {
                                    Label(downloads.status[item.key]?.state == .done ? L("delete_download") : L("cancel_download"),
                                          systemImage: "trash")
                                }
                            }
                            .contextMenu {
                                if downloads.status[item.key]?.state == .done {
                                    Button { Playback.downloads(item) } label: { Label(L("play"), systemImage: "play.fill") }
                                }
                                if downloads.status[item.key]?.state == .failed {
                                    Button { downloads.retry(item) } label: { Label(L("retry_download"), systemImage: "arrow.clockwise") }
                                }
                                Button(role: .destructive) { downloads.remove(item) } label: {
                                    Label(downloads.status[item.key]?.state == .done ? L("delete_download") : L("cancel_download"),
                                          systemImage: "trash")
                                }
                            }
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
    }
}

private struct DownloadRow: View {
    let item: DownloadItem
    @ObservedObject private var downloads = Downloads.shared

    var body: some View {
        let status = downloads.status[item.key]
        Button {
            if status?.state == .done {
                Playback.downloads(item)
            } else if status?.state == .failed {
                downloads.retry(item)
            }
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    Theme.cardHigh
                    CachedImage(url: item.image, placeholder: item.kind == .movie ? "film" : "play.rectangle")
                    if status?.state == .done {
                        Image(systemName: "play.fill")
                            .font(.caption)
                            .padding(7)
                            .background(.black.opacity(0.5), in: Circle())
                    }
                }
                .frame(width: item.kind == .movie ? 52 : 96, height: item.kind == .movie ? 78 : 54)
                .clipShape(RoundedRectangle(cornerRadius: 8))

                VStack(alignment: .leading, spacing: 4) {
                    Text(item.kind == .episode ? (item.subtitle ?? item.title) : item.title)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(2)
                        .foregroundStyle(.primary)
                    Text(downloadStatusText(status, item: item))
                        .font(.caption)
                        .foregroundStyle(status?.state == .failed ? .orange : .secondary)
                        .lineLimit(2)
                    if let status, status.state == .running || (status.state == .paused && status.bytes > 0) {
                        if let f = status.fraction {
                            ProgressView(value: f).tint(Theme.accent)
                        } else {
                            ProgressView().progressViewStyle(.linear).tint(Theme.accent)
                        }
                    }
                }
                Spacer(minLength: 0)
                if status?.state == .failed {
                    Image(systemName: "arrow.clockwise").foregroundStyle(Theme.accent)
                }
            }
        }
        .buttonStyle(.plain)
    }
}
