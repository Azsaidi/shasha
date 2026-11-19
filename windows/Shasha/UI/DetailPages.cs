using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Shasha.Core;
using static Shasha.Core.Loc;

namespace Shasha.UI;

/// <summary>Poster, details and buttons shared by the film and series pages.</summary>
public abstract class DetailPage : Page
{
    protected readonly StreamItem Item;
    protected readonly Image Poster = new() { Stretch = Stretch.UniformToFill };
    protected readonly TextBlock Meta = Kit.Text("", "Caption", size: 14);
    protected readonly TextBlock Plot = Kit.Text("", wrap: true, color: Kit.Brush("Subtle"));
    protected readonly TextBlock Credits = Kit.Text("", "Caption", wrap: true);
    protected readonly WrapPanel Actions = new();
    protected readonly StackPanel Below = new();
    protected readonly ScrollViewer Scroll;

    protected DetailPage(StreamItem item)
    {
        Item = item;
        Remote.SetDecodeWidth(Poster, 480);
        Remote.SetUrl(Poster, item.Icon);
        var posterBox = new Border
        {
            Width = 240, Height = 360, CornerRadius = new CornerRadius(14), Background = Kit.Brush("Card"),
            ClipToBounds = true, VerticalAlignment = VerticalAlignment.Top,
            Child = new Grid { Children = { Kit.Glyph(item.Type == ContentType.Series ? "\uE8F1" : "\uE8B2", 40, Kit.Brush("Subtle")), Poster } },
        };
        var title = Kit.Text(item.Name, "H1", wrap: true);
        Plot.FontSize = 15;
        Plot.LineHeight = 22;
        Plot.MaxWidth = 820;
        Plot.HorizontalAlignment = HorizontalAlignment.Left;
        Credits.MaxWidth = 820;
        Credits.HorizontalAlignment = HorizontalAlignment.Left;
        Actions.Margin = new Thickness(0, 6, 0, 0);
        var info = Kit.Column(12, title, Meta, Actions, Plot, Credits);
        info.Margin = new Thickness(32, 0, 0, 0);
        var top = new Grid();
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        top.ColumnDefinitions.Add(new ColumnDefinition());
        top.Children.Add(posterBox);
        Grid.SetColumn(info, 1);
        top.Children.Add(info);
        var col = new StackPanel { Margin = new Thickness(32, 16, 32, 32) };
        col.Children.Add(top);
        Below.Margin = new Thickness(0, 28, 0, 0);
        col.Children.Add(Below);
        Scroll = new ScrollViewer { Content = col, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        Content = Scroll;
    }

    public override string Title => Item.Name;

    protected Button Action(string label, string glyph, Action onClick, bool primary = false)
    {
        var b = Kit.Button(label, onClick, glyph, primary ? "AccentButton" : null);
        b.Margin = new Thickness(0, 0, 10, 10);
        return b;
    }

    protected Button FavouriteButton()
    {
        var fav = Session.IsFavourite(Item);
        var b = Action(fav ? L("in_favourites") : L("add_favourite"), fav ? "\uE735" : "\uE734", () => Session.ToggleFavourite(Item));
        if (fav) b.Foreground = Kit.Brush("Gold");
        return b;
    }

    protected static string Join(params string?[] parts) => string.Join("  ·  ", parts.Where(p => !string.IsNullOrWhiteSpace(p)));
}

public sealed class MovieDetailPage : DetailPage
{
    VodInfo? _info;

    public MovieDetailPage(StreamItem item) : base(item)
    {
        Meta.Text = Join(item.YearText, item.RatingValue > 0 ? $"★ {item.RatingValue:0.#}" : null);
        Session.HistoryChanged += BuildActions;
        Session.FavouritesChanged += BuildActions;
        Downloads.Changed += BuildActions;
        BuildActions();
        LoadInfo();
    }

    public override void Closed()
    {
        Session.HistoryChanged -= BuildActions;
        Session.FavouritesChanged -= BuildActions;
        Downloads.Changed -= BuildActions;
    }

    string? Ext => _info?.Ext ?? Item.Ext;

    async void LoadInfo()
    {
        if (Session.Api == null) return;
        try
        {
            _info = await Session.Api.VodInfo(Item.Id);
            if (!string.IsNullOrWhiteSpace(_info.Cover) && string.IsNullOrWhiteSpace(Item.Icon)) Remote.SetUrl(Poster, _info.Cover);
            var year = Item.YearText ?? Text.FirstYear(_info.ReleaseDate)?.ToString();
            var rating = Item.RatingValue > 0 ? Item.RatingValue : _info.Rating.Double() ?? 0;
            Meta.Text = Join(year, _info.Duration, rating > 0 ? $"★ {rating:0.#}" : null, _info.Genre);
            Plot.Text = _info.Plot ?? "";
            var credits = new List<string>();
            if (!string.IsNullOrWhiteSpace(_info.Director)) credits.Add(L("director", _info.Director));
            if (!string.IsNullOrWhiteSpace(_info.Cast)) credits.Add(L("cast", _info.Cast));
            Credits.Text = string.Join("\n", credits);
        }
        catch { }
    }

