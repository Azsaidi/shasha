import Foundation
import SwiftUI

private let adultPattern = try! NSRegularExpression(pattern: "(?i)(adult|xxx|porn|\\b18\\s*\\+|\\+\\s*18\\b)")

/// Progress of a full refresh, for the progress bar.
struct RefreshState: Equatable {
    var step: Int
    var total: Int
    var type: ContentType
    var done = false
    var error: String? = nil
}

/// Bytes downloaded so far for each list being fetched. Kept apart from Session so the frequent
/// updates only redraw the progress bar, not every screen.
final class ListProgress: ObservableObject {
    @Published var bytes: [ContentType: Int64] = [:]
}

/// The account that's open, its channel/film/series lists (memory + disk), favourites and watch history.
@MainActor
final class Session: ObservableObject {
    static let shared = Session()

    @Published private(set) var account: Account?
    private(set) var api: XtreamAPI?
    @Published private(set) var auth: AuthInfo?

    /// Bumped whenever lists change, so screens reload them.
    @Published private(set) var cacheVersion = 0
    @Published private(set) var hideAdult = Store.shared.hideAdult
    @Published private(set) var favourites: [StreamItem] = [] {
        didSet { favouriteIds = Set(favourites.map(\.uid)) }
    }
    @Published private(set) var history: [WatchEntry] = [] {
        didSet { historyIndex = Dictionary(history.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a }) }
    }
    /// Fast lookups for the many posters on screen.
    private var favouriteIds = Set<String>()
    private var historyIndex: [String: WatchEntry] = [:]
    @Published private(set) var searches: [String] = UserDefaults.standard.stringArray(forKey: "searches") ?? []

    let progress = ListProgress()
    @Published private(set) var refresh: RefreshState?

    private var libraries: [ContentType: Library] = [:]
    private var loading: [ContentType: Task<Library, Error>] = [:]
    private var refreshTask: Task<Void, Never>?
    private var lastRefresh: Date?

    private init() {
        if let id = Store.shared.currentAccountId, let acc = Store.shared.accounts.first(where: { $0.id == id }) {
            open(acc)
        }
    }

    func open(_ acc: Account, knownAuth: AuthInfo? = nil) {
        account = acc
        api = XtreamAPI(account: acc)
        auth = knownAuth
        resetLists()
        let data = Store.shared.load(acc.id)
        favourites = data.favourites
        history = data.history
        lastRefresh = data.lastRefresh
        Store.shared.currentAccountId = acc.id
        Downloads.shared.maxConnections = Int(knownAuth?.maxConnections ?? "") ?? 1
        if knownAuth == nil { refreshAuth() }
    }

    /// After editing an account: keep favourites and history, reload lists only if the login changed.
    func accountEdited(_ updated: Account, auth newAuth: AuthInfo) {
        guard account?.id == updated.id else { return }
        let old = account
        account = updated
        api = XtreamAPI(account: updated)
        auth = newAuth
        if old?.server != updated.server || old?.username != updated.username || old?.password != updated.password {
            CatalogCache.clear(updated.id)
            resetLists()
        }
    }

    func close() {
        account = nil
        api = nil
        auth = nil
        resetLists()
        favourites = []
        history = []
        Store.shared.currentAccountId = nil
    }

    private func resetLists() {
        for t in loading.values { t.cancel() }
        refreshTask?.cancel()
        refreshTask = nil
        refresh = nil
        libraries = [:]
        loading = [:]
        progress.bytes = [:]
        cacheVersion += 1
    }

    private func refreshAuth() {
        guard let acc = account, let client = api else { return }
        Task {
            if let result = try? await client.login(), self.account?.id == acc.id {
                self.auth = result
                Downloads.shared.maxConnections = Int(result.maxConnections ?? "") ?? 1
            }
        }
    }

    private func saveData() {
        guard let acc = account else { return }
        Store.shared.save(AccountData(favourites: favourites, history: history, lastRefresh: lastRefresh), for: acc.id)
    }

    // MARK: - Lists

    func cached(_ type: ContentType) -> Library? { libraries[type] }

    /// Memory, then disk, then network. Callers waiting for the same list share one download.
    func load(_ type: ContentType) async throws -> Library {
        if let lib = libraries[type] { return lib }
        guard let acc = account else { throw XtreamError.unexpected }
        if let running = loading[type] { return try await running.value }
        let task = Task { try await self.loadFromDiskOrNetwork(acc, type) }
        loading[type] = task
        do {
            let lib = try await task.value
            return lib
        } catch {
            if loading[type] == task { loading[type] = nil }
            throw error
        }
    }

    private func loadFromDiskOrNetwork(_ acc: Account, _ type: ContentType) async throws -> Library {
        let id = acc.id
        let disk = await Task.detached(priority: .userInitiated) { CatalogCache.load(id, type) }.value
        if let disk {
            if account?.id == acc.id { libraries[type] = disk }
            autoRefreshIfOld()
            return disk
        }
        return try await download(acc, type)
    }

    private func download(_ acc: Account, _ type: ContentType) async throws -> Library {
        let client = XtreamAPI(account: acc)
        progress.bytes[type] = 0
        async let cats = client.categories(type)
        async let items = client.streams(type) { bytes in
            Task { @MainActor in Session.shared.progress.bytes[type] = bytes }
        }
        let lib = Library(categories: try await cats, items: try await items)
        if account?.id == acc.id { libraries[type] = lib }
        let id = acc.id
        await Task.detached(priority: .utility) { CatalogCache.save(id, type, lib) }.value
        return lib
    }

    /// Lists older than the auto-refresh setting are updated quietly in the background.
    private func autoRefreshIfOld() {
        let days = Store.shared.autoRefreshDays
        guard days > 0, refreshTask == nil else { return }
        if lastRefresh == nil {
            lastRefresh = Date()
            saveData()
            return
        }
        if let last = lastRefresh, Date().timeIntervalSince(last) > Double(days) * 86_400 {
            refreshAll(quiet: true)
        }
    }

    /// Downloads fresh lists, one after another, with progress.
    func refreshAll(quiet: Bool = false) {
        guard let acc = account, refreshTask == nil else { return }
        let types: [ContentType] = [.live, .movie, .series]
        if !quiet {
            libraries = [:]
            for t in loading.values { t.cancel() }
            loading = [:]
            cacheVersion += 1
        }
        refreshAuth()
        refreshTask = Task {
            var failure: String?
            for (index, type) in types.enumerated() {
                guard self.account?.id == acc.id, !Task.isCancelled else { break }
                self.refresh = RefreshState(step: index + 1, total: types.count, type: type)
                let task = Task { try await self.download(acc, type) }
                // Screens waiting for this list share the download instead of starting another.
                if !quiet { self.loading[type] = task }
                do {
                    _ = try await task.value
                } catch {
                    failure = friendlyError(error)
                }
                if quiet { self.cacheVersion += 1 }
            }
            if self.account?.id == acc.id {
                if failure == nil {
                    self.lastRefresh = Date()
                    self.saveData()
                }
                self.refresh = RefreshState(step: types.count, total: types.count, type: .series, done: true, error: failure)
                self.cacheVersion += 1
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                if self.refresh?.done == true { self.refresh = nil }
            }
            self.refreshTask = nil
        }
    }

    func findItem(_ type: ContentType, _ id: String) -> StreamItem? {
        libraries[type]?.items.first { $0.id == id } ?? favourites.first { $0.type == type && $0.id == id }
    }

    // MARK: - Searches (shared by all accounts)

    func rememberSearch(_ query: String) {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard q.count >= 2 else { return }
        var list = searches.filter { $0.caseInsensitiveCompare(q) != .orderedSame }
        list.insert(q, at: 0)
        searches = Array(list.prefix(10))
        UserDefaults.standard.set(searches, forKey: "searches")
    }

    func removeSearch(_ query: String) {
        searches.removeAll { $0 == query }
        UserDefaults.standard.set(searches, forKey: "searches")
    }

    func clearSearches() {
        searches = []
        UserDefaults.standard.set(searches, forKey: "searches")
    }

    // MARK: - Adult filter

    nonisolated static func isAdult(_ text: String?) -> Bool {
        guard let text else { return false }
        return adultPattern.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }

    func setHideAdult(_ value: Bool) {
        hideAdult = value
        Store.shared.hideAdult = value
    }

    /// The list with adult categories removed when that setting is on.
    func visible(_ lib: Library) -> Library { Session.filterAdult(lib, hide: hideAdult) }

    /// Can run off the main thread (it checks every title).
    nonisolated static func filterAdult(_ lib: Library, hide: Bool) -> Library {
        guard hide else { return lib }
        let hidden = Set(lib.categories.filter { Session.isAdult($0.name) }.map(\.id))
        return Library(
            categories: lib.categories.filter { !hidden.contains($0.id) },
            items: lib.items.filter { !hidden.contains($0.categoryId ?? "") && !Session.isAdult($0.name) }
        )
    }

    // MARK: - Favourites

    func isFavourite(_ item: StreamItem) -> Bool { favouriteIds.contains(item.uid) }

    var favouriteUids: Set<String> { favouriteIds }

    func toggleFavourite(_ item: StreamItem) {
        if let i = favourites.firstIndex(where: { $0.type == item.type && $0.id == item.id }) {
            favourites.remove(at: i)
        } else {
            favourites.insert(item, at: 0)
        }
        saveData()
    }

    // MARK: - Watch history

    func historyEntry(_ kind: WatchKind, _ streamId: String) -> WatchEntry? {
        historyIndex[WatchEntry.key(kind, streamId)]
    }

    func record(_ entry: WatchEntry, accountId: String) {
        if account?.id == accountId {
            var list = history.filter { $0.key != entry.key }
            list.insert(entry, at: 0)
            history = trimmed(list)
            saveData()
        } else {
            var data = Store.shared.load(accountId)
            var list = data.history.filter { $0.key != entry.key }
            list.insert(entry, at: 0)
            data.history = trimmed(list)
            Store.shared.save(data, for: accountId)
        }
    }

    private func trimmed(_ list: [WatchEntry]) -> [WatchEntry] {
        var live = 0
        let out = list.filter { e in
            guard e.kind == .live else { return true }
            live += 1
            return live <= 30
        }
        return Array(out.prefix(300))
    }

    func removeFromHistory(_ entry: WatchEntry) {
        // For a series, drop every episode so an older one doesn't pop up in its place.
        if entry.kind == .episode, let sid = entry.seriesId {
            history.removeAll { $0.kind == .episode && $0.seriesId == sid }
        } else {
            history.removeAll { $0.key == entry.key }
        }
        saveData()
    }

    func removeSeriesHistory(_ seriesId: String) {
        history.removeAll { $0.kind == .episode && $0.seriesId == seriesId }
        saveData()
    }

    func hasSeriesHistory(_ seriesId: String) -> Bool {
        history.contains { $0.kind == .episode && $0.seriesId == seriesId }
    }

    /// Marks something as finished (shows a tick). For an episode, the next one goes into
    /// Continue watching as "Up next".
    func markWatched(_ entry: WatchEntry) {
        guard let acc = account else { return }
        var e = entry
        e.position = 0
        e.finished = true
        e.upNext = false
        e.updatedAt = Date()
        record(e, accountId: acc.id)
        if entry.kind == .episode { queueNextEpisode(after: entry, accountId: acc.id) }
    }

    /// Finds the episode after `entry` (into the next season if needed) and lists it as up next.
    private func queueNextEpisode(after entry: WatchEntry, accountId: String) {
        guard let seriesId = entry.seriesId, let client = api else { return }
        Task {
            guard let info = try? await client.seriesInfo(seriesId), self.account?.id == accountId else { return }
            let seasons = info.seasonNumbers.filter { !(info.seasons[$0] ?? []).isEmpty }
            var next: Episode?
            for (si, season) in seasons.enumerated() {
                let episodes = info.seasons[season] ?? []
                guard let i = episodes.firstIndex(where: { $0.id == entry.streamId }) else { continue }
                if i + 1 < episodes.count {
                    next = episodes[i + 1]
                } else if si + 1 < seasons.count {
                    next = info.seasons[seasons[si + 1]]?.first
                }
                break
            }
            guard let ep = next else { return }
            let now = Date().addingTimeInterval(1)
            if var existing = self.historyEntry(.episode, ep.id), !existing.finished, existing.position > 10 {
                // Already part-watched: just bring it to the front.
                existing.updatedAt = now
                self.record(existing, accountId: accountId)
                return
            }
            let seriesName = entry.seriesName ?? info.name ?? entry.title
            let subtitle = [L("episode_code", ep.season, ep.number), tidyEpisodeTitle(ep.title, seriesName: seriesName)]
                .compactMap { $0 }.joined(separator: " · ")
            self.record(WatchEntry(
                kind: .episode, streamId: ep.id, title: seriesName, subtitle: subtitle,
                image: ep.image ?? info.cover ?? entry.image, ext: ep.ext,
                seriesId: seriesId, seriesName: seriesName,
                position: 0, duration: 0, finished: false, upNext: true, updatedAt: now
            ), accountId: accountId)
        }
    }

    func markUnwatched(_ entry: WatchEntry) {
        history.removeAll { $0.key == entry.key }
        saveData()
    }

    func clearHistory() {
        history = []
        saveData()
    }

    /// Films and series to resume: the latest entry per film / series, if it isn't finished.
    var continueWatching: [WatchEntry] {
        var seen = Set<String>()
        var out: [WatchEntry] = []
        var opened: [String: WatchEntry] = [:]
        for e in history.sorted(by: { $0.updatedAt > $1.updatedAt }) where e.kind != .live {
            let group = e.kind == .episode ? "series:\(e.seriesId ?? e.streamId)" : e.key
            guard !seen.contains(group) else { continue }
            // An episode only opened for a second doesn't replace the one being watched.
            if e.barelyStarted {
                if opened[group] == nil { opened[group] = e }
                continue
            }
            seen.insert(group)
            if e.resumable {
                out.append(e)
            } else if e.finished, var next = opened[group] {
                // The last one was finished and the next one only peeked at: keep it as up next.
                next.upNext = true
                out.append(next)
            }
        }
        return out
    }

    var recentChannels: [WatchEntry] {
        Array(history.filter { $0.kind == .live }.sorted { $0.updatedAt > $1.updatedAt }.prefix(20))
    }

    /// Latest episode entry for a series (to offer "Resume S1 E5").
    func latestEpisode(_ seriesId: String) -> WatchEntry? {
        let all = history.filter { $0.kind == .episode && $0.seriesId == seriesId }
        return all.filter { !$0.barelyStarted }.max { $0.updatedAt < $1.updatedAt }
            ?? all.max { $0.updatedAt < $1.updatedAt }
    }

    // MARK: - Live format

    /// Extension to request live channels in. Apple's player needs HLS (m3u8); TS goes through VLC.
    func liveExtension() -> String {
        // Apple's player only plays live channels as HLS.
        if Store.shared.player == "apple" { return "m3u8" }
        switch Store.shared.liveFormat {
        case "ts": return "ts"
        case "m3u8": return "m3u8"
        default:
            let formats = auth?.allowedFormats ?? []
            if formats.isEmpty || formats.contains("m3u8") { return "m3u8" }
            return "ts"
        }
    }

    // MARK: - Subscription

    /// Days until the subscription ends (negative once it has), or nil when unknown/unlimited.
    var daysLeft: Int? {
        guard let exp = auth?.expDate, exp > 0 else { return nil }
        let end = Date(timeIntervalSince1970: TimeInterval(exp))
        return Calendar.current.dateComponents([.day], from: Calendar.current.startOfDay(for: Date()),
                                               to: Calendar.current.startOfDay(for: end)).day
    }

    var expiryDate: Date? {
        guard let exp = auth?.expDate, exp > 0 else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(exp))
    }
}
