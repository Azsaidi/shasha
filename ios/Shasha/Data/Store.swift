import Foundation

/// What we keep for each account: favourites, watch history and searches.
struct AccountData: Codable {
    var favourites: [StreamItem] = []
    var history: [WatchEntry] = []
    var searches: [String] = []
    var lastRefresh: Date? = nil
}

/// Settings and saved data. Settings live in UserDefaults, per-account data in JSON files.
final class Store {
    static let shared = Store()
    private let defaults = UserDefaults.standard
    private let dir: URL

    private init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = support.appendingPathComponent("accounts", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    // MARK: Accounts

    var accounts: [Account] {
        get {
            guard let data = defaults.data(forKey: "accounts"),
                  let list = try? JSONDecoder().decode([Account].self, from: data) else { return [] }
            return list
        }
        set { defaults.set(try? JSONEncoder().encode(newValue), forKey: "accounts") }
    }

    var currentAccountId: String? {
        get { defaults.string(forKey: "currentAccount") }
        set { defaults.set(newValue, forKey: "currentAccount") }
    }

    func upsert(_ account: Account) {
        var list = accounts
        if let i = list.firstIndex(where: { $0.id == account.id }) { list[i] = account } else { list.append(account) }
        accounts = list
    }

    func remove(_ account: Account) {
        accounts = accounts.filter { $0.id != account.id }
        if currentAccountId == account.id { currentAccountId = nil }
        try? FileManager.default.removeItem(at: file(account.id))
        CatalogCache.clear(account.id)
    }

    // MARK: Per-account data

    private func file(_ id: String) -> URL { dir.appendingPathComponent("\(id).json") }

    func load(_ accountId: String) -> AccountData {
        guard let data = try? Data(contentsOf: file(accountId)),
              let value = try? JSONDecoder().decode(AccountData.self, from: data) else { return AccountData() }
        return value
    }

    func save(_ value: AccountData, for accountId: String) {
        let url = file(accountId)
        DispatchQueue.global(qos: .utility).async {
            if let data = try? JSONEncoder().encode(value) { try? data.write(to: url, options: .atomic) }
        }
    }

    // MARK: Settings

    /// "auto", "ts" or "m3u8".
    var liveFormat: String {
        get { defaults.string(forKey: "liveFormat") ?? "auto" }
        set { defaults.set(newValue, forKey: "liveFormat") }
    }

    /// "auto", "apple" or "vlc".
    var player: String {
        get { defaults.string(forKey: "player") ?? "auto" }
        set { defaults.set(newValue, forKey: "player") }
    }

    /// "Clearer sound" for a series, film or channel: 0 normal, 1 clearer, 2 much clearer.
    func clarity(_ key: String) -> Int { defaults.integer(forKey: "clarity:\(key)") }
    func setClarity(_ level: Int, for key: String) { defaults.set(level, forKey: "clarity:\(key)") }

    var hideAdult: Bool {
        get { defaults.object(forKey: "hideAdult") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "hideAdult") }
    }

    var autoPip: Bool {
        get { defaults.object(forKey: "autoPip") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "autoPip") }
    }

    /// 0 = off, 3 or 7 days.
    var autoRefreshDays: Int {
        get { defaults.object(forKey: "autoRefreshDays") as? Int ?? 7 }
        set { defaults.set(newValue, forKey: "autoRefreshDays") }
    }

    func sort(for type: ContentType) -> String {
        defaults.string(forKey: "sort_\(type.rawValue)") ?? "default"
    }

    func setSort(_ value: String, for type: ContentType) {
        defaults.set(value, forKey: "sort_\(type.rawValue)")
    }
}

/// Channel, film and series lists saved on disk so the app opens instantly.
enum CatalogCache {
    private struct Entry: Codable {
        var categories: [Category]
        var items: [StreamItem]
    }

    private static var root: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("catalog", isDirectory: true)
    }

    private static func file(_ accountId: String, _ type: ContentType) -> URL {
        root.appendingPathComponent(accountId, isDirectory: true).appendingPathComponent("\(type.rawValue.lowercased()).json")
    }

    static func load(_ accountId: String, _ type: ContentType) -> Library? {
        guard let data = try? Data(contentsOf: file(accountId, type)),
              let entry = try? JSONDecoder().decode(Entry.self, from: data) else { return nil }
        return Library(categories: entry.categories, items: entry.items)
    }

    static func save(_ accountId: String, _ type: ContentType, _ library: Library) {
        let url = file(accountId, type)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(Entry(categories: library.categories, items: library.items)) {
            try? data.write(to: url, options: .atomic)
        }
    }

    static func clear(_ accountId: String) {
        try? FileManager.default.removeItem(at: root.appendingPathComponent(accountId, isDirectory: true))
    }
}
