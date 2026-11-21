using static Shasha.Core.Loc;

namespace Shasha.Core;

public sealed record PlayItem(
    string Title, string Url, WatchKind Kind, string StreamId, string AccountId,
    string? Subtitle = null, string? Image = null, string? Ext = null, string? SeriesId = null, string? SeriesName = null)
{
    public bool IsLive => Kind == WatchKind.Live;
    public string Key => WatchEntry.KeyOf(Kind, StreamId);
    public bool IsRemote => Url.StartsWith("http", StringComparison.OrdinalIgnoreCase);
}

/// <summary>Builds play queues for each kind of content. The UI layer shows the player.</summary>
public static class Playback
{
    /// <summary>Set by the app: opens the player with a queue.</summary>
    public static Action<List<PlayItem>, int, bool>? Present;

    public static List<PlayItem> LastQueue { get; private set; } = new();

    static void Start(List<PlayItem> items, int index, bool fromStart = false)
    {
        if (items.Count == 0) return;
        Trace.Write($"play {items.Count} item(s), starting at {index}");
        LastQueue = items;
        Present?.Invoke(items, Math.Clamp(index, 0, items.Count - 1), fromStart);
    }

    public static PlayItem? LiveItem(StreamItem item) => LiveItem(item.Id, item.Name, item.Icon);

    public static PlayItem? LiveItem(string id, string name, string? icon)
    {
        if (Session.Api == null || Session.Account == null) return null;
        return new PlayItem(name, Session.Api.LiveUrl(id, Session.LiveExtension()), WatchKind.Live, id, Session.Account.Id, Image: icon);
    }

    public static PlayItem? MovieItem(string id, string title, string? image, string? ext)
    {
        if (Session.Account == null) return null;
        var e = ext ?? "mp4";
        var url = Downloads.LocalPath(WatchKind.Movie, id) ?? Session.Api?.MovieUrl(id, e);
        return url == null ? null : new PlayItem(title, url, WatchKind.Movie, id, Session.Account.Id, Image: image, Ext: e);
    }

    public static PlayItem? EpisodeItem(string seriesId, string seriesName, string? cover, Episode ep)
    {
        if (Session.Account == null) return null;
        var url = Downloads.LocalPath(WatchKind.Episode, ep.Id) ?? Session.Api?.EpisodeUrl(ep.Id, ep.Ext);
        if (url == null) return null;
        return new PlayItem($"{seriesName} · {L("episode_code", ep.Season, ep.Number)}", url, WatchKind.Episode, ep.Id, Session.Account.Id,
            Text.TidyEpisodeTitle(ep.Title, seriesName), ep.Image ?? cover, ep.Ext, seriesId, seriesName);
    }

    /// <summary>A play item from a history entry (Continue watching, split screen).</summary>
    public static PlayItem? EntryItem(WatchEntry e)
    {
        switch (e.Kind)
        {
            case WatchKind.Live: return LiveItem(e.StreamId, e.Title, e.Image);
            case WatchKind.Movie: return MovieItem(e.StreamId, e.Title, e.Image, e.Ext);
            default:
                if (Session.Account == null) return null;
                var ext = e.Ext ?? "mp4";
                var url = Downloads.LocalPath(WatchKind.Episode, e.StreamId) ?? Session.Api?.EpisodeUrl(e.StreamId, ext);
                if (url == null) return null;
                var parts = (e.Subtitle ?? "").Split(" · ");
                var name = string.Join(" · ", parts.Skip(1));
                return new PlayItem(string.Join(" · ", new[] { e.SeriesName ?? e.Title, parts.FirstOrDefault() }.Where(s => !string.IsNullOrEmpty(s))),
                    url, WatchKind.Episode, e.StreamId, Session.Account.Id, name.Length == 0 ? null : name, e.Image, ext, e.SeriesId, e.SeriesName);
        }
    }

    public static PlayItem? DownloadItem(DownloadItem d)
    {
        var path = Downloads.LocalPath(d.Kind, d.StreamId);
        if (path == null) return null;
        var parts = (d.Subtitle ?? "").Split(" · ");
        var title = d.Kind == WatchKind.Episode
            ? string.Join(" · ", new[] { d.SeriesName ?? d.Title, parts.FirstOrDefault() }.Where(s => !string.IsNullOrEmpty(s)))
            : d.Title;
        var sub = string.Join(" · ", parts.Skip(1));
        return new PlayItem(title, path, d.Kind, d.StreamId, d.AccountId, sub.Length == 0 ? null : sub, d.Image, d.Ext, d.SeriesId, d.SeriesName);
    }

