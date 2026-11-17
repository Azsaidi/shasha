import Foundation

struct Account: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var server: String
    var username: String
    var password: String
}

enum ContentType: String, Codable, CaseIterable, Hashable {
    case live = "LIVE"
    case movie = "MOVIE"
    case series = "SERIES"

    var titleKey: String {
        switch self {
        case .live: return "tab_live"
        case .movie: return "tab_movies"
        case .series: return "tab_series"
        }
    }

    var nounKey: String {
        switch self {
        case .live: return "noun_channels"
        case .movie: return "noun_movies"
        case .series: return "noun_series"
        }
    }
}

struct Category: Codable, Hashable, Identifiable {
    let id: String
    let name: String
}

struct StreamItem: Codable, Hashable, Identifiable {
    let id: String
    let name: String
    let icon: String?
    let categoryId: String?
    let type: ContentType
    let ext: String?
    let rating: String?
    /// Unix seconds when the provider added (or last updated) it.
    let added: Int64?
    /// Release year, from the provider's data or the title.
    let year: Int?

    /// Unique across types (ids repeat between live, films and series).
    var uid: String { "\(type.rawValue):\(id)" }
    var ratingValue: Double { Double(rating ?? "") ?? 0 }
}

struct Library {
    var categories: [Category]
    var items: [StreamItem]
}

struct AuthInfo {
    var status: String?
    var expDate: Int64?
    var maxConnections: String?
    var activeConnections: String?
    var allowedFormats: [String]
}

struct Episode: Hashable, Identifiable {
    let id: String
    let title: String
    let season: Int
    let number: Int
    let ext: String
    let plot: String?
    let image: String?
    let duration: String?
}

struct SeriesInfo {
    var name: String?
    var cover: String?
    var plot: String?
    var genre: String?
    var releaseDate: String?
    var rating: String?
    /// Season number → episodes, in order.
    var seasons: [Int: [Episode]]
    var seasonNumbers: [Int] { seasons.keys.sorted() }
}

struct VodInfo {
    var name: String?
    var plot: String?
    var genre: String?
    var duration: String?
    var releaseDate: String?
    var rating: String?
    var cover: String?
    var director: String?
    var cast: String?
    var ext: String?
}

struct EpgEntry {
    let title: String
    let start: Date
    let end: Date
}

enum WatchKind: String, Codable {
    case live = "LIVE"
    case movie = "MOVIE"
    case episode = "EPISODE"

    var prefix: String {
        switch self {
        case .live: return "live"
        case .movie: return "movie"
        case .episode: return "ep"
        }
    }
}

/// One row of watch history: what was played, where it stopped, and enough to play it again.
struct WatchEntry: Codable, Hashable, Identifiable {
    var kind: WatchKind
    var streamId: String
    var title: String
    var subtitle: String? = nil
    var image: String? = nil
    var ext: String? = nil
    var seriesId: String? = nil
    var seriesName: String? = nil
    var position: Double = 0
    var duration: Double = 0
    var finished: Bool = false
    /// The episode after one that was finished, shown as "Up next".
    var upNext: Bool = false
    var updatedAt: Date = Date()

    var id: String { key }
    var key: String { WatchEntry.key(kind, streamId) }
    var progress: Double { duration > 0 ? min(max(position / duration, 0), 1) : 0 }
    var resumable: Bool { kind != .live && !finished && (position > 10 || upNext) }

    static func key(_ kind: WatchKind, _ streamId: String) -> String { "\(kind.prefix):\(streamId)" }
}

// MARK: - Text helpers

private let yearInName = try! NSRegularExpression(pattern: "\\(\\s*((?:19|20)\\d{2})\\s*\\)|[-–]\\s*((?:19|20)\\d{2})\\s*$")
private let anyYear = try! NSRegularExpression(pattern: "(19|20)\\d{2}")

/// "The Prestige (2006)" or "Film - 2006" → 2006.
func yearFromTitle(_ name: String?) -> Int? {
    guard let name else { return nil }
    let range = NSRange(name.startIndex..., in: name)
    guard let m = yearInName.firstMatch(in: name, range: range) else { return nil }
    for i in 1...2 {
        if let r = Range(m.range(at: i), in: name) { return Int(name[r]) }
    }
    return nil
}

func firstYear(in text: String?) -> Int? {
    guard let text else { return nil }
    guard let m = anyYear.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
          let r = Range(m.range, in: text) else { return nil }
    return Int(text[r])
}

private let episodeCode = try! NSRegularExpression(pattern: "(?i)\\bS\\s?\\d{1,2}\\s*[-.]?\\s*E\\s?\\d{1,4}\\b")

/// Removes the series name and S/E codes from provider episode names like "Transplant S01 E02".
func tidyEpisodeTitle(_ title: String?, seriesName: String?) -> String? {
    guard var t = title, !t.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
    if let s = seriesName?.trimmingCharacters(in: .whitespaces), !s.isEmpty {
        t = t.replacingOccurrences(of: s, with: "", options: .caseInsensitive)
    }
    t = episodeCode.stringByReplacingMatches(in: t, range: NSRange(t.startIndex..., in: t), withTemplate: " ")
    t = t.replacingOccurrences(of: "\\s{2,}", with: " ", options: .regularExpression)
    t = t.trimmingCharacters(in: CharacterSet(charactersIn: " -–—:·|.,_").union(.whitespaces))
    if t.count < 2 { return nil }
    if t.range(of: "^(?i)(episode|ep|e)\\s*\\d+$", options: .regularExpression) != nil { return nil }
    return t
}

extension WatchEntry {
    /// "S1 E2" plus the episode name only when it's a real name.
    var displaySubtitle: String? {
        guard let sub = subtitle else { return nil }
        guard kind == .episode else { return sub }
        let parts = sub.components(separatedBy: " · ")
        let code = parts.first ?? sub
        let name = tidyEpisodeTitle(parts.dropFirst().joined(separator: " · "), seriesName: seriesName ?? title)
        return [code, name].compactMap { $0 }.joined(separator: " · ")
    }
}

func formatTime(_ seconds: Double) -> String {
    let total = Int(max(seconds, 0))
    let h = total / 3600, m = (total % 3600) / 60, s = total % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}

func formatBytes(_ bytes: Int64) -> String {
    let b = Double(bytes)
    if b >= 1_073_741_824 { return String(format: "%.1f GB", b / 1_073_741_824) }
    if b >= 1_048_576 { return String(format: "%.0f MB", b / 1_048_576) }
    return String(format: "%.0f KB", b / 1024)
}

func megabytes(_ bytes: Int64) -> String { String(format: "%.1f", Double(bytes) / 1_048_576) }
