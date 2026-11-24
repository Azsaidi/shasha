using System.Globalization;

namespace Shasha.Core;

/// <summary>The rows on the Home page. The order is chosen in Settings.</summary>
public enum HomeRow { ContinueWatching, FavouriteChannels, FavouriteVod, RecentChannels, NewMovies, NewSeries }

/// <summary>How favourites are listed (Favourites page and the Home rows).</summary>
public enum FavouriteSort { Name, NameDesc, Newest, Oldest }

/// <summary>Home row order and favourites order, saved in the settings. Same as the Android and iPhone apps.</summary>
public static class HomeLayout
{
    /// <summary>Favourites straight after Continue watching.</summary>
    public static readonly IReadOnlyList<HomeRow> DefaultRows = Enum.GetValues<HomeRow>();

    public static event Action? Changed;

    public static IReadOnlyList<HomeRow> Rows
    {
        get
        {
            var saved = (Store.Settings.HomeRows ?? new List<string>())
                .Select(n => Enum.TryParse<HomeRow>(n, out var r) ? (HomeRow?)r : null)
                .Where(r => r != null).Select(r => r!.Value);
            // Rows added in later versions go to the end of a saved order.
            return saved.Concat(DefaultRows).Distinct().ToList();
        }
    }

    public static FavouriteSort FavouriteSort =>
        Enum.TryParse<FavouriteSort>(Store.Settings.FavouriteSort, out var s) ? s : FavouriteSort.Name;

    public static string TitleKey(HomeRow row) => row switch
    {
        HomeRow.ContinueWatching => "continue_watching",
        HomeRow.FavouriteChannels => "favourite_channels",
        HomeRow.FavouriteVod => "favourite_vod",
        HomeRow.RecentChannels => "recent_channels",
        HomeRow.NewMovies => "new_movies",
        _ => "new_series",
    };

    public static string LabelKey(FavouriteSort sort) => sort switch
    {
        FavouriteSort.Name => "sort_name",
        FavouriteSort.NameDesc => "sort_name_desc",
        FavouriteSort.Newest => "sort_fav_newest",
        _ => "sort_fav_oldest",
    };

    /// <summary>Moves <paramref name="row"/> up (-1) or down (+1).</summary>
    public static void Move(HomeRow row, int delta)
    {
        var list = Rows.ToList();
        var from = list.IndexOf(row);
        var to = from + delta;
        if (from < 0 || to < 0 || to >= list.Count) return;
        list.RemoveAt(from);
        list.Insert(to, row);
        Save(list);
    }

    public static void Reset() => Save(DefaultRows.ToList());

    static void Save(List<HomeRow> list)
    {
        Store.Settings.HomeRows = list.Select(r => r.ToString()).ToList();
        Store.SaveSettings();
        Changed?.Invoke();
    }

    public static void SortFavouritesBy(FavouriteSort sort)
    {
        Store.Settings.FavouriteSort = sort.ToString();
        Store.SaveSettings();
        Changed?.Invoke();
    }

    /// <summary>
    /// Favourites in the chosen order. The saved list keeps the newest first; names compare the way
    /// people read them (accents and Arabic included), ignoring case.
    /// </summary>
    public static List<StreamItem> SortedFavourites(this IEnumerable<StreamItem> items, FavouriteSort? sort = null)
    {
        var byName = StringComparer.Create(CultureInfo.CurrentCulture, ignoreCase: true);
        return (sort ?? FavouriteSort) switch
        {
            FavouriteSort.Name => items.OrderBy(i => i.Name.Trim(), byName).ToList(),
            FavouriteSort.NameDesc => items.OrderByDescending(i => i.Name.Trim(), byName).ToList(),
            FavouriteSort.Oldest => items.Reverse().ToList(),
            _ => items.ToList(),
        };
    }
}
