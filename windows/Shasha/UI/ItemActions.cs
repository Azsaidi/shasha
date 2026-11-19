using System.Windows;
using System.Windows.Controls;
using Shasha.Core;
using static Shasha.Core.Loc;

namespace Shasha.UI;

/// <summary>What clicking or right-clicking a channel, poster or history card does.</summary>
public static class ItemActions
{
    /// <summary>Main action: channels play, films and series open their page.</summary>
    public static void Open(StreamItem item, IReadOnlyList<StreamItem>? channelList = null)
    {
        switch (item.Type)
        {
            case ContentType.Live:
                var list = channelList?.ToList() ?? new List<StreamItem> { item };
                var i = list.FindIndex(c => c.Uid == item.Uid);
                Playback.Channels(list, Math.Max(0, i));
                break;
            case ContentType.Movie:
                MainWindow.Push(new MovieDetailPage(item));
                break;
            default:
                MainWindow.Push(new SeriesDetailPage(item));
                break;
        }
    }

    public static ContextMenu Menu(StreamItem item, IReadOnlyList<StreamItem>? channelList = null)
    {
        var fav = Session.IsFavourite(item);
        var entries = new List<(string, string, Action)?>();
        switch (item.Type)
        {
            case ContentType.Live:
                entries.Add(("\uE768", L("play"), () => Open(item, channelList)));
                entries.Add(("\uE8A9", L("split_add"), () => SplitScreen.Add(Playback.LiveItem(item))));
                break;
            case ContentType.Movie:
                entries.Add(("\uE768", L("play"), () => Playback.Movie(item.Id, item.Name, item.Icon, item.Ext)));
                entries.Add(("\uE946", L("info"), () => Open(item)));
                entries.Add(("\uE8A9", L("split_add"), () => SplitScreen.Add(Playback.MovieItem(item.Id, item.Name, item.Icon, item.Ext))));
                var d = Downloads.Find(WatchKind.Movie, item.Id);
                if (d == null) entries.Add(("\uE896", L("download"), () => DownloadActions.Movie(item.Id, item.Name, item.Icon, item.Ext)));
                else entries.Add(("\uE896", L("go_downloads"), () => MainWindow.Current?.SwitchTab("downloads")));
                break;
            default:
                entries.Add(("\uE768", L("play"), () => _ = Playback.PlaySeries(item)));
                entries.Add(("\uE946", L("info"), () => Open(item)));
                break;
        }
        entries.Add(null);
        entries.Add((fav ? "\uE735" : "\uE734", fav ? L("remove_favourite") : L("add_favourite"), () => Session.ToggleFavourite(item)));
        return Kit.Menu(entries.ToArray());
    }

    /// <summary>Continue watching card: resume.</summary>
    public static void Open(WatchEntry entry)
    {
        Core.Trace.Write($"resume {entry.Kind} {entry.StreamId}");
        _ = Playback.Resume(entry);
    }

    public static ContextMenu Menu(WatchEntry entry)
    {
        var entries = new List<(string, string, Action)?>
        {
            ("\uE768", entry.Position > 10 && !entry.Finished ? L("resume_from", Text.FormatTime(entry.Position)) : L("play"), () => Open(entry)),
        };
        if (entry.Kind == WatchKind.Episode && entry.SeriesId != null)
        {
            entries.Add(("\uE8F1", L("open_series"), () =>
            {
                var series = Session.FindItem(ContentType.Series, entry.SeriesId)
                             ?? new StreamItem { Id = entry.SeriesId, Name = entry.SeriesName ?? entry.Title, Icon = entry.Image, Type = ContentType.Series };
                MainWindow.Push(new SeriesDetailPage(series));
            }));
        }
        else if (entry.Kind == WatchKind.Movie)
        {
            entries.Add(("\uE946", L("info"), () =>
            {
                var movie = Session.FindItem(ContentType.Movie, entry.StreamId)
                            ?? new StreamItem { Id = entry.StreamId, Name = entry.Title, Icon = entry.Image, Type = ContentType.Movie, Ext = entry.Ext };
                MainWindow.Push(new MovieDetailPage(movie));
            }));
        }
        if (entry.Kind != WatchKind.Live)
        {
            if (Downloads.Find(entry.Kind, entry.StreamId) == null)
                entries.Add(("\uE896", L("download"), () => DownloadActions.Entry(entry)));
            else
                entries.Add(("\uE896", L("go_downloads"), () => MainWindow.Current?.SwitchTab("downloads")));
            entries.Add(("\uE8A9", L("split_add"), () => SplitScreen.Add(Playback.EntryItem(entry))));
            entries.Add(null);
            entries.Add(("\uE73E", L("mark_watched"), () => Session.MarkWatched(entry)));
            entries.Add(("\uE711", L("remove_from_continue"), () => Session.RemoveFromHistory(entry)));
        }
        else
        {
            entries.Add(("\uE8A9", L("split_add"), () => SplitScreen.Add(Playback.EntryItem(entry))));
            entries.Add(null);
            entries.Add(("\uE711", L("remove_from_recent"), () => Session.RemoveFromHistory(entry)));
        }
        return Kit.Menu(entries.ToArray());
    }

    /// <summary>Card for a stream item.</summary>
    public static CardVM Card(StreamItem item, int index = 0)
    {
        string? sub = item.Type switch
        {
            ContentType.Live => null,
            _ => string.Join(" · ", new[] { item.YearText, item.RatingValue > 0 ? $"★ {item.RatingValue:0.#}" : null }.Where(s => !string.IsNullOrEmpty(s))),
        };
        var progress = 0.0;
        if (item.Type == ContentType.Movie && Session.HistoryEntry(WatchKind.Movie, item.Id) is { Resumable: true } h) progress = h.Progress;
        return new CardVM
        {
            Title = item.Name,
            Subtitle = sub,
            Image = item.Icon,
            Model = item,
            Index = index,
            Favourite = Session.IsFavourite(item),
            Downloaded = item.Type == ContentType.Movie && Downloads.IsDownloaded(WatchKind.Movie, item.Id),
            Progress = progress,
        };
    }

    /// <summary>Card for a Continue watching entry.</summary>
    public static CardVM Card(WatchEntry e, int index = 0)
    {
        string? sub;
        if (e.UpNext) sub = e.Subtitle;
        else if (e.Duration > 0) sub = string.Join(" · ", new[] { e.Kind == WatchKind.Episode ? e.Subtitle : null, L("time_left", Text.FormatDuration(e.Duration - e.Position)) }.Where(s => s != null));
        else sub = e.Subtitle;
        return new CardVM
        {
            Title = e.Kind == WatchKind.Episode ? e.SeriesName ?? e.Title : e.Title,
            Subtitle = sub,
            Image = e.Image,
            Progress = e.UpNext ? 0 : e.Progress,
            Badge = e.UpNext ? L("up_next") : null,
            Model = e,
            Index = index,
        };
    }

    /// <summary>Wires click and right-click on a list of cards.</summary>
    public static void Wire(ItemsControl list, Action<CardVM, string?> click, Func<CardVM, ContextMenu?> menu)
    {
        list.AddHandler(System.Windows.Controls.Primitives.ButtonBase.ClickEvent, new RoutedEventHandler((_, e) =>
        {
            var (item, tag) = Kit.Clicked(e);
            if (item is CardVM card) { e.Handled = true; click(card, tag); }
        }));
        list.MouseRightButtonUp += (_, e) =>
        {
            if (e.OriginalSource is FrameworkElement { DataContext: CardVM card } fe && menu(card) is { } m)
            {
                Kit.ShowMenu(fe, m);
                e.Handled = true;
            }
        };
    }
}
