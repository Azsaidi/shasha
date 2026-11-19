using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using Shasha.Core;
using static Shasha.Core.Loc;

namespace Shasha.UI;

/// <summary>Streams picked for split screen, before it opens.</summary>
public static class SplitScreen
{
    public const int Max = 4;
    public static List<PlayItem> Picks { get; } = new();
    public static event Action? Changed;

    public static void Add(PlayItem? item)
    {
        if (item == null) return;
        if (Picks.Any(p => p.Key == item.Key)) return;
        if (Picks.Count >= Max) { Toast.Show(L("split_full")); return; }
        Picks.Add(item);
        Toast.Show(L("split_added", Picks.Count));
        Changed?.Invoke();
    }

    public static void Clear()
    {
        Picks.Clear();
        Changed?.Invoke();
    }

    public static void Watch()
    {
        if (Picks.Count == 0) return;
        MultiViewWindow.Open(Picks.ToList());
        Clear();
    }
}

public sealed class MainWindow : Window
{
    public static MainWindow? Current { get; private set; }

    readonly Grid _root = new();
    readonly StackPanel _rail = new();
    readonly Border _railHost;
    readonly ContentControl _pageHost = new() { Focusable = false };
    readonly StackPanel _banners = new();
    readonly Border _toast;
    readonly TextBlock _toastText = new() { FontSize = 14, TextWrapping = TextWrapping.Wrap, TextTrimming = TextTrimming.None };
    readonly Grid _header = new() { Margin = new Thickness(24, 16, 24, 0), Visibility = Visibility.Collapsed };
    readonly TextBlock _headerTitle = new() { FontSize = 15, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center };
    readonly TextBlock _accountName = new() { FontSize = 12, Margin = new Thickness(20, 0, 20, 16) };
    readonly Dictionary<string, RadioButton> _navButtons = new();
    readonly Dictionary<string, Stack<Page>> _stacks = new();
    string _tab = "home";
    System.Windows.Threading.DispatcherTimer? _toastTimer;

