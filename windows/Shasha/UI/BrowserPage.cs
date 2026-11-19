using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Shasha.Core;
using static Shasha.Core.Loc;

namespace Shasha.UI;

/// <summary>Live TV, Films or Series: categories on the side, the chosen category's items on the right.</summary>
public sealed class BrowserPage : Page
{
    const string AllId = "*";
    const double PosterWidth = 164;

    readonly ContentType _type;
    readonly ListBox _categories = new() { Background = Brushes.Transparent, BorderThickness = new Thickness(0) };
    readonly TextBox _categoryFilter;
    readonly TextBox _search;
    readonly TextBlock _heading;
    readonly TextBlock _count;
    readonly ListBox _list;
    readonly ContentControl _overlay = new() { Focusable = false };
    readonly Button _sortButton;

    Library? _library;
    int _libraryVersion = -1;
    string _category = AllId;
    List<StreamItem> _shown = new();
    List<CardVM> _cards = new();
    int _perRow = 1;
    CancellationTokenSource? _cts;
    readonly System.Windows.Threading.DispatcherTimer _debounce = new() { Interval = TimeSpan.FromMilliseconds(250) };

    public BrowserPage(ContentType type)
    {
        _type = type;
        var noun = L(type.NounKey());

        // Left: categories
        _categoryFilter = Kit.Field(L("find_category"));
        _categoryFilter.TextChanged += (_, _) => FillCategories();
        ScrollViewer.SetHorizontalScrollBarVisibility(_categories, ScrollBarVisibility.Disabled);
        VirtualizingPanel.SetIsVirtualizing(_categories, true);
        _categories.SelectionChanged += (_, _) =>
        {
            if (_categories.SelectedItem is ListBoxItem { Tag: string id } && id != _category)
            {
                _category = id;
                Apply(resetScroll: true);
            }
        };
        var left = new Grid { Margin = new Thickness(24, 24, 8, 16) };
        left.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        left.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        left.RowDefinitions.Add(new RowDefinition());
        var title = Kit.Text(L(type.TitleKey()), "H1");
        left.Children.Add(title);
        _categoryFilter.Margin = new Thickness(0, 16, 0, 10);
        Grid.SetRow(_categoryFilter, 1);
        left.Children.Add(_categoryFilter);
        Grid.SetRow(_categories, 2);
        left.Children.Add(_categories);

        // Right: header and items
        _heading = Kit.Text(L("all_x", noun), "H2");
        _count = Kit.Text("", "Caption");
        _search = Kit.Field(L("search_x", noun));
        _search.Width = 300;
        _search.TextChanged += (_, _) => { _debounce.Stop(); _debounce.Start(); };
        _search.KeyDown += (_, e) => { if (e.Key == Key.Enter && _search.Text.Trim().Length > 1) Session.RememberSearch(_search.Text.Trim()); };
        _debounce.Tick += (_, _) => { _debounce.Stop(); Apply(resetScroll: true); };
        _sortButton = Kit.IconButton("\uE8CB", L("sort_by"), ShowSortMenu);
        var refresh = Kit.IconButton("\uE72C", L("refresh_content"), () => Session.RefreshAll());
        var header = new Grid { Margin = new Thickness(16, 28, 32, 14) };
        header.ColumnDefinitions.Add(new ColumnDefinition());
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var titles = Kit.Column(2, _heading, _count);
        header.Children.Add(titles);
        var tools = Kit.Row(6, _search, _sortButton, refresh);
        tools.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(tools, 1);
        header.Children.Add(tools);

        _list = Kit.List(type == ContentType.Live ? "ChannelRowTemplate" : "PosterRowTemplate");
        _list.Margin = new Thickness(16, 0, 16, 0);
        _list.Padding = new Thickness(0, 0, 16, 16);
        ItemActions.Wire(_list, OnClick, c => ItemActions.Menu((StreamItem)c.Model!, _type == ContentType.Live ? _shown : null));
        _list.SizeChanged += (_, e) => { if (e.WidthChanged) Relayout(); };

        var right = new Grid();
        right.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        right.RowDefinitions.Add(new RowDefinition());
        right.Children.Add(header);
        Grid.SetRow(_list, 1);
        right.Children.Add(_list);
        Grid.SetRow(_overlay, 1);
        right.Children.Add(_overlay);

        var root = new Grid();
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(280) });
        root.ColumnDefinitions.Add(new ColumnDefinition());
        root.Children.Add(left);
        Grid.SetColumn(right, 1);
        root.Children.Add(right);
        Content = root;

        Session.ListsChanged += OnListsChanged;
        Session.FavouritesChanged += OnFavourites;
        Session.HistoryChanged += OnFavourites;
    }

    public override TextBox? SearchBox => _search;

    public override void Shown()
    {
        if (_library == null || _libraryVersion != Session.CacheVersion) Load();
    }

    public override void Closed()
    {
        Session.ListsChanged -= OnListsChanged;
        Session.FavouritesChanged -= OnFavourites;
        Session.HistoryChanged -= OnFavourites;
        _cts?.Cancel();
    }

    void OnListsChanged()
    {
        if (IsVisible) Load(); else _libraryVersion = -1;
    }

    void OnFavourites()
    {
        foreach (var c in _cards) if (c.Model is StreamItem s) c.Favourite = Session.IsFavourite(s);
        if (Store.Sort(_type) == "favourites") Apply(resetScroll: false);
    }

    async void Load()
    {
        var noun = L(_type.NounKey());
        _libraryVersion = Session.CacheVersion;
        if (Session.Cached(_type) == null)
        {
            _overlay.Content = Kit.Message("\uE896", L("loading_x", noun));
            _list.ItemsSource = null;
        }
        try
        {
            var lib = await Session.Load(_type);
            var hide = Session.HideAdult;
            _library = await Task.Run(() => Session.FilterAdult(lib, hide));
            _overlay.Content = null;
            if (_library.Items.Count == 0)
            {
                _overlay.Content = Kit.Message("\uE7BA", L("no_x_on_account", noun));
                return;
            }
            if (_category != AllId && _library.Categories.All(c => c.Id != _category)) _category = AllId;
            FillCategories();
            Apply(resetScroll: false);
        }
        catch (Exception e)
        {
            _overlay.Content = Kit.Message("\uE7BA", L("couldnt_load_x", noun), Http.Friendly(e), L("try_again"), Load);
        }
    }

    void FillCategories()
    {
        if (_library == null) return;
        var counts = _library.Items.GroupBy(i => i.CategoryId ?? "").ToDictionary(g => g.Key, g => g.Count());
        var filter = _categoryFilter.Text.Trim();
        _categories.Items.Clear();
        _categories.Items.Add(CategoryItem(AllId, L("all_x", L(_type.NounKey())), _library.Items.Count));
        foreach (var c in _library.Categories)
        {
            if (filter.Length > 0 && !c.Name.Contains(filter, StringComparison.OrdinalIgnoreCase)) continue;
            var n = counts.GetValueOrDefault(c.Id);
            if (n == 0) continue;
            _categories.Items.Add(CategoryItem(c.Id, c.Name, n));
        }
        foreach (ListBoxItem item in _categories.Items)
            if ((string)item.Tag == _category) { _categories.SelectedItem = item; break; }
    }

    static ListBoxItem CategoryItem(string id, string name, int count)
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(new TextBlock { Text = name, FontSize = 14, VerticalAlignment = VerticalAlignment.Center });
        var n = Kit.Text(count.ToString("N0", L10n.Culture), "Caption");
        n.VerticalAlignment = VerticalAlignment.Center;
        n.Margin = new Thickness(8, 0, 0, 0);
        Grid.SetColumn(n, 1);
        grid.Children.Add(n);
        return new ListBoxItem { Tag = id, Content = grid, ToolTip = name };
    }

    /// <summary>Filters and sorts off the UI thread, then shows the result.</summary>
    async void Apply(bool resetScroll)
    {
        if (_library == null) return;
        _cts?.Cancel();
        var cts = _cts = new CancellationTokenSource();
        var lib = _library;
        var category = _category;
        var query = _search.Text.Trim();
        var sort = Store.Sort(_type);
        var favs = Session.Favourites.Select(f => f.Uid).ToHashSet();
        List<StreamItem> items;
        try
        {
            items = await Task.Run(() =>
            {
                IEnumerable<StreamItem> src = category == AllId || query.Length > 0 ? lib.Items : lib.Items.Where(i => i.CategoryId == category);
                if (query.Length > 0)
                {
                    // Searching looks in the chosen category, or everywhere from "All".
                    if (category != AllId) src = src.Where(i => i.CategoryId == category);
                    src = SearchPage.Match(src, query);
                }
                return Sorted(src, sort, favs).ToList();
            }, cts.Token);
        }
        catch (OperationCanceledException) { return; }
        if (cts.IsCancellationRequested) return;

        _shown = items;
        _heading.Text = category == AllId
            ? L("all_x", L(_type.NounKey()))
            : lib.Categories.FirstOrDefault(c => c.Id == category)?.Name ?? "";
        _count.Text = items.Count.ToString("N0", L10n.Culture) + " " + L(_type.NounKey());
        _cards = items.Select((s, i) => ItemActions.Card(s, i)).ToList();
        _overlay.Content = items.Count == 0 ? Kit.Message("\uE721", L("nothing_matches")) : null;
        Relayout();
        if (resetScroll && _list.Items.Count > 0) _list.ScrollIntoView(_list.Items[0]);
    }

    IEnumerable<StreamItem> Sorted(IEnumerable<StreamItem> items, string sort, HashSet<string> favs) => sort switch
    {
        "name" => items.OrderBy(i => i.Name, StringComparer.CurrentCultureIgnoreCase),
        "name_desc" => items.OrderByDescending(i => i.Name, StringComparer.CurrentCultureIgnoreCase),
        "added" => items.OrderByDescending(i => i.Added ?? 0),
        "rating" => items.OrderByDescending(i => i.RatingValue),
        "release_new" => items.OrderByDescending(i => i.Year ?? 0),
        "release_old" => items.OrderBy(i => i.Year ?? int.MaxValue),
        "favourites" => items.OrderBy(i => favs.Contains(i.Uid) ? 0 : 1),
        _ => items,
    };

    void Relayout()
    {
        if (_type == ContentType.Live)
        {
            if (!ReferenceEquals(_list.ItemsSource, _cards)) _list.ItemsSource = _cards;
            return;
        }
        var width = _list.ActualWidth - 40;
        var perRow = Math.Max(1, (int)(width / PosterWidth));
        if (perRow == _perRow && _list.ItemsSource is List<RowVM> rows && rows.Sum(r => r.Cells.Count) == _cards.Count && rows.FirstOrDefault()?.Cells.FirstOrDefault() == _cards.FirstOrDefault())
            return;
        _perRow = perRow;
        _list.ItemsSource = Kit.Rows(_cards, perRow);
    }

    void OnClick(CardVM card, string? tag)
    {
        if (card.Model is not StreamItem item) return;
        if (tag == "star") { Session.ToggleFavourite(item); return; }
        ItemActions.Open(item, _type == ContentType.Live ? _shown : null);
    }

    void ShowSortMenu()
    {
        var options = _type == ContentType.Live
            ? new[] { "default", "name", "name_desc", "favourites" }
            : new[] { "default", "added", "name", "name_desc", "rating", "release_new", "release_old", "favourites" };
        var current = Store.Sort(_type);
        var menu = Kit.Menu(options.Select(o => ((string, string, Action)?)(o == current ? "\uE73E" : "", L("sort_" + o), () =>
        {
            Store.SetSort(_type, o);
            Apply(resetScroll: true);
        })).ToArray());
        menu.PlacementTarget = _sortButton;
        menu.Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom;
        menu.IsOpen = true;
    }
}