    void BuildActions()
    {
        Actions.Children.Clear();
        var entry = Session.HistoryEntry(WatchKind.Movie, Item.Id);
        if (entry is { Resumable: true } && entry.Position > 10)
        {
            Actions.Children.Add(Action(L("resume_from", Text.FormatTime(entry.Position)), "\uE768", () => Playback.Movie(Item.Id, Item.Name, Item.Icon, Ext), true));
            Actions.Children.Add(Action(L("start_over"), "\uE72C", () => Playback.Movie(Item.Id, Item.Name, Item.Icon, Ext, fromStart: true)));
        }
        else
        {
            Actions.Children.Add(Action(L("play"), "\uE768", () => Playback.Movie(Item.Id, Item.Name, Item.Icon, Ext, fromStart: true), true));
        }
        Actions.Children.Add(FavouriteButton());

        var d = Downloads.Find(WatchKind.Movie, Item.Id);
        var status = d == null ? null : Downloads.StatusOf(d);
        if (d == null)
            Actions.Children.Add(Action(L("download"), "\uE896", () => DownloadActions.Movie(Item.Id, Item.Name, Item.Icon, Ext)));
        else if (status?.State == DownloadState.Done)
            Actions.Children.Add(Action(L("downloaded"), "\uE73E", () => MainWindow.Current?.SwitchTab("downloads")));
        else if (status?.State == DownloadState.Failed)
            Actions.Children.Add(Action(L("retry_download"), "\uE72C", () => Downloads.Retry(d)));
        else
            Actions.Children.Add(Action(Downloads.StatusText(status), "\uE896", () => MainWindow.Current?.SwitchTab("downloads")));

        Actions.Children.Add(Action(L("split_add"), "\uE8A9", () => SplitScreen.Add(Playback.MovieItem(Item.Id, Item.Name, Item.Icon, Ext))));
        if (entry != null && entry.Kind == WatchKind.Movie)
        {
            var more = Kit.IconButton("\uE712", L("more"), () => { });
            more.Margin = new Thickness(0, 0, 10, 10);
            more.Click += (_, _) => Kit.ShowMenu(more, Kit.Menu(
                entry.Finished
                    ? ("\uE711", L("mark_unwatched"), () => Session.MarkUnwatched(entry))
                    : ("\uE73E", L("mark_watched"), () => Session.MarkWatched(entry))));
            Actions.Children.Add(more);
        }
    }
}

public sealed class SeriesDetailPage : DetailPage
{
    SeriesInfo? _info;
    int _season;
    readonly WrapPanel _seasons = new();
    readonly ListBox _episodes;
    readonly TextBlock _status = Kit.Text("", "Caption");
    List<EpisodeVM> _rows = new();

    public SeriesDetailPage(StreamItem item) : base(item)
    {
        Meta.Text = Join(item.YearText, item.RatingValue > 0 ? $"★ {item.RatingValue:0.#}" : null);
        _episodes = Kit.List("EpisodeTemplate");
        // The page scrolls as a whole; the episode list just grows.
        ScrollViewer.SetVerticalScrollBarVisibility(_episodes, ScrollBarVisibility.Disabled);
        VirtualizingPanel.SetIsVirtualizing(_episodes, false);
        _episodes.PreviewMouseWheel += (_, e) =>
        {
            e.Handled = true;
            Scroll.ScrollToVerticalOffset(Scroll.VerticalOffset - e.Delta);
        };
        _episodes.AddHandler(System.Windows.Controls.Primitives.ButtonBase.ClickEvent, new RoutedEventHandler((_, e) =>
        {
            var (vm, tag) = Kit.Clicked(e);
            if (vm is not EpisodeVM ep) return;
            e.Handled = true;
            if (tag == "download") EpisodeDownload(ep); else PlayEpisode(ep.Index, fromStart: false);
        }));
        _episodes.MouseRightButtonUp += (_, e) =>
        {
            if (e.OriginalSource is FrameworkElement { DataContext: EpisodeVM ep } fe) Kit.ShowMenu(fe, EpisodeMenu(ep));
        };
        _seasons.Margin = new Thickness(0, 0, 0, 14);
        var listHeader = new Grid { Margin = new Thickness(0, 0, 0, 12) };
        listHeader.ColumnDefinitions.Add(new ColumnDefinition());
        listHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        listHeader.Children.Add(Kit.Text(L("episodes"), "H2"));
        Below.Children.Add(listHeader);
        Below.Children.Add(_seasons);
        Below.Children.Add(_status);
        Below.Children.Add(_episodes);
        _status.Text = L("loading_episodes");

        Session.HistoryChanged += Refresh;
        Session.FavouritesChanged += BuildActions;
        Downloads.Changed += UpdateDownloads;
        BuildActions();
        LoadInfo();
    }

