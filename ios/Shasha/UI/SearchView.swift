import SwiftUI

/// Searches channels, films and series at once.
struct SearchView: View {
    @ObservedObject private var session = Session.shared
    @State private var query = ""
    @State private var results: [ContentType: [StreamItem]] = [:]
    @State private var searching = false

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                if query.trimmingCharacters(in: .whitespaces).count < 2 {
                    recentSearches
                } else if searching && results.isEmpty {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(L("searching")).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 40)
                } else if results.values.allSatisfy({ $0.isEmpty }) {
                    EmptyState(icon: "magnifyingglass", title: L("nothing_found", query))
                } else {
                    resultSections
                }
            }
            .padding(.vertical, 12)
        }
        .screenBackground()
        .navigationTitle(L("search"))
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: L("home_search_hint"))
        .onSubmit(of: .search) { session.rememberSearch(query) }
        .task(id: query) { await search() }
    }

    @ViewBuilder
    private var recentSearches: some View {
        if !session.searches.isEmpty {
            HStack {
                Text(L("recent_searches")).font(.headline)
                Spacer()
                Button(L("clear")) { session.clearSearches() }.font(.subheadline)
            }
            .padding(.horizontal, 16)
            ForEach(session.searches, id: \.self) { s in
                HStack {
                    Button {
                        query = s
                    } label: {
                        Label(s, systemImage: "clock.arrow.circlepath")
                            .foregroundStyle(.primary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    Button {
                        session.removeSearch(s)
                    } label: {
                        Image(systemName: "xmark").foregroundStyle(.secondary)
                    }
                    .accessibilityLabel(L("remove_search"))
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
            }
        }
    }

    @ViewBuilder
    private var resultSections: some View {
        if let live = results[.live], !live.isEmpty {
            Text(L("tab_live")).font(.title3.bold()).padding(.horizontal, 16)
            ForEach(Array(live.enumerated()), id: \.element.id) { i, item in
                ChannelRow(item: item) {
                    session.rememberSearch(query)
                    Playback.channels(live, index: i)
                }
                .contextMenu { StreamItemMenu(item: item, list: live) }
                .padding(.horizontal, 16)
            }
        }
        ForEach([ContentType.movie, .series], id: \.self) { type in
            if let items = results[type], !items.isEmpty {
                Text(L(type.titleKey)).font(.title3.bold()).padding(.horizontal, 16).padding(.top, 8)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(items) { item in
                            NavigationLink(value: item) {
                                PosterCard(title: item.name, image: item.icon, subtitle: item.year.map(String.init),
                                           favourite: session.isFavourite(item))
                                    .frame(width: 112)
                            }
                            .buttonStyle(.plain)
                            .simultaneousGesture(TapGesture().onEnded { session.rememberSearch(query) })
                            .contextMenu { StreamItemMenu(item: item) }
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
    }

    private func search() async {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else {
            results = [:]
            return
        }
        // Wait for typing to pause.
        try? await Task.sleep(nanoseconds: 300_000_000)
        guard !Task.isCancelled else { return }
        searching = true
        results = [:]
        var found: [ContentType: [StreamItem]] = [:]
        for type in [ContentType.live, .movie, .series] {
            guard let lib = try? await session.load(type), !Task.isCancelled else { continue }
            let items = session.visible(lib).items
            let matches = await Task.detached(priority: .userInitiated) {
                Array(items.lazy.filter { $0.name.range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil }.prefix(60))
            }.value
            guard !Task.isCancelled else { return }
            found[type] = matches
            results = found
        }
        searching = false
    }
}