    public static void Channels(List<StreamItem> channels, int index)
    {
        // Keep the queue small enough to build instantly, centred on the chosen channel.
        var lower = Math.Max(0, index - 300);
        var upper = Math.Min(channels.Count, index + 300);
        Start(channels.Skip(lower).Take(upper - lower).Select(LiveItem).OfType<PlayItem>().ToList(), index - lower);
    }

    public static void RecentChannels(List<WatchEntry> entries, int index) =>
        Start(entries.Select(e => LiveItem(e.StreamId, e.Title, e.Image)).OfType<PlayItem>().ToList(), index);

    public static void Movie(string id, string title, string? image, string? ext, bool fromStart = false)
    {
        var item = MovieItem(id, title, image, ext);
        if (item != null) Start(new List<PlayItem> { item }, 0, fromStart);
    }

    public static void Episodes(string seriesId, string seriesName, string? cover, List<Episode> episodes, int index, bool fromStart = false) =>
        Start(episodes.Select(e => EpisodeItem(seriesId, seriesName, cover, e)).OfType<PlayItem>().ToList(), index, fromStart);

    public static void Downloaded(DownloadItem d)
    {
        List<DownloadItem> queue;
        if (d.Kind == WatchKind.Episode && d.SeriesId != null)
            queue = Downloads.Items.Where(i => i.Kind == WatchKind.Episode && i.SeriesId == d.SeriesId && Downloads.IsDownloaded(i.Kind, i.StreamId))
                .OrderBy(i => i.Season).ThenBy(i => i.Episode).ToList();
        else queue = new List<DownloadItem> { d };
        var items = queue.Select(DownloadItem).OfType<PlayItem>().ToList();
        Start(items, Math.Max(0, items.FindIndex(i => i.StreamId == d.StreamId)));
    }

    /// <summary>True while a series is looked up before the player opens; more clicks are ignored.</summary>
    static bool _opening;

    /// <summary>Runs a lookup that ends in playing something, once at a time, with a message.</summary>
    static async Task WhileOpening(Func<Task> work)
    {
        if (_opening) return;
        _opening = true;
        Toast.Show(L("loading_episodes"));
        try { await work(); }
        finally { _opening = false; }
    }

    /// <summary>Continue a history entry. For episodes the rest of the season is queued too.</summary>
    public static async Task Resume(WatchEntry entry)
    {
        switch (entry.Kind)
        {
            case WatchKind.Live: RecentChannels(new List<WatchEntry> { entry }, 0); return;
            case WatchKind.Movie: Movie(entry.StreamId, entry.Title, entry.Image, entry.Ext); return;
        }
        await WhileOpening(() => ResumeEpisode(entry));
    }

    static async Task ResumeEpisode(WatchEntry entry)
    {
        SeriesInfo? info = null;
        if (entry.SeriesId != null && Session.Api != null)
        {
            try
            {
                var task = Session.Api.SeriesInfo(entry.SeriesId);
                if (await Task.WhenAny(task, Task.Delay(8000)) == task) info = await task;
                Trace.Write(info == null ? "resume: series info timed out" : "resume: series info loaded");
            }
            catch { }
        }
        if (info != null)
        {
            foreach (var season in info.Seasons.Values)
            {
                var i = season.FindIndex(e => e.Id == entry.StreamId);
                if (i < 0) continue;
                Episodes(entry.SeriesId!, entry.SeriesName ?? info.Name ?? entry.Title, info.Cover ?? entry.Image, season, i);
                return;
            }
        }
        // Offline or the provider didn't answer: play just this one (from the download if there is one).
        var item = EntryItem(entry);
        if (item != null) Start(new List<PlayItem> { item }, 0);
    }

    /// <summary>Plays a series straight from its poster: resume, next episode, or the first one.</summary>
    public static Task PlaySeries(StreamItem series) => WhileOpening(() => PlaySeriesNow(series));

    static async Task PlaySeriesNow(StreamItem series)
    {
        if (Session.Api == null) return;
        try
        {
            var info = await Session.Api.SeriesInfo(series.Id);
            var action = SeriesAction(info, Session.LatestEpisode(series.Id));
            if (action == null) { Toast.Show(L("no_episodes")); return; }
            Episodes(series.Id, series.Name, info.Cover ?? series.Icon, info.Seasons[action.Season], action.Index, action.FromStart);
        }
        catch { Toast.Show(L("couldnt_load_series")); }
    }

