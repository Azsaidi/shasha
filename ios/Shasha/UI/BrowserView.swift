import SwiftUI

enum SortOrder: String, CaseIterable {
    case standard = "default", added, releaseNew, releaseOld, rating, name, nameDesc, favourites

    var labelKey: String {
        switch self {
        case .standard: return "sort_default"
        case .added: return "sort_added"
        case .releaseNew: return "sort_release_new"
        case .releaseOld: return "sort_release_old"
        case .rating: return "sort_rating"
        case .name: return "sort_name"
        case .nameDesc: return "sort_name_desc"
        case .favourites: return "sort_favourites"
        }
    }

    static func options(for type: ContentType) -> [SortOrder] {
        type == .live ? [.standard, .name, .nameDesc, .favourites] : allCases
    }
}

func ordered(_ items: [StreamItem], _ order: SortOrder, favourites: Set<String>) -> [StreamItem] {
    switch order {
    case .standard: return items
    case .added: return items.sorted { ($0.added ?? 0) > ($1.added ?? 0) }
    // Titles without a known year go to the end either way.
    case .releaseNew:
        return items.sorted { a, b in
            if (a.year == nil) != (b.year == nil) { return a.year != nil }
            if a.year != b.year { return (a.year ?? 0) > (b.year ?? 0) }
            return (a.added ?? 0) > (b.added ?? 0)
        }
    case .releaseOld:
        return items.sorted { a, b in
            if (a.year == nil) != (b.year == nil) { return a.year != nil }
            return (a.year ?? 0) < (b.year ?? 0)
        }
    case .rating: return items.sorted { $0.ratingValue > $1.ratingValue }
    case .name: return items.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    case .nameDesc: return items.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedDescending }
    case .favourites:
        let fav = items.filter { favourites.contains($0.uid) }
        let rest = items.filter { !favourites.contains($0.uid) }
        return fav + rest
    }
}

/// Live TV, Films or Series: categories, search, sorting and the list itself.
struct BrowserView: View {
    let type: ContentType
    @ObservedObject private var session = Session.shared
    @State private var library: Library?
    @State private var error: String?
    @State private var query = ""
    @State private var categoryId: String?
    @State private var sort: SortOrder
    @State private var shown: [StreamItem] = []
    @State private var categories: [Category] = []
    @State private var counts: [String: Int] = [:]
    @State private var total = 0
    @State private var filtering = false
    @State private var showCategories = false
    @FocusState private var searchFocused: Bool

    init(type: ContentType) {
        self.type = type
        _sort = State(initialValue: SortOrder(rawValue: Store.shared.sort(for: type)) ?? .standard)
    }

    private struct FilterKey: Hashable {
        var version: Int
        var query: String
        var category: String?
        var sort: SortOrder
        var hideAdult: Bool
        var favourites: Int
        var loaded: Bool
    }

    private var filterKey: FilterKey {
        FilterKey(version: session.cacheVersion, query: query, category: categoryId, sort: sort,
                  hideAdult: session.hideAdult,
                  favourites: sort == .favourites ? session.favourites.count : 0,
                  loaded: library != nil)
    }

    var body: some View {
        Group {
            if let library {
                content(library)
            } else if let error {
                VStack {
                    Banners()
                    EmptyState(icon: "exclamationmark.triangle", title: L("couldnt_load_x", L(type.nounKey)), message: error,
                               action: (L("try_again"), { Task { await load() } }))
                }
            } else {
                LoadingList(type: type, progress: session.progress)
            }
        }
        .screenBackground()
        .navigationTitle(L(type.titleKey))
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Menu {
                    Picker(L("sort_by"), selection: $sort) {
                        ForEach(SortOrder.options(for: type), id: \.self) { o in
                            Text(L(o.labelKey)).tag(o)
                        }
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                }
                .accessibilityLabel(L("sort_by"))
                Button {
                    session.refreshAll()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel(L("refresh_content"))
                SettingsButton()
            }
        }
        .navigationDestination(for: StreamItem.self) { item in
            DetailView(item: item)
        }
        .onChange(of: sort) { value in Store.shared.setSort(value.rawValue, for: type) }
        .task(id: session.cacheVersion) { await load() }
        .task(id: filterKey) { await applyFilter() }
        .sheet(isPresented: $showCategories) {
            CategoryPicker(categories: categories, counts: counts, total: total,
                           noun: L(type.nounKey), selected: $categoryId)
        }
    }

    private func load() async {
        error = nil
        do {
            library = try await session.load(type)
        } catch is CancellationError {
        } catch {
            if library == nil { self.error = friendlyError(error) }
        }
    }