    public override void Closed()
    {
        Session.HistoryChanged -= Refresh;
        Session.FavouritesChanged -= BuildActions;
        Downloads.Changed -= UpdateDownloads;
    }

    async void LoadInfo()
    {
        if (Session.Api == null) return;
        try
        {
            _info = await Session.Api.SeriesInfo(Item.Id);
        }
        catch (Exception e)
        {
            _status.Text = L("couldnt_load_series") + " · " + Http.Friendly(e);
            var retry = Kit.Button(L("try_again"), () => { _status.Text = L("loading_episodes"); LoadInfo(); });
            retry.HorizontalAlignment = HorizontalAlignment.Left;
            retry.Margin = new Thickness(0, 10, 0, 0);
            if (!Below.Children.OfType<Button>().Any()) Below.Children.Insert(Below.Children.IndexOf(_status) + 1, retry);
            return;
        }
        foreach (var b in Below.Children.OfType<Button>().ToList()) Below.Children.Remove(b);
        if (!string.IsNullOrWhiteSpace(_info.Cover) && string.IsNullOrWhiteSpace(Item.Icon)) Remote.SetUrl(Poster, _info.Cover);
        var seasons = _info.SeasonNumbers.Where(s => _info.Seasons[s].Count > 0).ToList();
        var year = Item.YearText ?? Text.FirstYear(_info.ReleaseDate)?.ToString();
        var rating = Item.RatingValue > 0 ? Item.RatingValue : _info.Rating.Double() ?? 0;
        Meta.Text = Join(year, seasons.Count > 0 ? L10n.Plural("seasons", seasons.Count, seasons.Count) : null, rating > 0 ? $"★ {rating:0.#}" : null, _info.Genre);
        Plot.Text = _info.Plot ?? "";
        if (seasons.Count == 0) { _status.Text = L("no_episodes"); return; }
        _status.Text = "";
        _status.Visibility = Visibility.Collapsed;
        // Open on the season being watched.
        var latest = Session.LatestEpisode(Item.Id);
        _season = seasons.FirstOrDefault(s => latest != null && _info.Seasons[s].Any(e => e.Id == latest.StreamId), seasons[0]);
        BuildSeasons();
        BuildActions();
        FillEpisodes();
    }

    void BuildSeasons()
    {
        _seasons.Children.Clear();
        if (_info == null) return;
        foreach (var s in _info.SeasonNumbers.Where(s => _info.Seasons[s].Count > 0))
        {
            var chip = new RadioButton { Style = Kit.Style("Chip"), GroupName = "season" + Item.Id, Content = L("season_n", s), IsChecked = s == _season, Margin = new Thickness(0, 0, 8, 8) };
            var season = s;
            chip.Checked += (_, _) => { _season = season; FillEpisodes(); BuildActions(); };
            _seasons.Children.Add(chip);
        }
    }

    List<Episode> Current => _info != null && _info.Seasons.TryGetValue(_season, out var eps) ? eps : new();

    void FillEpisodes()
    {
        var name = _info?.Name ?? Item.Name;
        _rows = Current.Select((ep, i) =>
        {
            var h = Session.HistoryEntry(WatchKind.Episode, ep.Id);
            var details = Join(ep.Duration, ep.Plot);
            var vm = new EpisodeVM
            {
                Episode = ep,
                Index = i,
                Title = string.Join(" · ", new[] { L("episode_code", ep.Season, ep.Number), Text.TidyEpisodeTitle(ep.Title, name) }.Where(s => s != null)),
                Details = details,
                Image = ep.Image ?? _info?.Cover ?? Item.Icon,
                Progress = h is { Resumable: true } && !h.UpNext ? h.Progress : 0,
                Watched = h?.Finished == true,
            };
            return vm;
        }).ToList();
        foreach (var r in _rows) SetDownloadState(r);
        _episodes.ItemsSource = _rows;
    }

    void Refresh()
    {
        FillEpisodes();
        BuildActions();
    }

    void UpdateDownloads()
    {
        foreach (var r in _rows) SetDownloadState(r);
    }

