using System.Text.RegularExpressions;
using static Shasha.Core.Loc;

namespace Shasha.Core;

/// <summary>Progress of a full refresh, for the progress bar.</summary>
public sealed record RefreshState(int Step, int Total, ContentType Type, bool Done = false, string? Error = null);

/// <summary>
/// The account that's open, its channel/film/series lists (memory + disk), favourites and watch history.
/// Used from the UI thread only.
/// </summary>
public static class Session
{
    public static Account? Account { get; private set; }
    public static XtreamApi? Api { get; private set; }
    public static AuthInfo? Auth { get; private set; }

    public static List<StreamItem> Favourites { get; private set; } = new();
    public static List<WatchEntry> History { get; private set; } = new();
    static HashSet<string> _favouriteIds = new();
    static Dictionary<string, WatchEntry> _historyIndex = new();
    static DateTime? _lastRefresh;

    static readonly Dictionary<ContentType, Library> Libraries = new();
    static readonly Dictionary<ContentType, Task<Library>> Loading = new();
    static CancellationTokenSource _cts = new();
    static bool _refreshing;

    public static int CacheVersion { get; private set; }
    public static RefreshState? Refresh { get; private set; }
    public static Dictionary<ContentType, long> Bytes { get; } = new();

    public static event Action? AccountChanged;
    public static event Action? ListsChanged;
    public static event Action? FavouritesChanged;
    public static event Action? HistoryChanged;
    public static event Action? RefreshChanged;
    public static event Action? SearchesChanged;

    public static bool HideAdult => Store.Settings.HideAdult;

    public static void Start()
    {
        var id = Store.Settings.CurrentAccountId;
        var acc = Store.Accounts.FirstOrDefault(a => a.Id == id);
        if (acc != null) Open(acc);
    }

    public static void Open(Account acc, AuthInfo? knownAuth = null)
    {
        Account = acc;
        Api = new XtreamApi(acc);
        Auth = knownAuth;
        ResetLists();
        var data = Store.LoadAccountData(acc.Id);
        SetFavourites(data.Favourites);
        SetHistory(data.History);
        _lastRefresh = data.LastRefresh;
        Store.Settings.CurrentAccountId = acc.Id;
        Store.SaveSettings();
        Downloads.MaxConnections = knownAuth?.MaxConnections.Int() ?? 1;
        if (knownAuth == null) RefreshAuth();
        AccountChanged?.Invoke();
    }

    /// <summary>After editing an account: keep favourites and history, reload lists only if the login changed.</summary>
    public static void AccountEdited(Account updated, AuthInfo auth)
    {
        if (Account?.Id != updated.Id) return;
        var old = Account;
        Account = updated;
        Api = new XtreamApi(updated);
        Auth = auth;
        if (old.Server != updated.Server || old.Username != updated.Username || old.Password != updated.Password)
        {
            CatalogCache.Clear(updated.Id);
            ResetLists();
        }
        AccountChanged?.Invoke();
    }

    public static void Close()
    {
        Account = null;
        Api = null;
        Auth = null;
        ResetLists();
        SetFavourites(new());
        SetHistory(new());
        Store.Settings.CurrentAccountId = null;
        Store.SaveSettings();
        AccountChanged?.Invoke();
    }

    static void ResetLists()
    {
        _cts.Cancel();
        _cts = new CancellationTokenSource();
        Libraries.Clear();
        Loading.Clear();
        Bytes.Clear();
        _refreshing = false;
        Refresh = null;
        CacheVersion++;
        ListsChanged?.Invoke();
        RefreshChanged?.Invoke();
    }

    static async void RefreshAuth()
    {
        var acc = Account;
        var api = Api;
        if (acc == null || api == null) return;
        try
        {
            var auth = await api.Login();
            if (Account?.Id != acc.Id) return;
            Auth = auth;
            Downloads.MaxConnections = auth.MaxConnections.Int() ?? 1;
            AccountChanged?.Invoke();
        }
        catch { }
    }

    static void SaveData()
    {
        if (Account == null) return;
        Store.SaveAccountData(Account.Id, new AccountData { Favourites = Favourites, History = History, LastRefresh = _lastRefresh });
    }

    // Lists

    public static Library? Cached(ContentType type) => Libraries.GetValueOrDefault(type);