    /// Filtering and sorting run off the main thread so typing and scrolling stay smooth.
    private func applyFilter() async {
        guard let library else { return }
        if !query.isEmpty {
            // Wait for typing to pause.
            try? await Task.sleep(nanoseconds: 150_000_000)
            guard !Task.isCancelled else { return }
        }
        filtering = true
        let hide = session.hideAdult
        let q = query.trimmingCharacters(in: .whitespaces)
        let cat = categoryId
        let order = sort
        let favs = session.favouriteUids
        let result = await Task.detached(priority: .userInitiated) { () -> ([StreamItem], [Category], [String: Int], Int) in
            let visible = Session.filterAdult(library, hide: hide)
            var counts: [String: Int] = [:]
            for item in visible.items { counts[item.categoryId ?? "", default: 0] += 1 }
            var items = visible.items
            if let cat { items = items.filter { $0.categoryId == cat } }
            if !q.isEmpty {
                items = items.filter { $0.name.range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
            }
            return (ordered(items, order, favourites: favs), visible.categories, counts, visible.items.count)
        }.value
        guard !Task.isCancelled else { return }
        shown = result.0
        categories = result.1
        counts = result.2
        total = result.3
        filtering = false
    }

    @ViewBuilder
    private func content(_ library: Library) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Banners()
                SearchField(prompt: L("search_x", L(type.nounKey)), text: $query, focus: $searchFocused) {
                    session.rememberSearch(query)
                }
                .padding(.horizontal, 16)
                if searchFocused && query.isEmpty && !session.searches.isEmpty {
                    RecentSearchChips(searches: session.searches) { query = $0 }
                }
                categoryChips
                if shown.isEmpty && !filtering {
                    if library.items.isEmpty {
                        EmptyState(icon: "tray", title: L("no_x_on_account", L(type.nounKey)))
                    } else {
                        EmptyState(icon: "magnifyingglass", title: L("nothing_matches"), message: L("try_different"))
                    }
                } else if type == .live {
                    LazyVStack(spacing: 8) {
                        ForEach(shown) { item in
                            ChannelRow(item: item, favourite: session.isFavourite(item)) {
                                if !query.isEmpty { session.rememberSearch(query) }
                                Playback.channels(shown, index: shown.firstIndex(of: item) ?? 0)
                            }
                            .contextMenu { StreamItemMenu(item: item, list: shown) }
                        }
                    }
                    .padding(.horizontal, 16)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 12, alignment: .top)],
                              alignment: .leading, spacing: 16) {
                        ForEach(shown) { item in
                            NavigationLink(value: item) {
                                PosterCard(title: item.name, image: item.icon, subtitle: item.year.map(String.init),
                                           progress: progress(item), favourite: session.isFavourite(item),
                                           downloaded: item.type == .movie && Downloads.shared.isDownloaded(.movie, item.id))
                            }
                            .buttonStyle(.plain)
                            .simultaneousGesture(TapGesture().onEnded {
                                if !query.isEmpty { session.rememberSearch(query) }
                            })
                            .contextMenu { StreamItemMenu(item: item) }
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
            .padding(.bottom, 20)
        }
        .scrollDismissesKeyboard(.immediately)
    }

    private var categoryChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 8) {
                Button {
                    showCategories = true
                } label: {
                    Label(L("categories"), systemImage: "line.3.horizontal.decrease.circle")
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 12)
                        .frame(height: 36)
                        .background(Theme.cardHigh, in: Capsule())
                }
                chip(L("all_x", L(type.nounKey)), selected: categoryId == nil) { categoryId = nil }
                ForEach(categories) { c in
                    chip(c.name, selected: categoryId == c.id) { categoryId = c.id }
                }
            }
            .padding(.horizontal, 16)
        }
        .frame(height: 40)
        .buttonStyle(.plain)
    }

    private func chip(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .lineLimit(1)
                .padding(.horizontal, 12)
                .frame(height: 36)
                .foregroundStyle(selected ? .black : .white)
                .background(selected ? Theme.accent : Theme.card, in: Capsule())
        }
    }

    private func progress(_ item: StreamItem) -> Double? {
        guard item.type == .movie, let e = session.historyEntry(.movie, item.id), e.resumable else { return nil }
        return e.progress
    }
}

/// Shown while a list downloads for the first time.
private struct LoadingList: View {
    let type: ContentType
    @ObservedObject var progress: ListProgress

    var body: some View {
        VStack(spacing: 14) {
            Banners()
            Spacer()
            ProgressView().controlSize(.large)
            Text(L("loading_x", L(type.nounKey))).font(.headline)
            if let bytes = progress.bytes[type], bytes > 0 {
                Text(L("downloaded_mb", megabytes(bytes))).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}

struct ChannelRow: View {
    let item: StreamItem
    let favourite: Bool
    let onPlay: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onPlay) {
                HStack(spacing: 12) {
                    Theme.cardHigh
                        .frame(width: 72, height: 46)
                        .overlay { CachedImage(url: item.icon, contentMode: .fit, placeholder: "tv").padding(6) }
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    Text(item.name)
                        .font(.body.weight(.medium))
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button {
                Actions.toggleFavourite(item)
            } label: {
                Image(systemName: favourite ? "star.fill" : "star")
                    .foregroundStyle(favourite ? .yellow : .secondary)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(favourite ? L("remove_favourite") : L("add_favourite"))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
    }
}

struct CategoryPicker: View {
    let categories: [Category]
    let counts: [String: Int]
    let total: Int
    let noun: String
    @Binding var selected: String?
    @State private var filter = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if filter.isEmpty {
                    row(L("all_x", noun), count: total, isSelected: selected == nil) { selected = nil }
                }
                ForEach(categories.filter { filter.isEmpty || $0.name.localizedCaseInsensitiveContains(filter) }) { c in
                    row(c.name, count: counts[c.id] ?? 0, isSelected: selected == c.id) { selected = c.id }
                }
            }
            .scrollDismissesKeyboard(.immediately)
            .searchable(text: $filter, prompt: L("find_category"))
            .navigationTitle(L("categories"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("done")) { dismiss() } }
            }
        }
    }

    private func row(_ title: String, count: Int, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button {
            action()
            dismiss()
        } label: {
            HStack {
                Text(title).foregroundStyle(.primary)
                Spacer()
                Text("\(count)").foregroundStyle(.secondary).font(.caption)
                if isSelected { Image(systemName: "checkmark").foregroundStyle(Theme.accent) }
            }
        }
    }
}
