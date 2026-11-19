using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Shasha.Core;
using static Shasha.Core.Loc;

namespace Shasha.UI;

/// <summary>Search, Continue watching, recent channels, favourites and what's new.</summary>
public sealed class HomePage : Page
{
    readonly StackPanel _sections = new() { Margin = new Thickness(32, 24, 32, 32) };
    readonly TextBox _search;
    readonly ScrollViewer _scroll;
    bool _dirty = true;

    public HomePage()
    {
        _search = Kit.Field(L("home_search_hint"));
        _search.MaxWidth = 640;
        _search.HorizontalAlignment = HorizontalAlignment.Left;
        _search.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter && _search.Text.Trim().Length > 0)
            {
                MainWindow.Push(new SearchPage(_search.Text.Trim()));
                _search.Text = "";
            }
        };
        var top = new StackPanel { Margin = new Thickness(32, 28, 32, 0) };
        top.Children.Add(Kit.Text(L("tab_home"), "H1"));
        _search.Margin = new Thickness(0, 16, 0, 0);
        top.Children.Add(_search);
        var all = new StackPanel();
        all.Children.Add(top);
        all.Children.Add(_sections);
        _scroll = new ScrollViewer { Content = all, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        Content = _scroll;

        Session.HistoryChanged += MarkDirty;
        Session.FavouritesChanged += MarkDirty;
        Session.ListsChanged += MarkDirty;
        Downloads.Changed += OnDownloads;
    }

    public override TextBox? SearchBox => _search;

    void MarkDirty()
    {
        _dirty = true;
        if (IsVisible) Build();
    }

    int _downloadedCount = -1;
    void OnDownloads()
    {
        var n = Downloads.Items.Count(i => Downloads.IsDownloaded(i.Kind, i.StreamId));
        if (n != _downloadedCount) { _downloadedCount = n; MarkDirty(); }
    }

    public override void Shown()
    {
        if (_dirty) Build();
    }

    public override void Closed()
    {
        Session.HistoryChanged -= MarkDirty;
        Session.FavouritesChanged -= MarkDirty;
        Session.ListsChanged -= MarkDirty;
        Downloads.Changed -= OnDownloads;
    }

    async void Build()
    {
        _dirty = false;
        var offset = _scroll.VerticalOffset;
        _sections.Children.Clear();

        var cont = Session.ContinueWatching();
        if (cont.Count > 0)
            AddShelf(L("continue_watching"), "WideTemplate", cont.Select((e, i) => ItemActions.Card(e, i)).ToList(),
                (c, _) => ItemActions.Open((WatchEntry)c.Model!), c => ItemActions.Menu((WatchEntry)c.Model!));

        var recent = Session.RecentChannels();
        if (recent.Count > 0)
            AddShelf(L("recent_channels"), "ChannelTileTemplate",
                recent.Select((e, i) => new CardVM { Title = e.Title, Image = e.Image, Model = e, Index = i }).ToList(),
                (c, _) => Playback.RecentChannels(recent, c.Index), c => ItemActions.Menu((WatchEntry)c.Model!));

        var favChannels = Session.Favourites.Where(f => f.Type == ContentType.Live).ToList();
        if (favChannels.Count > 0)
            AddShelf(L("favourite_channels"), "ChannelTileTemplate", favChannels.Select((f, i) => ItemActions.Card(f, i)).ToList(),
                (c, _) => ItemActions.Open((StreamItem)c.Model!, favChannels), c => ItemActions.Menu((StreamItem)c.Model!, favChannels));

        var favVod = Session.Favourites.Where(f => f.Type != ContentType.Live).ToList();
        if (favVod.Count > 0)
            AddShelf(L("favourite_vod"), "PosterTemplate", favVod.Select((f, i) => ItemActions.Card(f, i)).ToList(),
                (c, _) => ItemActions.Open((StreamItem)c.Model!), c => ItemActions.Menu((StreamItem)c.Model!));

        _ = Dispatcher.InvokeAsync(() => _scroll.ScrollToVerticalOffset(offset), System.Windows.Threading.DispatcherPriority.Loaded);

        // Recently added (needs the lists, which may still be loading).
        foreach (var (type, title) in new[] { (ContentType.Movie, L("new_movies")), (ContentType.Series, L("new_series")) })
        {
            Library lib;
            try { lib = await Session.Load(type); }
            catch { continue; }
            if (_dirty) return;
            var items = Session.FilterAdult(lib, Session.HideAdult).Items
                .Where(i => i.Added != null).OrderByDescending(i => i.Added).Take(24).ToList();
            if (items.Count == 0) continue;
            AddShelf(title, "PosterTemplate", items.Select((f, i) => ItemActions.Card(f, i)).ToList(),
                (c, _) => ItemActions.Open((StreamItem)c.Model!), c => ItemActions.Menu((StreamItem)c.Model!));
        }

        if (_sections.Children.Count == 0)
            _sections.Children.Add(Kit.Message("\uE80F", L("app_name"), L("home_empty")));
    }

    void AddShelf(string title, string template, List<CardVM> cards, Action<CardVM, string?> click, Func<CardVM, ContextMenu?> menu)
    {
        var header = Kit.Text(title, "H2");
        header.Margin = new Thickness(0, 22, 0, 12);
        var shelf = Kit.Shelf(template, cards);
        ItemActions.Wire(shelf, click, menu);
        _sections.Children.Add(header);
        _sections.Children.Add(shelf);
    }
}