    /// <summary>Memory, then disk, then network. Callers waiting for the same list share one download.</summary>
    public static Task<Library> Load(ContentType type)
    {
        if (Libraries.TryGetValue(type, out var lib)) return Task.FromResult(lib);
        if (Account == null) return Task.FromException<Library>(new XtreamException("unexpected"));
        if (Loading.TryGetValue(type, out var running)) return running;
        var task = LoadFromDiskOrNetwork(Account, type, _cts.Token);
        Loading[type] = task;
        task.ContinueWith(t =>
        {
            if (t.IsFaulted || t.IsCanceled) { if (Loading.GetValueOrDefault(type) == task) Loading.Remove(type); }
        }, TaskScheduler.FromCurrentSynchronizationContext());
        return task;
    }

    static async Task<Library> LoadFromDiskOrNetwork(Account acc, ContentType type, CancellationToken ct)
    {
        var disk = await Task.Run(() => CatalogCache.Load(acc.Id, type), ct);
        if (disk != null)
        {
            if (Account?.Id == acc.Id) Libraries[type] = disk;
            AutoRefreshIfOld();
            return disk;
        }
        return await Download(acc, type, ct);
    }

    static async Task<Library> Download(Account acc, ContentType type, CancellationToken ct)
    {
        var api = new XtreamApi(acc);
        Bytes[type] = 0;
        var progress = new Progress<long>(b => { Bytes[type] = b; RefreshChanged?.Invoke(); });
        var cats = api.Categories(type, ct);
        var items = api.Streams(type, progress, ct);
        var lib = new Library { Categories = await cats, Items = await items };
        if (Account?.Id == acc.Id) Libraries[type] = lib;
        await Task.Run(() => CatalogCache.Save(acc.Id, type, lib), CancellationToken.None);
        return lib;
    }

    /// <summary>Lists older than the auto-refresh setting are updated quietly in the background.</summary>
    static void AutoRefreshIfOld()
    {
        var days = Store.Settings.AutoRefreshDays;
        if (days <= 0 || _refreshing) return;
        if (_lastRefresh == null)
        {
            _lastRefresh = DateTime.UtcNow;
            SaveData();
            return;
        }
        if ((DateTime.UtcNow - _lastRefresh.Value).TotalDays > days) RefreshAll(quiet: true);
    }

    /// <summary>Downloads fresh lists, one after another, with progress.</summary>
    public static async void RefreshAll(bool quiet = false)
    {
        var acc = Account;
        if (acc == null || _refreshing) return;
        _refreshing = true;
        var ct = _cts.Token;
        var types = new[] { ContentType.Live, ContentType.Movie, ContentType.Series };
        if (!quiet)
        {
            Libraries.Clear();
            Loading.Clear();
            CacheVersion++;
            ListsChanged?.Invoke();
        }
        RefreshAuth();
        string? failure = null;
        for (var i = 0; i < types.Length; i++)
        {
            var type = types[i];
            if (Account?.Id != acc.Id || ct.IsCancellationRequested) break;
            Refresh = new RefreshState(i + 1, types.Length, type);
            RefreshChanged?.Invoke();
            var task = Download(acc, type, ct);
            // Screens waiting for this list share the download instead of starting another.
            if (!quiet) Loading[type] = task;
            try { await task; }
            catch (OperationCanceledException) { break; }
            catch (Exception e) { failure = Http.Friendly(e); }
            if (quiet) { CacheVersion++; ListsChanged?.Invoke(); }
        }
        if (Account?.Id == acc.Id && !ct.IsCancellationRequested)
        {
            if (failure == null)
            {
                _lastRefresh = DateTime.UtcNow;
                SaveData();
            }
            Refresh = new RefreshState(types.Length, types.Length, ContentType.Series, true, failure);
            CacheVersion++;
            ListsChanged?.Invoke();
            RefreshChanged?.Invoke();
            await Task.Delay(3500);
            if (Refresh?.Done == true) { Refresh = null; RefreshChanged?.Invoke(); }
        }
        _refreshing = false;
    }

    public static StreamItem? FindItem(ContentType type, string id) =>
        Libraries.GetValueOrDefault(type)?.Items.FirstOrDefault(i => i.Id == id) ?? Favourites.FirstOrDefault(f => f.Type == type && f.Id == id);

    // Searches (shared by all accounts)

    public static IReadOnlyList<string> Searches => Store.Settings.Searches;

    public static void RememberSearch(string query)
    {
        var q = query.Trim();
        if (q.Length < 2) return;
        var list = Store.Settings.Searches.Where(s => !string.Equals(s, q, StringComparison.OrdinalIgnoreCase)).ToList();
        list.Insert(0, q);
        Store.Settings.Searches = list.Take(10).ToList();
        Store.SaveSettings();
        SearchesChanged?.Invoke();
    }

