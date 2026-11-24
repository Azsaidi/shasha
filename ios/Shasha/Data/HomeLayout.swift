import Foundation
import SwiftUI

/// The rows on the Home tab. The order is chosen in Settings.
enum HomeRow: String, CaseIterable, Identifiable {
    case continueWatching, favouriteChannels, favouriteVod, recentChannels, newMovies, newSeries

    var id: String { rawValue }

    var titleKey: String {
        switch self {
        case .continueWatching: return "continue_watching"
        case .favouriteChannels: return "favourite_channels"
        case .favouriteVod: return "favourite_vod"
        case .recentChannels: return "recent_channels"
        case .newMovies: return "new_movies"
        case .newSeries: return "new_series"
        }
    }
}

/// How favourites are listed (Favourites and the Home rows).
enum FavouriteSort: String, CaseIterable, Identifiable {
    case name, nameDesc, newest, oldest

    var id: String { rawValue }

    var labelKey: String {
        switch self {
        case .name: return "sort_name"
        case .nameDesc: return "sort_name_desc"
        case .newest: return "sort_fav_newest"
        case .oldest: return "sort_fav_oldest"
        }
    }
}

/// Home row order and favourites order, saved on the device. Same as the Android and Windows apps.
@MainActor
final class HomeLayout: ObservableObject {
    static let shared = HomeLayout()
    /// Favourites straight after Continue watching.
    static let defaultRows = HomeRow.allCases

    @Published private(set) var rows: [HomeRow]
    @Published private(set) var favouriteSort: FavouriteSort

    private let defaults = UserDefaults.standard

    private init() {
        let saved = (defaults.string(forKey: "homeRows") ?? "").split(separator: ",").compactMap { HomeRow(rawValue: String($0)) }
        // Rows added in later versions go to the end of a saved order.
        var seen = Set<HomeRow>()
        rows = (saved + Self.defaultRows).filter { seen.insert($0).inserted }
        favouriteSort = FavouriteSort(rawValue: defaults.string(forKey: "favouriteSort") ?? "") ?? .name
    }

    func move(from source: IndexSet, to destination: Int) {
        var list = rows
        list.move(fromOffsets: source, toOffset: destination)
        save(list)
    }

    func reset() { save(Self.defaultRows) }

    private func save(_ list: [HomeRow]) {
        rows = list
        defaults.set(list.map(\.rawValue).joined(separator: ","), forKey: "homeRows")
    }

    func sortFavourites(by sort: FavouriteSort) {
        favouriteSort = sort
        defaults.set(sort.rawValue, forKey: "favouriteSort")
    }
}

extension Array where Element == StreamItem {
    /// Favourites in the chosen order. The saved list keeps the newest first; names compare the way
    /// people read them (numbers, accents and Arabic included), ignoring case.
    func sortedFavourites(_ sort: FavouriteSort) -> [StreamItem] {
        switch sort {
        case .name: return sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        case .nameDesc: return sorted { $0.name.localizedStandardCompare($1.name) == .orderedDescending }
        case .newest: return self
        case .oldest: return reversed()
        }
    }
}