    public sealed record SeriesStep(string Label, int Season, int Index, bool FromStart);

    /// <summary>What the main series button does: resume, move on to the next episode, or start at the beginning.</summary>
    public static SeriesStep? SeriesAction(SeriesInfo info, WatchEntry? latest)
    {
        var numbers = info.SeasonNumbers.Where(s => info.Seasons[s].Count > 0).ToList();
        if (numbers.Count == 0) return null;
        var first = info.Seasons[numbers[0]][0];
        string Code(Episode e) => L("episode_code", e.Season, e.Number);
        if (latest != null)
        {
            for (var si = 0; si < numbers.Count; si++)
            {
                var eps = info.Seasons[numbers[si]];
                var i = eps.FindIndex(e => e.Id == latest.StreamId);
                if (i < 0) continue;
                var ep = eps[i];
                if (!latest.Finished)
                    return new SeriesStep(latest.Position > 10 ? L("resume_ep", Code(ep), Text.FormatTime(latest.Position)) : L("play_ep", Code(ep)), numbers[si], i, false);
                if (i + 1 < eps.Count) return new SeriesStep(L("play_next_ep", Code(eps[i + 1])), numbers[si], i + 1, true);
                if (si + 1 < numbers.Count) return new SeriesStep(L("play_next_ep", Code(info.Seasons[numbers[si + 1]][0])), numbers[si + 1], 0, true);
                return new SeriesStep(L("watch_again", Code(first)), numbers[0], 0, true);
            }
        }
        return new SeriesStep(L("play_ep", Code(first)), numbers[0], 0, true);
    }
}

/// <summary>Download helpers that need the open account.</summary>
public static class DownloadActions
{
    public static void Movie(string id, string title, string? image, string? ext)
    {
        if (Session.Api == null || Session.Account == null) return;
        var e = ext ?? "mp4";
        var ok = Downloads.Enqueue(Session.Api.MovieUrl(id, e), WatchKind.Movie, id, Session.Account.Id, title, null, image, e);
        Toast.Show(ok ? L("download_started", title) : L("download_error"));
    }

    public static void Episodes(string seriesId, string seriesName, string? cover, IEnumerable<Episode> episodes)
    {
        if (Session.Api == null || Session.Account == null) return;
        var started = 0;
        foreach (var ep in episodes)
        {
            var existing = Downloads.Find(WatchKind.Episode, ep.Id);
            if (existing != null && Downloads.StatusOf(existing)?.State != DownloadState.Failed) continue;
            var subtitle = string.Join(" · ", new[] { L("episode_code", ep.Season, ep.Number), Text.TidyEpisodeTitle(ep.Title, seriesName) }.Where(s => s != null));
            if (Downloads.Enqueue(Session.Api.EpisodeUrl(ep.Id, ep.Ext), WatchKind.Episode, ep.Id, Session.Account.Id, seriesName, subtitle,
                    ep.Image ?? cover, ep.Ext, seriesId, seriesName, ep.Season, ep.Number)) started++;
        }
        Toast.Show(started > 0 ? L10n.Plural("episodes_downloading", started) : L("season_all_downloaded"));
    }

    /// <summary>An episode straight from Continue watching.</summary>
    public static void Entry(WatchEntry e)
    {
        if (e.Kind == WatchKind.Movie) { Movie(e.StreamId, e.Title, e.Image, e.Ext); return; }
        if (Session.Api == null || Session.Account == null) return;
        var ext = e.Ext ?? "mp4";
        var m = System.Text.RegularExpressions.Regex.Match(e.Subtitle ?? "", @"(\d+)\D+(\d+)");
        var ok = Downloads.Enqueue(Session.Api.EpisodeUrl(e.StreamId, ext), WatchKind.Episode, e.StreamId, Session.Account.Id,
            e.SeriesName ?? e.Title, e.Subtitle, e.Image, ext, e.SeriesId, e.SeriesName ?? e.Title,
            m.Success ? int.Parse(m.Groups[1].Value) : 0, m.Success ? int.Parse(m.Groups[2].Value) : 0);
        Toast.Show(ok ? L("download_started", e.Subtitle ?? e.Title) : L("download_error"));
    }
}