/// <summary>Searches channels, films and series at once.</summary>
public sealed class SearchPage : Page
{
    readonly TextBox _box;
    readonly StackPanel _results = new();
    CancellationTokenSource? _cts;
    readonly System.Windows.Threading.DispatcherTimer _debounce = new() { Interval = TimeSpan.FromMilliseconds(300) };

    public SearchPage(string query)
    {
        _box = Kit.Field(L("search_everything"), query);
        _box.MaxWidth = 640;
        _box.HorizontalAlignment = HorizontalAlignment.Left;
        _box.TextChanged += (_, _) => { _debounce.Stop(); _debounce.Start(); };
        _box.KeyDown += (_, e) => { if (e.Key == Key.Enter) { _debounce.Stop(); Run(true); } };
        _debounce.Tick += (_, _) => { _debounce.Stop(); Run(false); };
        var col = new StackPanel { Margin = new Thickness(32, 12, 32, 32) };
        col.Children.Add(_box);
        _results.Margin = new Thickness(0, 8, 0, 0);
        col.Children.Add(_results);
        Content = new ScrollViewer { Content = col, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        Loaded += (_, _) => { _box.Focus(); _box.CaretIndex = _box.Text.Length; };
        Run(true);
    }

    public override string Title => L("search");
    public override TextBox? SearchBox => _box;
    public override void Closed() => _cts?.Cancel();

    async void Run(bool remember)
    {
        _cts?.Cancel();
        var cts = _cts = new CancellationTokenSource();
        var q = _box.Text.Trim();
        _results.Children.Clear();
        if (q.Length == 0)
        {
            ShowRecent();
            return;
        }
        if (remember) Session.RememberSearch(q);
        _results.Children.Add(Kit.Text(L("searching"), "Caption"));
        var found = 0;
        var sections = new List<(string, List<StreamItem>, ContentType)>();
        foreach (var type in new[] { ContentType.Live, ContentType.Movie, ContentType.Series })
        {
            Library lib;
            try { lib = await Session.Load(type); }
            catch { continue; }
            if (cts.IsCancellationRequested) return;
            var hide = Session.HideAdult;
            var matches = await Task.Run(() => Match(Session.FilterAdult(lib, hide).Items, q), cts.Token).ContinueWith(t => t.IsCompletedSuccessfully ? t.Result : new());
            if (cts.IsCancellationRequested) return;
            sections.Add((L(type.TitleKey()), matches, type));
            found += matches.Count;
        }
        _results.Children.Clear();
        if (found == 0)
        {
            _results.Children.Add(Kit.Message("\uE721", L("nothing_found", q)));
            return;
        }
        foreach (var (title, items, type) in sections.Where(s => s.Item2.Count > 0))
        {
            var header = Kit.Text($"{title} ({items.Count})", "H2");
            header.Margin = new Thickness(0, 22, 0, 12);
            _results.Children.Add(header);
            var shown = items.Take(60).ToList();
            var shelf = Kit.Shelf(type == ContentType.Live ? "ChannelTileTemplate" : "PosterTemplate", shown.Select((s, i) => ItemActions.Card(s, i)).ToList());
            ItemActions.Wire(shelf, (c, _) => ItemActions.Open((StreamItem)c.Model!, type == ContentType.Live ? shown : null),
                c => ItemActions.Menu((StreamItem)c.Model!, type == ContentType.Live ? shown : null));
            _results.Children.Add(shelf);
        }
    }

    /// <summary>Every word has to appear in the name; names starting with the query come first.</summary>
    public static List<StreamItem> Match(IEnumerable<StreamItem> items, string query)
    {
        var words = Normalise(query).Split(' ', StringSplitOptions.RemoveEmptyEntries);
        var q = Normalise(query);
        return items
            .Select(i => (item: i, name: Normalise(i.Name)))
            .Where(x => words.All(w => x.name.Contains(w, StringComparison.Ordinal)))
            .OrderBy(x => x.name.StartsWith(q, StringComparison.Ordinal) ? 0 : 1)
            .Select(x => x.item)
            .ToList();
    }

    static string Normalise(string s)
    {
        var d = s.ToLowerInvariant().Normalize(System.Text.NormalizationForm.FormD);
        var sb = new System.Text.StringBuilder(d.Length);
        foreach (var c in d)
        {
            var cat = System.Globalization.CharUnicodeInfo.GetUnicodeCategory(c);
            if (cat == System.Globalization.UnicodeCategory.NonSpacingMark) continue;
            // Arabic letter variants that people type interchangeably.
            sb.Append(c switch { 'أ' or 'إ' or 'آ' => 'ا', 'ة' => 'ه', 'ى' => 'ي', _ => c });
        }
        return sb.ToString();
    }

    void ShowRecent()
    {
        if (Session.Searches.Count == 0) return;
        var header = Kit.Text(L("recent_searches"), "H2");
        header.Margin = new Thickness(0, 22, 0, 12);
        _results.Children.Add(header);
        var wrap = new WrapPanel();
        foreach (var s in Session.Searches)
        {
            var chip = Kit.Button(s, () => { _box.Text = s; Run(true); }, "\uE81C");
            chip.Margin = new Thickness(0, 0, 8, 8);
            chip.Height = 34;
            chip.ContextMenu = Kit.Menu(("\uE711", L("remove_search"), () => { Session.RemoveSearch(s); Run(false); }));
            wrap.Children.Add(chip);
        }
        _results.Children.Add(wrap);
    }
}