    public MainWindow()
    {
        Current = this;
        Title = L("app_name");
        Background = Kit.Brush("Bg");
        MinWidth = 900;
        MinHeight = 600;
        Width = Store.Settings.WindowWidth > 0 ? Store.Settings.WindowWidth : 1320;
        Height = Store.Settings.WindowHeight > 0 ? Store.Settings.WindowHeight : 820;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        if (Store.Settings.WindowMaximized) WindowState = WindowState.Maximized;
        Icon = BitmapFrame.Create(new Uri("pack://application:,,,/Assets/shasha.ico"));
        App.StyleWindow(this);

        _root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(232) });
        _root.ColumnDefinitions.Add(new ColumnDefinition());

        // Navigation rail
        var railGrid = new Grid();
        railGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        railGrid.RowDefinitions.Add(new RowDefinition());
        railGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var logo = new Image { Source = new BitmapImage(new Uri("pack://application:,,,/Assets/logo.png")), Width = 34, Height = 34 };
        var brand = Kit.Row(12, logo, Kit.Text(L("app_name"), size: 20));
        ((TextBlock)brand.Children[1]).FontWeight = FontWeights.Bold;
        ((TextBlock)brand.Children[1]).VerticalAlignment = VerticalAlignment.Center;
        brand.Margin = new Thickness(20, 22, 20, 22);
        railGrid.Children.Add(brand);
        Grid.SetRow(_rail, 1);
        railGrid.Children.Add(_rail);
        _accountName.Foreground = Kit.Brush("Subtle");
        Grid.SetRow(_accountName, 2);
        railGrid.Children.Add(_accountName);
        AddNav("home", "\uE80F", L("tab_home"));
        AddNav("live", "\uE7F4", L("tab_live"));
        AddNav("movies", "\uE8B2", L("tab_movies"));
        AddNav("series", "\uE8F1", L("tab_series"));
        AddNav("favourites", "\uE734", L("tab_favourites"));
        AddNav("downloads", "\uE896", L("tab_downloads"));
        AddNav("settings", "\uE713", L("tab_settings"));
        _railHost = new Border { Background = Kit.Brush("Panel"), Child = railGrid };
        _root.Children.Add(_railHost);

        // Content: banners, a back bar, then the page
        var content = new Grid();
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition());
        content.Children.Add(_banners);
        var back = Kit.IconButton("\uE72B", L("back"), Back);
        back.RenderTransform = L10n.IsArabic ? new ScaleTransform(-1, 1, 20, 20) : null;
        _header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _header.ColumnDefinitions.Add(new ColumnDefinition());
        _header.Children.Add(back);
        _headerTitle.Margin = new Thickness(10, 0, 0, 0);
        Grid.SetColumn(_headerTitle, 1);
        _header.Children.Add(_headerTitle);
        Grid.SetRow(_header, 1);
        content.Children.Add(_header);
        Grid.SetRow(_pageHost, 2);
        content.Children.Add(_pageHost);
        Grid.SetColumn(content, 1);
        _root.Children.Add(content);

        // Toast
        _toast = new Border
        {
            Background = Kit.Brush("CardHigh"),
            CornerRadius = new CornerRadius(12),
            Padding = new Thickness(18, 12, 18, 12),
            Child = _toastText,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(0, 0, 0, 28),
            MaxWidth = 560,
            Opacity = 0,
            IsHitTestVisible = false,
            Effect = new System.Windows.Media.Effects.DropShadowEffect { BlurRadius = 24, Opacity = 0.5, ShadowDepth = 4 },
        };
        Grid.SetColumnSpan(_toast, 2);
        _root.Children.Add(_toast);

        Content = _root;

        Toast.Shown += ShowToast;
        Session.AccountChanged += OnAccountChanged;
        Session.RefreshChanged += UpdateBanners;
        Downloads.Changed += UpdateBanners;
        SplitScreen.Changed += UpdateBanners;

        PreviewKeyDown += OnKey;
        MouseUp += (_, e) => { if (e.ChangedButton == MouseButton.XButton1) { Back(); e.Handled = true; } };
        Closing += (_, _) =>
        {
            Store.Settings.WindowMaximized = WindowState == WindowState.Maximized;
            if (WindowState == WindowState.Normal)
            {
                Store.Settings.WindowWidth = Width;
                Store.Settings.WindowHeight = Height;
            }
            Store.SaveSettings();
            foreach (var w in Application.Current.Windows.OfType<Window>().Where(w => w != this).ToList()) w.Close();
        };

        OnAccountChanged();
        Loaded += (_, _) => _ = CheckForUpdateQuietly();
    }

    void AddNav(string key, string glyph, string label)
    {
        var b = new RadioButton
        {
            Style = Kit.Style("NavButton"),
            GroupName = "nav",
            Content = Kit.Row(14, Kit.Glyph(glyph, 16), new TextBlock { Text = label, FontSize = 14, VerticalAlignment = VerticalAlignment.Center }),
        };
        b.Checked += (_, _) => SwitchTab(key);
        _navButtons[key] = b;
        _rail.Children.Add(b);
    }

    // MARK: Account

    string? _accountId = "-";

    void OnAccountChanged()
    {
        var signedIn = Session.Account != null;
        _accountName.Text = Session.Account?.Name ?? "";
        // Same account (e.g. its details refreshed): keep the pages as they are.
        if (Session.Account?.Id == _accountId) { UpdateBanners(); return; }
        _accountId = Session.Account?.Id;
        foreach (var stack in _stacks.Values) foreach (var p in stack) p.Closed();
        _stacks.Clear();
        if (!signedIn)
        {
            _railHost.Visibility = Visibility.Collapsed;
            Grid.SetColumn((UIElement)_pageHost.Parent, 0);
            Grid.SetColumnSpan((UIElement)_pageHost.Parent, 2);
            _header.Visibility = Visibility.Collapsed;
            _pageHost.Content = new LoginPage(null);
        }
        else
        {
            _railHost.Visibility = Visibility.Visible;
            Grid.SetColumn((UIElement)_pageHost.Parent, 1);
            Grid.SetColumnSpan((UIElement)_pageHost.Parent, 1);
            _tab = "";
            if (_navButtons["home"].IsChecked == true) SwitchTab("home");
            else _navButtons["home"].IsChecked = true;
        }
        UpdateBanners();
    }

    // MARK: Navigation

    Page MakeRoot(string tab) => tab switch
    {
        "live" => new BrowserPage(ContentType.Live),
        "movies" => new BrowserPage(ContentType.Movie),
        "series" => new BrowserPage(ContentType.Series),
        "favourites" => new FavouritesPage(),
        "downloads" => new DownloadsPage(),
        "settings" => new SettingsPage(),
        _ => new HomePage(),
    };

    public void SwitchTab(string tab)
    {
        if (Session.Account == null) return;
        if (_tab == tab && _stacks.TryGetValue(tab, out var same) && same.Count > 1)
        {
            // Clicking the current tab again goes back to its start.
            while (same.Count > 1) same.Pop().Closed();
            ShowTop();
            return;
        }
        _tab = tab;
        if (_navButtons.TryGetValue(tab, out var b) && b.IsChecked != true) b.IsChecked = true;
        if (!_stacks.TryGetValue(tab, out var stack))
        {
            stack = new Stack<Page>();
            stack.Push(MakeRoot(tab));
            _stacks[tab] = stack;
        }
        ShowTop();
    }

    public static void Push(Page page)
    {
        var w = Current;
        if (w == null || !w._stacks.TryGetValue(w._tab, out var stack)) return;
        stack.Push(page);
        w.ShowTop();
    }

    public void Back()
    {
        if (!_stacks.TryGetValue(_tab, out var stack) || stack.Count <= 1) return;
        stack.Pop().Closed();
        ShowTop();
    }

    void ShowTop()
    {
        if (!_stacks.TryGetValue(_tab, out var stack)) return;
        var page = stack.Peek();
        _pageHost.Content = page;
        _header.Visibility = stack.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        _headerTitle.Text = page.Title;
        page.Shown();
        var fade = new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(140));
        page.BeginAnimation(OpacityProperty, fade);
    }

    void OnKey(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Back && Keyboard.FocusedElement is not TextBox && Keyboard.FocusedElement is not PasswordBox) { Back(); e.Handled = true; }
        else if (e.Key == Key.Left && Keyboard.Modifiers == ModifierKeys.Alt) { Back(); e.Handled = true; }
        else if (e.Key == Key.F && Keyboard.Modifiers == ModifierKeys.Control)
        {
            if (_pageHost.Content is Page { SearchBox: { } box }) { box.Focus(); box.SelectAll(); e.Handled = true; }
        }
    }

    // MARK: Banners

    void UpdateBanners()
    {
        _banners.Children.Clear();
        if (Session.Account == null) return;
        if (!Downloads.IsOnline)
            _banners.Children.Add(Banner("\uF384", L("offline_title"), L("offline_body"), "Warning"));
        if (Session.Refresh is { } r)
        {
            string text;
            if (!r.Done) text = L("refresh_progress", L(r.Type.NounKey()), r.Step, r.Total);
            else text = r.Error == null ? L("refresh_done") : L("refresh_failed", r.Error);
            var banner = Banner(r.Done ? (r.Error == null ? "\uE73E" : "\uE7BA") : "\uE72C", text, null, r.Error == null ? "Accent" : "Warning");
            if (!r.Done)
            {
                var bytes = Session.Bytes.GetValueOrDefault(r.Type);
                if (bytes > 0) ((StackPanel)((Grid)banner.Child).Children[1]).Children.Add(Kit.Text(Text.FormatBytes(bytes), "Caption"));
            }
            _banners.Children.Add(banner);
        }
        if (SplitScreen.Picks.Count > 0)
        {
            var b = Banner("\uE8A9", L("split_banner", SplitScreen.Picks.Count), null, "Accent");
            var actions = Kit.Row(8,
                Kit.Button(L("watch"), SplitScreen.Watch, "\uE768", "AccentButton"),
                Kit.Button(L("clear"), SplitScreen.Clear));
            ((FrameworkElement)actions.Children[0]).Height = 36;
            Grid.SetColumn(actions, 2);
            ((Grid)b.Child).Children.Add(actions);
            _banners.Children.Add(b);
        }
        if (Session.DaysLeft is int days && days <= 7 && Session.ExpiryDate is DateTime exp)
        {
            var date = exp.ToString("d MMM yyyy", L10n.Culture);
            var text = days < 0 ? L("expired", date) : days == 0 ? L("expires_today", date) : L10n.Plural("expires_in", days, days, date);
            _banners.Children.Add(Banner("\uE7BA", text, null, "Warning"));
        }
    }

    static Border Banner(string glyph, string title, string? body, string colorKey)
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(Kit.Glyph(glyph, 18, Kit.Brush(colorKey)));
        var text = Kit.Column(2, Kit.Text(title, size: 14, wrap: true));
        if (body != null) text.Children.Add(Kit.Text(body, "Caption", wrap: true));
        text.Margin = new Thickness(14, 0, 14, 0);
        text.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        return new Border
        {
            Background = Kit.Brush("Card"),
            CornerRadius = new CornerRadius(12),
            Padding = new Thickness(16, 10, 16, 10),
            Margin = new Thickness(24, 14, 24, 0),
            Child = grid,
        };
    }

    // MARK: Toast

    void ShowToast(string text)
    {
        _toastText.Text = text;
        _toast.BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(150)));
        _toastTimer?.Stop();
        _toastTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(2.6) };
        _toastTimer.Tick += (_, _) =>
        {
            _toastTimer?.Stop();
            _toast.BeginAnimation(OpacityProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(300)));
        };
        _toastTimer.Start();
    }

    // MARK: Updates

    async Task CheckForUpdateQuietly()
    {
        await Task.Delay(4000);
        try
        {
            var release = await Updater.Check();
            if (release != null) SettingsPage.OfferUpdate(release);
        }
        catch { }
    }
}