    public static void RemoveSearch(string query)
    {
        Store.Settings.Searches.Remove(query);
        Store.SaveSettings();
        SearchesChanged?.Invoke();
    }

    public static void ClearSearches()
    {
        Store.Settings.Searches = new();
        Store.SaveSettings();
        SearchesChanged?.Invoke();
    }

    // Adult filter

    static readonly Regex AdultRx = new(@"(adult|xxx|porn|\b18\s*\+|\+\s*18\b)", RegexOptions.IgnoreCase | RegexOptions.Compiled);

    public static bool IsAdult(string? text) => text != null && AdultRx.IsMatch(text);

    public static void SetHideAdult(bool value)
    {
        Store.Settings.HideAdult = value;
        Store.SaveSettings();
        CacheVersion++;
        ListsChanged?.Invoke();
    }

    /// <summary>The list with adult categories removed (runs on any thread).</summary>
    public static Library FilterAdult(Library lib, bool hide)
    {
        if (!hide) return lib;
        var hidden = lib.Categories.Where(c => IsAdult(c.Name)).Select(c => c.Id).ToHashSet();
        return new Library
        {
            Categories = lib.Categories.Where(c => !hidden.Contains(c.Id)).ToList(),
            Items = lib.Items.Where(i => !hidden.Contains(i.CategoryId ?? "") && !IsAdult(i.Name)).ToList(),
        };
    }

    // Favourites

    static void SetFavourites(List<StreamItem> list)
    {
        Favourites = list;
        _favouriteIds = list.Select(f => f.Uid).ToHashSet();
        FavouritesChanged?.Invoke();
    }

    public static bool IsFavourite(StreamItem item) => _favouriteIds.Contains(item.Uid);

    public static void ToggleFavourite(StreamItem item)
    {
        var list = Favourites.ToList();
        var i = list.FindIndex(f => f.Uid == item.Uid);
        if (i >= 0) list.RemoveAt(i); else list.Insert(0, item);
        SetFavourites(list);
        SaveData();
        Toast.Show(i >= 0 ? L("removed_from_favourites", item.Name) : L("added_to_favourites", item.Name));
    }

    // Watch history

    static void SetHistory(List<WatchEntry> list)
    {
        History = list;
        _historyIndex = new Dictionary<string, WatchEntry>();
        foreach (var e in list) _historyIndex.TryAdd(e.Key, e);
        HistoryChanged?.Invoke();
    }

    public static WatchEntry? HistoryEntry(WatchKind kind, string id) => _historyIndex.GetValueOrDefault(WatchEntry.KeyOf(kind, id));

    public static void Record(WatchEntry entry, string accountId)
    {
        if (Account?.Id == accountId)
        {
            var list = History.Where(h => h.Key != entry.Key).ToList();
            list.Insert(0, entry);
            SetHistory(Trimmed(list));
            SaveData();
        }
        else
        {
            var data = Store.LoadAccountData(accountId);
            var list = data.History.Where(h => h.Key != entry.Key).ToList();
            list.Insert(0, entry);
            data.History = Trimmed(list);
            Store.SaveAccountData(accountId, data);
        }
    }

    static List<WatchEntry> Trimmed(List<WatchEntry> list)
    {
        var live = 0;
        return list.Where(e => e.Kind != WatchKind.Live || ++live <= 30).Take(300).ToList();
    }

    public static void RemoveFromHistory(WatchEntry entry)
    {
        // For a series, drop every episode so an older one doesn't pop up in its place.
        if (entry.Kind == WatchKind.Episode && entry.SeriesId != null)
            SetHistory(History.Where(h => !(h.Kind == WatchKind.Episode && h.SeriesId == entry.SeriesId)).ToList());
        else
            SetHistory(History.Where(h => h.Key != entry.Key).ToList());
        SaveData();
    }

    public static void RemoveSeriesHistory(string seriesId)
    {
        SetHistory(History.Where(h => !(h.Kind == WatchKind.Episode && h.SeriesId == seriesId)).ToList());
        SaveData();
    }

    public static bool HasSeriesHistory(string seriesId) => History.Any(h => h.Kind == WatchKind.Episode && h.SeriesId == seriesId);

    /// <summary>Marks something as finished. For an episode, the next one goes into Continue watching as "Up next".</summary>
    public static void MarkWatched(WatchEntry entry)
    {
        if (Account == null) return;
        var e = entry.Copy();
        e.Position = 0;
        e.Finished = true;
        e.UpNext = false;
        e.UpdatedAt = DateTime.UtcNow;
        Record(e, Account.Id);
        if (entry.Kind == WatchKind.Episode) QueueNextEpisode(entry, Account.Id);
    }