    static void SetDownloadState(EpisodeVM vm)
    {
        var d = Downloads.Find(WatchKind.Episode, vm.Episode.Id);
        var s = d == null ? null : Downloads.StatusOf(d);
        switch (s?.State)
        {
            case null:
                vm.DownloadGlyph = "\uE896";
                vm.DownloadBrush = Kit.Brush("Fg");
                vm.DownloadText = null;
                break;
            case DownloadState.Done:
                vm.DownloadGlyph = "\uE73E";
                vm.DownloadBrush = Kit.Brush("Success");
                vm.DownloadText = L("downloaded");
                break;
            case DownloadState.Failed:
                vm.DownloadGlyph = "\uE7BA";
                vm.DownloadBrush = Kit.Brush("Warning");
                vm.DownloadText = Downloads.StatusText(s);
                break;
            default:
                vm.DownloadGlyph = "\uE118";
                vm.DownloadBrush = Kit.Brush("Accent");
                vm.DownloadText = Downloads.StatusText(s);
                break;
        }
    }

    void BuildActions()
    {
        Actions.Children.Clear();
        if (_info != null && Playback.SeriesAction(_info, Session.LatestEpisode(Item.Id)) is { } step)
        {
            Actions.Children.Add(Action(step.Label, "\uE768", () =>
                Playback.Episodes(Item.Id, _info.Name ?? Item.Name, _info.Cover ?? Item.Icon, _info.Seasons[step.Season], step.Index, step.FromStart), true));
        }
        Actions.Children.Add(FavouriteButton());
        if (_info != null && Current.Count > 0)
            Actions.Children.Add(Action(L("download_season", _season), "\uE896", () =>
                DownloadActions.Episodes(Item.Id, _info.Name ?? Item.Name, _info.Cover ?? Item.Icon, Current)));
        if (Session.HasSeriesHistory(Item.Id))
        {
            var more = Kit.IconButton("\uE712", L("more"), () => { });
            more.Margin = new Thickness(0, 0, 10, 10);
            more.Click += (_, _) => Kit.ShowMenu(more, Kit.Menu(("\uE711", L("remove_from_continue"), () => Session.RemoveSeriesHistory(Item.Id))));
            Actions.Children.Add(more);
        }
    }

    void PlayEpisode(int index, bool fromStart)
    {
        if (_info == null) return;
        Playback.Episodes(Item.Id, _info.Name ?? Item.Name, _info.Cover ?? Item.Icon, Current, index, fromStart);
    }

    void EpisodeDownload(EpisodeVM ep)
    {
        if (_info == null) return;
        var d = Downloads.Find(WatchKind.Episode, ep.Episode.Id);
        var s = d == null ? null : Downloads.StatusOf(d);
        if (d == null) DownloadActions.Episodes(Item.Id, _info.Name ?? Item.Name, _info.Cover ?? Item.Icon, new[] { ep.Episode });
        else if (s?.State == DownloadState.Failed) Downloads.Retry(d);
        else if (s?.State == DownloadState.Done) MainWindow.Current?.SwitchTab("downloads");
        else if (Kit.Confirm(L("cancel_download"), ep.Title, L("cancel_download"), danger: true)) Downloads.Remove(d);
    }

    ContextMenu EpisodeMenu(EpisodeVM ep)
    {
        var h = Session.HistoryEntry(WatchKind.Episode, ep.Episode.Id);
        var items = new List<(string, string, Action)?>();
        if (h is { Resumable: true } && h.Position > 10)
        {
            items.Add(("\uE768", L("resume_from", Text.FormatTime(h.Position)), () => PlayEpisode(ep.Index, false)));
            items.Add(("\uE72C", L("start_over"), () => PlayEpisode(ep.Index, true)));
        }
        else items.Add(("\uE768", L("play"), () => PlayEpisode(ep.Index, true)));
        var entry = h ?? new WatchEntry
        {
            Kind = WatchKind.Episode, StreamId = ep.Episode.Id, Title = _info?.Name ?? Item.Name, Subtitle = ep.Title,
            Image = ep.Image, Ext = ep.Episode.Ext, SeriesId = Item.Id, SeriesName = _info?.Name ?? Item.Name,
        };
        if (h?.Finished == true) items.Add(("\uE711", L("mark_unwatched"), () => Session.MarkUnwatched(h)));
        else items.Add(("\uE73E", L("mark_watched"), () => Session.MarkWatched(entry)));
        items.Add(null);
        var d = Downloads.Find(WatchKind.Episode, ep.Episode.Id);
        if (d == null) items.Add(("\uE896", L("download"), () => EpisodeDownload(ep)));
        else items.Add(("\uE74D", L("delete_download"), () => Downloads.Remove(d)));
        items.Add(("\uE8A9", L("split_add"), () =>
        {
            if (_info != null) SplitScreen.Add(Playback.EpisodeItem(Item.Id, _info.Name ?? Item.Name, _info.Cover ?? Item.Icon, ep.Episode));
        }));
        return Kit.Menu(items.ToArray());
    }
}
