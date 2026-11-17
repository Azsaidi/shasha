import SwiftUI

struct HomeView: View {
    @ObservedObject private var session = Session.shared
    @ObservedObject private var downloads = Downloads.shared
    @State private var newMovies: [StreamItem] = []
    @State private var newSeries: [StreamItem] = []

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 22) {
                Banners()

                NavigationLink {
                    SearchView()
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "magnifyingglass")
                        Text(L("search_everything"))
                        Spacer()
                    }
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                }
                .padding(.horizontal, 16)

                ExpiryBanner()

                let resume = session.continueWatching
                if !resume.isEmpty {
                    shelf(L("continue_watching")) {
                        ForEach(resume) { entry in
                            Button { Actions.play(entry) } label: {
                                WideCard(title: entry.title, subtitle: entry.displaySubtitle, image: entry.image,
                                         progress: entry.progress, badge: entry.upNext ? L("up_next") : nil)
                                    .frame(width: 220)
                            }
                            .buttonStyle(.plain)
                            .contextMenu { HistoryMenu(entry: entry) }
                        }
                    }
                }

                let recent = session.recentChannels
                if !recent.isEmpty {
                    shelf(L("recent_channels")) {
                        ForEach(Array(recent.enumerated()), id: \.element.id) { i, entry in
                            Button { Playback.recentChannels(recent, index: i) } label: {
                                ChannelCard(title: entry.title, image: entry.image,
                                            favourite: session.favourites.contains { $0.type == .live && $0.id == entry.streamId })
                                    .frame(width: 130)
                            }
                            .buttonStyle(.plain)
                            .contextMenu { HistoryMenu(entry: entry) }
                        }
                    }
                }

                let channels = session.favourites.filter { $0.type == .live }
                if !channels.isEmpty {
                    shelf(L("favourite_channels")) {
                        ForEach(channels) { item in
                            Button { Actions.open(item, list: channels) } label: {
                                ChannelCard(title: item.name, image: item.icon).frame(width: 130)
                            }
                            .buttonStyle(.plain)
                            .contextMenu { StreamItemMenu(item: item, list: channels) }
                        }
                    }
                }

                let vod = session.favourites.filter { $0.type != .live }
                if !vod.isEmpty {
                    shelf(L("favourite_vod")) {
                        ForEach(vod) { item in posterLink(item) }
                    }
                }

                if !newMovies.isEmpty {
                    shelf(L("new_movies"), seeAll: { AppNav.shared.tab = .movies }) {
                        ForEach(newMovies) { item in posterLink(item) }
                    }
                }

                if !newSeries.isEmpty {
                    shelf(L("new_series"), seeAll: { AppNav.shared.tab = .series }) {
                        ForEach(newSeries) { item in posterLink(item) }
                    }
                }
            }
            .padding(.vertical, 8)
        }
        .screenBackground()
        .navigationTitle(L("tab_home"))
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button {
                    session.refreshAll()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(session.refresh != nil && session.refresh?.done == false)
                .accessibilityLabel(L("refresh_content"))
            }
            ToolbarItem(placement: .navigationBarTrailing) { SettingsButton() }
        }
        .navigationDestination(for: StreamItem.self) { item in
            DetailView(item: item)
        }
        .task(id: session.cacheVersion) { await loadNew() }
        .task(id: session.hideAdult) { await loadNew() }
        .refreshable { session.refreshAll() }
    }

    private func posterLink(_ item: StreamItem) -> some View {
        NavigationLink(value: item) {
            PosterCard(title: item.name, image: item.icon, subtitle: item.year.map(String.init),
                       progress: progress(item), favourite: session.isFavourite(item),
                       downloaded: item.type == .movie && downloads.localURL(.movie, item.id) != nil)
                .frame(width: 118)
        }
        .buttonStyle(.plain)
        .contextMenu { StreamItemMenu(item: item) }
    }

    private func progress(_ item: StreamItem) -> Double? {
        guard item.type == .movie, let e = session.historyEntry(.movie, item.id), e.resumable else { return nil }
        return e.progress
    }

    private func shelf<Content: View>(_ title: String, seeAll: (() -> Void)? = nil,
                                      @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: title, action: seeAll)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    content()
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private func loadNew() async {
        async let movies = try? session.load(.movie)
        async let series = try? session.load(.series)
        let m = await movies, s = await series
        newMovies = recent(m)
        newSeries = recent(s)
    }

    private func recent(_ lib: Library?) -> [StreamItem] {
        guard let lib else { return [] }
        let visible = session.visible(lib).items
        return Array(visible.filter { $0.added != nil }.sorted { ($0.added ?? 0) > ($1.added ?? 0) }.prefix(20))
    }
}

/// Warns when the subscription is about to end or has ended.
struct ExpiryBanner: View {
    @ObservedObject private var session = Session.shared

    var body: some View {
        if let days = session.daysLeft, days <= 7, let date = session.expiryDate {
            let text = date.formatted(.dateTime.day().month(.wide).year().locale(L10n.shared.locale))
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: days < 0 ? "exclamationmark.octagon.fill" : "clock.badge.exclamationmark")
                    .font(.title3)
                    .foregroundStyle(days < 0 ? .red : .orange)
                Text(days < 0 ? L("expired", text) : (days < 1 ? L("expires_today", text) : LP("expires_in", days, text)))
                    .font(.subheadline)
                Spacer(minLength: 0)
            }
            .padding(14)
            .background((days < 0 ? Color.red : Color.orange).opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 16)
        }
    }
}