    static async void QueueNextEpisode(WatchEntry entry, string accountId)
    {
        if (entry.SeriesId == null || Api == null) return;
        SeriesInfo info;
        try { info = await Api.SeriesInfo(entry.SeriesId); } catch { return; }
        if (Account?.Id != accountId) return;
        var seasons = info.SeasonNumbers.Where(s => info.Seasons[s].Count > 0).ToList();
        Episode? next = null;
        for (var si = 0; si < seasons.Count; si++)
        {
            var eps = info.Seasons[seasons[si]];
            var i = eps.FindIndex(e => e.Id == entry.StreamId);
            if (i < 0) continue;
            next = i + 1 < eps.Count ? eps[i + 1] : si + 1 < seasons.Count ? info.Seasons[seasons[si + 1]].FirstOrDefault() : null;
            break;
        }
        if (next == null) return;
        var now = DateTime.UtcNow.AddSeconds(1);
        var existing = HistoryEntry(WatchKind.Episode, next.Id);
        if (existing != null && !existing.Finished && existing.Position > 10)
        {
            var bumped = existing.Copy();
            bumped.UpdatedAt = now;
            Record(bumped, accountId);
            return;
        }
        var seriesName = entry.SeriesName ?? info.Name ?? entry.Title;
        Record(new WatchEntry
        {
            Kind = WatchKind.Episode, StreamId = next.Id, Title = seriesName,
            Subtitle = string.Join(" · ", new[] { L("episode_code", next.Season, next.Number), Text.TidyEpisodeTitle(next.Title, seriesName) }.Where(s => s != null)),
            Image = next.Image ?? info.Cover ?? entry.Image, Ext = next.Ext, SeriesId = entry.SeriesId, SeriesName = seriesName,
            UpNext = true, UpdatedAt = now,
        }, accountId);
    }

    public static void MarkUnwatched(WatchEntry entry)
    {
        SetHistory(History.Where(h => h.Key != entry.Key).ToList());
        SaveData();
    }

    public static void ClearHistory()
    {
        SetHistory(new());
        SaveData();
    }

    /// <summary>Films and series to resume: the latest entry per film / series, if it isn't finished.</summary>
    public static List<WatchEntry> ContinueWatching()
    {
        var seen = new HashSet<string>();
        var list = new List<WatchEntry>();
        var opened = new Dictionary<string, WatchEntry>();
        foreach (var e in History.OrderByDescending(h => h.UpdatedAt))
        {
            if (e.Kind == WatchKind.Live) continue;
            var group = e.Kind == WatchKind.Episode ? $"series:{e.SeriesId ?? e.StreamId}" : e.Key;
            if (seen.Contains(group)) continue;
            // An episode only opened for a second doesn't replace the one being watched.
            if (e.BarelyStarted)
            {
                opened.TryAdd(group, e);
                continue;
            }
            seen.Add(group);
            if (e.Resumable) list.Add(e);
            else if (e.Finished && opened.TryGetValue(group, out var next))
            {
                // The last one was finished and the next one only peeked at: keep it as up next.
                var c = next.Copy();
                c.UpNext = true;
                list.Add(c);
            }
        }
        return list;
    }

    public static List<WatchEntry> RecentChannels() =>
        History.Where(h => h.Kind == WatchKind.Live).OrderByDescending(h => h.UpdatedAt).Take(20).ToList();

    public static WatchEntry? LatestEpisode(string seriesId)
    {
        var all = History.Where(h => h.Kind == WatchKind.Episode && h.SeriesId == seriesId).OrderByDescending(h => h.UpdatedAt).ToList();
        return all.FirstOrDefault(h => !h.BarelyStarted) ?? all.FirstOrDefault();
    }

    // Live format and subscription

    public static string LiveExtension()
    {
        switch (Store.Settings.LiveFormat)
        {
            case "m3u8": return "m3u8";
            case "ts": return "ts";
            default:
                var formats = Auth?.AllowedFormats ?? new List<string>();
                return formats.Count == 0 || formats.Contains("ts") ? "ts" : "m3u8";
        }
    }

    public static DateTime? ExpiryDate =>
        Auth?.ExpDate is long exp && exp > 0 ? DateTimeOffset.FromUnixTimeSeconds(exp).LocalDateTime : null;

    /// <summary>Days until the subscription ends (negative once it has), or null when unknown/unlimited.</summary>
    public static int? DaysLeft => ExpiryDate is DateTime d ? (int)(d.Date - DateTime.Today).TotalDays : null;
}
