using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using LibVLCSharp.Shared;
using LibVLCSharp.WPF;
using Shasha.Core;
using static Shasha.Core.Loc;
using MediaPlayer = LibVLCSharp.Shared.MediaPlayer;

namespace Shasha.UI;

/// <summary>Split screen: up to four streams at once. Only the selected one is heard.</summary>
public sealed class MultiViewWindow : Window
{
    static MultiViewWindow? _instance;

    sealed class Tile
    {
        public required PlayItem Item;
        public required MediaPlayer Player;
        public required VideoView View;
        public required Border Frame;
        public required Grid Controls;
    }

    readonly List<Tile> _tiles = new();
    readonly Grid _grid = new();
    readonly Grid _root = new();
    Tile? _selected;
    readonly DispatcherTimer _hide = new() { Interval = TimeSpan.FromSeconds(3) };
    readonly Border _bar;

    public static void Open(List<PlayItem> items)
    {
        if (_instance == null)
        {
            _instance = new MultiViewWindow();
            _instance.Show();
        }
        _instance.Activate();
        foreach (var item in items) _instance.Add(item);
        var max = Session.Auth?.MaxConnections.Int() ?? 0;
        if (max > 0 && _instance._tiles.Count(t => t.Item.IsRemote) > max) Toast.Show(L("split_connections", max));
    }

