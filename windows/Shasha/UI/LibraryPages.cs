using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using Shasha.Core;
using static Shasha.Core.Loc;

namespace Shasha.UI;

public sealed class FavouritesPage : Page
{
    readonly StackPanel _col = new() { Margin = new Thickness(32, 28, 32, 32) };

    public FavouritesPage()
    {
        Content = new ScrollViewer { Content = _col, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        Session.FavouritesChanged += Build;
        Build();
    }

    public override void Closed() => Session.FavouritesChanged -= Build;

    void Build()
    {
        _col.Children.Clear();
        _col.Children.Add(Kit.Text(L("tab_favourites"), "H1"));
        if (Session.Favourites.Count == 0)
        {
            var m = Kit.Message("\uE734", L("no_favourites"), L("no_favourites_hint_win"));
            m.Margin = new Thickness(0, 80, 0, 0);
            _col.Children.Add(m);
            return;
        }
        var channels = Session.Favourites.Where(f => f.Type == ContentType.Live).ToList();
        if (channels.Count > 0) AddGroup(L("favourite_channels"), "ChannelTileTemplate", channels, channels);
        var vod = Session.Favourites.Where(f => f.Type != ContentType.Live).ToList();
        if (vod.Count > 0) AddGroup(L("favourite_vod"), "PosterTemplate", vod, null);
    }

    void AddGroup(string title, string template, List<StreamItem> items, List<StreamItem>? channels)
    {
        var header = Kit.Text(title, "H2");
        header.Margin = new Thickness(0, 24, 0, 12);
        _col.Children.Add(header);
        var grid = new ItemsControl
        {
            ItemsSource = items.Select((s, i) => ItemActions.Card(s, i)).ToList(),
            ItemTemplate = Kit.Template(template),
            ItemsPanel = new ItemsPanelTemplate(new FrameworkElementFactory(typeof(WrapPanel))),
            Focusable = false,
        };
        ItemActions.Wire(grid, (c, _) => ItemActions.Open((StreamItem)c.Model!, channels), c => ItemActions.Menu((StreamItem)c.Model!, channels));
        _col.Children.Add(grid);
    }
}

public sealed class DownloadsPage : Page
{
    readonly StackPanel _col = new() { Margin = new Thickness(32, 28, 32, 32) };
    readonly StackPanel _groups = new();
    readonly TextBlock _storage = Kit.Text("", "Caption");
    readonly Dictionary<string, DownloadVM> _vms = new();
    string _shape = "";

    public DownloadsPage()
    {
        var header = new Grid();
        header.ColumnDefinitions.Add(new ColumnDefinition());
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.Children.Add(Kit.Column(4, Kit.Text(L("tab_downloads"), "H1"), _storage));
        var open = Kit.Button(L("open_folder"), OpenFolder, "\uE8B7");
        open.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(open, 1);
        header.Children.Add(open);
        _col.Children.Add(header);
        _groups.Margin = new Thickness(0, 12, 0, 0);
        _col.Children.Add(_groups);
        Content = new ScrollViewer { Content = _col, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        Downloads.Changed += Update;
        Build();
    }

    public override void Closed() => Downloads.Changed -= Update;
    public override void Shown() => Build();

    static void OpenFolder()
    {
        try
        {
            System.IO.Directory.CreateDirectory(Store.DownloadsFolder);
            Process.Start(new ProcessStartInfo { FileName = Store.DownloadsFolder, UseShellExecute = true });
        }
        catch { }
    }

    /// <summary>Rebuilds only when items are added or removed; progress updates in place.</summary>
    void Update()
    {
        var shape = string.Join("|", Downloads.Items.Select(i => i.Key));
        if (shape != _shape) { Build(); return; }
        foreach (var vm in _vms.Values) vm.Update();
        UpdateStorage();
    }

    void UpdateStorage() => _storage.Text = L("storage_line", Text.FormatBytes(Downloads.UsedBytes), Text.FormatBytes(Downloads.FreeBytes));

    void Build()
    {
        _shape = string.Join("|", Downloads.Items.Select(i => i.Key));
        _groups.Children.Clear();
        _vms.Clear();
        UpdateStorage();
        if (Downloads.Items.Count == 0)
        {
            var m = Kit.Message("\uE896", L("downloads_empty"), L("downloads_empty_hint"));
            m.Margin = new Thickness(0, 80, 0, 0);
            _groups.Children.Add(m);
            return;
        }
        var movies = Downloads.Items.Where(i => i.Kind == WatchKind.Movie).OrderByDescending(i => i.AddedAt).ToList();
        if (movies.Count > 0) AddGroup(L("tab_movies"), movies);
        foreach (var series in Downloads.Items.Where(i => i.Kind == WatchKind.Episode)
                     .GroupBy(i => i.SeriesId ?? i.Title)
                     .OrderByDescending(g => g.Max(i => i.AddedAt)))
            AddGroup(series.First().SeriesName ?? series.First().Title, series.OrderBy(i => i.Season).ThenBy(i => i.Episode).ToList());
    }

    void AddGroup(string title, List<DownloadItem> items)
    {
        var header = Kit.Text(title, "H2");
        header.Margin = new Thickness(0, 22, 0, 10);
        _groups.Children.Add(header);
        var vms = items.Select(i =>
        {
            var vm = new DownloadVM { Item = i };
            vm.Update();
            _vms[i.Key] = vm;
            return vm;
        }).ToList();
        var list = new ItemsControl { ItemsSource = vms, ItemTemplate = Kit.Template("DownloadTemplate"), Focusable = false };
        list.AddHandler(System.Windows.Controls.Primitives.ButtonBase.ClickEvent, new RoutedEventHandler((_, e) =>
        {
            var (obj, tag) = Kit.Clicked(e);
            if (obj is not DownloadVM vm) return;
            e.Handled = true;
            switch (tag)
            {
                case "play": Playback.Downloaded(vm.Item); break;
                case "retry": Downloads.Retry(vm.Item); break;
                case "delete":
                    var done = Downloads.StatusOf(vm.Item)?.State == DownloadState.Done;
                    if (Kit.Confirm(done ? L("delete_download") : L("cancel_download"), vm.Title, done ? L("delete_download") : L("cancel_download"), danger: true))
                        Downloads.Remove(vm.Item);
                    break;
            }
        }));
        list.MouseDoubleClick += (_, e) =>
        {
            if (e.OriginalSource is FrameworkElement { DataContext: DownloadVM vm } && Downloads.IsDownloaded(vm.Item.Kind, vm.Item.StreamId))
                Playback.Downloaded(vm.Item);
        };
        _groups.Children.Add(list);
    }
}