    MultiViewWindow()
    {
        Title = L("split_screen");
        Background = Brushes.Black;
        Width = 1280;
        Height = 760;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Icon = System.Windows.Media.Imaging.BitmapFrame.Create(new Uri("pack://application:,,,/Assets/shasha.ico"));
        App.StyleWindow(this);
        FlowDirection = FlowDirection.LeftToRight;

        var add = Kit.Button(L("split_add"), () => { }, "\uE710");
        add.Click += (_, _) => ShowPicker(add);
        var exit = Kit.Button(L("split_exit"), Close, "\uE711");
        var buttons = Kit.Row(8, add, exit);
        buttons.Margin = new Thickness(12);
        buttons.HorizontalAlignment = HorizontalAlignment.Center;
        _bar = new Border { Child = buttons, VerticalAlignment = VerticalAlignment.Top, HorizontalAlignment = HorizontalAlignment.Center };

        _root.Children.Add(_grid);
        Content = _root;
        _hide.Tick += (_, _) => { _hide.Stop(); SetControlsVisible(false); };
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape) { if (WindowStyle == WindowStyle.None) ToggleFullScreen(); else Close(); e.Handled = true; }
            else if (e.Key is Key.F or Key.F11) { ToggleFullScreen(); e.Handled = true; }
            else
            {
                var n = (int)e.Key - (int)Key.D1;
                if (n >= 0 && n < _tiles.Count) { Select(_tiles[n]); e.Handled = true; }
            }
        };
        Closing += (_, _) =>
        {
            foreach (var t in _tiles.ToList()) Remove(t, closing: true);
            Downloads.SetStreaming(false);
        };
        Closed += (_, _) => _instance = null;
    }

    void Add(PlayItem item)
    {
        if (_tiles.Any(t => t.Item.Key == item.Key)) return;
        if (_tiles.Count >= SplitScreen.Max) { Toast.Show(L("split_full")); return; }
        var player = new MediaPlayer(PlayerWindow.Vlc) { EnableHardwareDecoding = true, EnableKeyInput = false, EnableMouseInput = false, Mute = true };
        var view = new VideoView();
        var title = new TextBlock { Text = item.Title, Foreground = Brushes.White, FontSize = 14, FontWeight = FontWeights.SemiBold, Margin = new Thickness(12, 10, 12, 0), FlowDirection = L10n.Direction };
        var controls = new Grid { Background = new SolidColorBrush(Color.FromArgb(1, 0, 0, 0)) };
        var tile = new Tile { Item = item, Player = player, View = view, Frame = new Border(), Controls = controls };
        var row = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(6) };
        row.Children.Add(TileButton("\uE767", L("audio"), () => Select(tile)));
        row.Children.Add(TileButton("\uE740", L("split_watch_single"), () => WatchAlone(tile)));
        row.Children.Add(TileButton("\uE711", L("remove"), () => Remove(tile)));
        var top = new Border
        {
            VerticalAlignment = VerticalAlignment.Top,
            Background = new LinearGradientBrush(Color.FromArgb(0xB0, 0, 0, 0), Color.FromArgb(0, 0, 0, 0), 90),
            Child = new Grid { Children = { title, row } },
        };
        controls.Children.Add(top);
        tile.Frame.BorderThickness = new Thickness(3);
        tile.Frame.BorderBrush = Brushes.Transparent;
        controls.Children.Add(tile.Frame);
        tile.Frame.IsHitTestVisible = false;
        controls.MouseLeftButtonDown += (_, e) =>
        {
            if (e.ClickCount == 2) WatchAlone(tile);
            else Select(tile);
        };
        controls.MouseMove += (_, _) => { SetControlsVisible(true); _hide.Stop(); _hide.Start(); };
        view.Content = controls;
        player.EncounteredError += (_, _) => Ui.Post(() => title.Text = item.Title + " · " + L("err_stream_network"));
        _tiles.Add(tile);
        Layout();
        var media = item.IsRemote ? new Media(PlayerWindow.Vlc, new Uri(item.Url)) : new Media(PlayerWindow.Vlc, item.Url, FromType.FromPath);
        media.AddOption($":http-user-agent={Http.UserAgent}");
        media.AddOption(":network-caching=2000");
        PlayerWindow.WhenVideoReady(view, player, () => PlayerWindow.Background(() => { player.Play(media); media.Dispose(); }));
        if (_selected == null) Select(tile);
        Downloads.SetStreaming(_tiles.Any(t => t.Item.IsRemote));
        SetControlsVisible(true);
        _hide.Start();
    }

    static Button TileButton(string glyph, string tip, Action onClick)
    {
        var b = new Button { Style = Kit.Style("IconButton"), Content = glyph, ToolTip = tip, Foreground = Brushes.White, Width = 36, Height = 36, Focusable = false };
        b.Click += (_, _) => onClick();
        return b;
    }

    void Select(Tile tile)
    {
        _selected = tile;
        foreach (var t in _tiles)
        {
            t.Player.Mute = t != tile;
            t.Frame.BorderBrush = t == tile && _tiles.Count > 1 ? Kit.Brush("Accent") : Brushes.Transparent;
        }
        Title = $"{L("split_screen")} · {tile.Item.Title}";
    }

    void WatchAlone(Tile tile)
    {
        var item = tile.Item;
        Close();
        PlayerWindow.Open(new List<PlayItem> { item }, 0, false);
    }

    void Remove(Tile tile, bool closing = false)
    {
        _tiles.Remove(tile);
        _grid.Children.Remove(tile.View);
        var player = tile.Player;
        tile.View.MediaPlayer = null;
        PlayerWindow.Background(() =>
        {
            try { player.Stop(); } catch { }
            player.Dispose();
        });
        if (closing) return;
        if (_tiles.Count == 0) { Close(); return; }
        if (_selected == tile) Select(_tiles[0]);
        Layout();
        Downloads.SetStreaming(_tiles.Any(t => t.Item.IsRemote));
    }

    void Layout()
    {
        _grid.Children.Clear();
        _grid.RowDefinitions.Clear();
        _grid.ColumnDefinitions.Clear();
        var n = _tiles.Count;
        var cols = n == 1 ? 1 : 2;
        var rows = n <= 2 ? 1 : 2;
        for (var c = 0; c < cols; c++) _grid.ColumnDefinitions.Add(new ColumnDefinition());
        for (var r = 0; r < rows; r++) _grid.RowDefinitions.Add(new RowDefinition());
        for (var i = 0; i < n; i++)
        {
            var v = _tiles[i].View;
            Grid.SetColumn(v, i % cols);
            Grid.SetRow(v, i / cols);
            // Three streams: the third spans the bottom row.
            Grid.SetColumnSpan(v, n == 3 && i == 2 ? 2 : 1);
            v.Margin = new Thickness(1);
            _grid.Children.Add(v);
        }
        if (_selected != null) Select(_selected);
        // The button bar sits in the first tile's overlay so it stays above the video.
        if (_bar.Parent is Panel old) old.Children.Remove(_bar);
        if (_tiles.Count > 0) _tiles[0].Controls.Children.Add(_bar);
        if (_tiles.Count > 0 && cols == 2) _bar.HorizontalAlignment = HorizontalAlignment.Right;
        _bar.Margin = new Thickness(0, 40, 0, 0);
    }

    void SetControlsVisible(bool visible)
    {
        foreach (var t in _tiles)
            foreach (UIElement c in t.Controls.Children)
                if (c != t.Frame) c.Visibility = visible ? Visibility.Visible : Visibility.Hidden;
        if (_tiles.Count > 0) _tiles[0].Controls.Cursor = visible ? null : Cursors.None;
    }

    void ToggleFullScreen()
    {
        if (WindowStyle != WindowStyle.None)
        {
            WindowState = WindowState.Normal;
            WindowStyle = WindowStyle.None;
            WindowState = WindowState.Maximized;
        }
        else
        {
            WindowStyle = WindowStyle.SingleBorderWindow;
            WindowState = WindowState.Normal;
        }
    }

    /// <summary>Recent and favourite channels, and whatever is playing, to add.</summary>
    void ShowPicker(Button anchor)
    {
        var entries = new List<(string, string, Action)?>();
        if (PlayerWindow.Playing is { } playing && _tiles.All(t => t.Item.Key != playing.Key))
            entries.Add(("\uE768", playing.Title, () => Add(playing)));
        foreach (var e in Session.RecentChannels().Take(10))
        {
            var entry = e;
            if (_tiles.Any(t => t.Item.Key == WatchEntry.KeyOf(WatchKind.Live, entry.StreamId))) continue;
            entries.Add(("\uE7F4", entry.Title, () => { if (Playback.EntryItem(entry) is { } it) Add(it); }));
        }
        var favs = Session.Favourites.Where(f => f.Type == ContentType.Live).Take(15).ToList();
        if (favs.Count > 0) entries.Add(null);
        foreach (var f in favs)
        {
            var fav = f;
            if (_tiles.Any(t => t.Item.Key == WatchEntry.KeyOf(WatchKind.Live, fav.Id))) continue;
            entries.Add(("\uE735", fav.Name, () => { if (Playback.LiveItem(fav) is { } it) Add(it); }));
        }
        if (entries.Count == 0)
        {
            Toast.Show(L("split_pick_empty"));
            return;
        }
        var menu = Kit.Menu(entries.ToArray());
        menu.PlacementTarget = anchor;
        menu.Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom;
        menu.IsOpen = true;
    }
}
