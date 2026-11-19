using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using LibVLCSharp.Shared;
using LibVLCSharp.WPF;
using Shasha.Core;
using Shasha.Player;
using static Shasha.Core.Loc;
using MediaPlayer = LibVLCSharp.Shared.MediaPlayer;

namespace Shasha.UI;

/// <summary>The player: VLC video with controls on top, keyboard shortcuts, resume, up next and more.</summary>
public sealed class PlayerWindow : Window
{
    static PlayerWindow? _instance;
    static LibVLC? _vlc;
    public static LibVLC Vlc => _vlc ??= new LibVLC("--no-osd", "--no-video-title-show", "--avcodec-hw=any");

    readonly VideoView _view = new();
    MediaPlayer _player;
    ClarityAudio? _clarity;

    List<PlayItem> _items = new();
    int _index;
    int _trackedIndex = -1;
    double _resumeTarget;
    DateTime _resumeSetAt;
    double _lastPosition, _lastDuration;
    DateTime _lastSaved = DateTime.MinValue;
    bool _hadPlayback;
    bool _closing;
    bool _cancelledUpNext;
    bool _seekingByUser;
    bool _updatingSlider;
    int _sleepIndex;
    DateTime? _sleepEnd;
    static readonly int[] SleepOptions = { 0, 15, 30, 60, 90 };
    string _aspect = "fit";

    // Controls
    readonly Grid _overlay = new() { Background = new SolidColorBrush(Color.FromArgb(1, 0, 0, 0)), Focusable = true };
    readonly Border _top, _bottom;
    readonly TextBlock _title = new() { FontSize = 17, FontWeight = FontWeights.SemiBold, Foreground = Brushes.White };
    readonly TextBlock _subtitle = new() { FontSize = 13, Foreground = new SolidColorBrush(Color.FromRgb(0xCB, 0xD5, 0xE1)) };
    readonly Slider _slider = new() { Minimum = 0, Maximum = 1, IsMoveToPointEnabled = true, Margin = new Thickness(12, 0, 12, 0) };
    readonly TextBlock _time = new() { Foreground = Brushes.White, FontSize = 13, VerticalAlignment = VerticalAlignment.Center };
    readonly TextBlock _remaining = new() { Foreground = Brushes.White, FontSize = 13, VerticalAlignment = VerticalAlignment.Center };
    readonly Button _playPause, _prev, _next, _back10, _fwd10, _mute, _clarityButton, _fullscreen, _mini, _sleep;
    readonly Slider _volume = new() { Minimum = 0, Maximum = 100, Width = 110, VerticalAlignment = VerticalAlignment.Center };
    readonly Grid _timeRow = new();
    readonly ProgressBar _spinner = new() { IsIndeterminate = true, Width = 160, Height = 4, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    readonly StackPanel _errorPanel = new() { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Visibility = Visibility.Collapsed };
    readonly TextBlock _errorText = new() { Foreground = Brushes.White, FontSize = 16, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, MaxWidth = 520 };
    readonly Border _epg;
    readonly TextBlock _epgNow = new() { Foreground = Brushes.White, FontSize = 14 };
    readonly TextBlock _epgNext = new() { Foreground = new SolidColorBrush(Color.FromRgb(0xCB, 0xD5, 0xE1)), FontSize = 13 };
    readonly Border _upNext;
    readonly TextBlock _upNextText = new() { Foreground = Brushes.White, FontSize = 14, FontWeight = FontWeights.SemiBold };
    readonly TextBlock _upNextTitle = new() { Foreground = new SolidColorBrush(Color.FromRgb(0xCB, 0xD5, 0xE1)), FontSize = 13, MaxWidth = 280 };
    readonly Border _skipBubble;
    readonly TextBlock _skipText = new() { Foreground = Brushes.White, FontSize = 15, FontWeight = FontWeights.SemiBold };
    readonly Border _toast;
    readonly TextBlock _toastText = new() { Foreground = Brushes.White, FontSize = 14, TextWrapping = TextWrapping.Wrap };
    readonly DispatcherTimer _toastTimer = new() { Interval = TimeSpan.FromSeconds(2.2) };

    readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromMilliseconds(400) };
    readonly DispatcherTimer _hide = new() { Interval = TimeSpan.FromSeconds(3) };
    readonly DispatcherTimer _clickTimer = new() { Interval = TimeSpan.FromMilliseconds(260) };
    CancellationTokenSource? _epgCts;

    // Window state before full screen / mini player
    WindowState _savedState;
    Rect _savedBounds;
    bool _fullScreen, _miniMode;

    PlayItem? Current => _items.Count > 0 && _index >= 0 && _index < _items.Count ? _items[_index] : null;

    public static void Open(List<PlayItem> items, int index, bool fromStart)
    {
        if (_instance == null)
        {
            _instance = new PlayerWindow();
            _instance.Show();
        }
        _instance.Activate();
        if (_instance.WindowState == WindowState.Minimized) _instance.WindowState = WindowState.Normal;
        _instance.Start(items, index, fromStart);
    }

    /// <summary>The playing item, for split screen.</summary>
    public static PlayItem? Playing => _instance?.Current;

    PlayerWindow()
    {
        Title = L("app_name");
        Background = Brushes.Black;
        Width = 1280;
        Height = 760;
        MinWidth = 360;
        MinHeight = 220;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Icon = System.Windows.Media.Imaging.BitmapFrame.Create(new Uri("pack://application:,,,/Assets/shasha.ico"));
        App.StyleWindow(this);
        FlowDirection = FlowDirection.LeftToRight; // video controls read left to right everywhere

        _player = NewPlayer(withClarity: false);

        // Top bar
        var close = PlayerButton("\uE72B", L("back"), Close);
        close.RenderTransform = null;
        _clarityButton = TextButton(L("clarity_0"), CycleClarity);
        _clarityButton.ToolTip = L("clarity_1");
        var tracks = PlayerButton("\uE7F0", L("tracks"), () => { });
        tracks.Click += (_, _) => ShowTracks(tracks);
        var aspect = PlayerButton("\uE740", L("aspect"), () => { });
        aspect.Click += (_, _) => ShowAspect(aspect);
        aspect.Content = "\uE799";
        _sleep = PlayerButton("\uE708", L("sleep"), CycleSleep);
        var split = PlayerButton("\uE8A9", L("split_add"), () => SplitScreen.Add(Current));
        _mini = PlayerButton("\uE944", L("mini_player"), ToggleMini);
        _fullscreen = PlayerButton("\uE740", L("full_screen"), ToggleFullScreen);
        var topGrid = new Grid { Margin = new Thickness(12, 10, 12, 30) };
        topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        topGrid.ColumnDefinitions.Add(new ColumnDefinition());
        topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        topGrid.Children.Add(close);
        var titles = new StackPanel { Margin = new Thickness(10, 0, 10, 0), VerticalAlignment = VerticalAlignment.Center };
        titles.Children.Add(_title);
        titles.Children.Add(_subtitle);
        _title.FlowDirection = L10n.Direction;
        _subtitle.FlowDirection = L10n.Direction;
        Grid.SetColumn(titles, 1);
        topGrid.Children.Add(titles);
        var tools = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        foreach (var b in new UIElement[] { _clarityButton, tracks, aspect, _sleep, split, _mini, _fullscreen }) tools.Children.Add(b);
        Grid.SetColumn(tools, 2);
        topGrid.Children.Add(tools);
        _top = new Border { Child = topGrid, VerticalAlignment = VerticalAlignment.Top, Background = Fade(true) };

        // Bottom bar
        _prev = PlayerButton("\uE892", L("previous"), () => Step(-1));
        _back10 = PlayerButton("\uED3C", L("skip_back"), () => Skip(-10));
        _playPause = PlayerButton("\uE769", L("pause"), TogglePlay, 22);
        _playPause.Width = 54;
        _playPause.Height = 54;
        _fwd10 = PlayerButton("\uED3D", L("skip_forward"), () => Skip(10));
        _next = PlayerButton("\uE893", L("next"), () => Step(1));
        _mute = PlayerButton("\uE767", L("mute"), ToggleMute);
        _volume.Value = Store.Settings.Volume;
        _volume.ValueChanged += (_, e) => SetVolume((int)e.NewValue, save: true);
        _timeRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _timeRow.ColumnDefinitions.Add(new ColumnDefinition());
        _timeRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _timeRow.Children.Add(_time);
        Grid.SetColumn(_slider, 1);
        _timeRow.Children.Add(_slider);
        Grid.SetColumn(_remaining, 2);
        _timeRow.Children.Add(_remaining);
        _slider.AddHandler(Thumb.DragStartedEvent, new DragStartedEventHandler((_, _) => _seekingByUser = true));
        _slider.AddHandler(Thumb.DragCompletedEvent, new DragCompletedEventHandler((_, _) => { _seekingByUser = false; SeekTo(_slider.Value); }));
        _slider.ValueChanged += (_, e) =>
        {
            if (_updatingSlider) return;
            if (_seekingByUser) { _time.Text = Text.FormatTime(e.NewValue); return; }
            SeekTo(e.NewValue);
        };
        var buttons = new Grid { Margin = new Thickness(0, 6, 0, 0) };
        buttons.ColumnDefinitions.Add(new ColumnDefinition());
        buttons.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        buttons.ColumnDefinitions.Add(new ColumnDefinition());
        var middle = new StackPanel { Orientation = Orientation.Horizontal };
        foreach (var b in new UIElement[] { _prev, _back10, _playPause, _fwd10, _next }) middle.Children.Add(b);
        Grid.SetColumn(middle, 1);
        buttons.Children.Add(middle);
        var right = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        right.Children.Add(_mute);
        right.Children.Add(_volume);
        Grid.SetColumn(right, 2);
        buttons.Children.Add(right);
        var bottomStack = new StackPanel { Margin = new Thickness(18, 40, 18, 14) };
        bottomStack.Children.Add(_timeRow);
        bottomStack.Children.Add(buttons);
        _bottom = new Border { Child = bottomStack, VerticalAlignment = VerticalAlignment.Bottom, Background = Fade(false) };

        // TV guide (live channels)
        var epgStack = new StackPanel();
        epgStack.Children.Add(_epgNow);
        epgStack.Children.Add(_epgNext);
        _epgNow.FlowDirection = L10n.Direction;
        _epgNext.FlowDirection = L10n.Direction;
        _epg = new Border
        {
            Child = epgStack, Background = new SolidColorBrush(Color.FromArgb(0xB0, 0x0B, 0x13, 0x20)), CornerRadius = new CornerRadius(10),
            Padding = new Thickness(14, 10, 14, 10), Margin = new Thickness(20, 0, 20, 150), HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Bottom, Visibility = Visibility.Collapsed, MaxWidth = 640,
        };

        // Up next
        var playNow = TextButton(L("play_now"), () => Step(1));
        playNow.Background = Kit.Brush("Accent");
        playNow.Foreground = Kit.Brush("Bg");
        var cancelUpNext = TextButton(L("cancel"), () => { _cancelledUpNext = true; _upNext!.Visibility = Visibility.Collapsed; });
        var upNextButtons = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0) };
        upNextButtons.Children.Add(playNow);
        upNextButtons.Children.Add(cancelUpNext);
        var upNextStack = new StackPanel();
        upNextStack.Children.Add(_upNextText);
        upNextStack.Children.Add(_upNextTitle);
        upNextStack.Children.Add(upNextButtons);
        _upNext = new Border
        {
            Child = upNextStack, Background = new SolidColorBrush(Color.FromArgb(0xE0, 0x15, 0x22, 0x36)), CornerRadius = new CornerRadius(14),
            Padding = new Thickness(16), Margin = new Thickness(20, 0, 24, 150), HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Bottom, Visibility = Visibility.Collapsed,
        };

        // Skip feedback bubble
        _skipBubble = new Border
        {
            Child = _skipText, Background = new SolidColorBrush(Color.FromArgb(0x99, 0, 0, 0)), CornerRadius = new CornerRadius(30),
            Padding = new Thickness(20, 12, 20, 12), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            Opacity = 0, IsHitTestVisible = false,
        };

        // Error
        var retry = TextButton(L("try_again"), Retry);
        retry.HorizontalAlignment = HorizontalAlignment.Center;
        retry.Margin = new Thickness(0, 14, 0, 0);
        _errorPanel.Children.Add(new TextBlock { Text = "\uE7BA", FontFamily = Kit.Res<FontFamily>("Icons"), FontSize = 36, Foreground = Kit.Brush("Warning"), HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 0, 0, 12) });
        _errorPanel.Children.Add(_errorText);
        _errorPanel.Children.Add(retry);

        // Messages (volume, sound setting...) show over the video while this window is in front.
        _toastText.FlowDirection = L10n.Direction;
        _toast = new Border
        {
            Child = _toastText, Background = new SolidColorBrush(Color.FromArgb(0xD0, 0x1E, 0x2E, 0x46)), CornerRadius = new CornerRadius(12),
            Padding = new Thickness(16, 10, 16, 10), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 90, 0, 0), MaxWidth = 520, Visibility = Visibility.Collapsed, IsHitTestVisible = false,
        };
        _toastTimer.Tick += (_, _) => { _toastTimer.Stop(); _toast.Visibility = Visibility.Collapsed; };
        Toast.Shown += OnToast;

        foreach (var e in new UIElement[] { _spinner, _errorPanel, _skipBubble, _epg, _upNext, _top, _bottom, _toast }) _overlay.Children.Add(e);
        _view.Content = _overlay;
        Content = _view;

        _overlay.MouseMove += (_, _) => ShowControls();
        _overlay.MouseLeftButtonDown += OnOverlayClick;
        _overlay.MouseWheel += (_, e) => SetVolume((int)_volume.Value + (e.Delta > 0 ? 5 : -5), save: true, show: true);
        _overlay.KeyDown += OnKey;
        PreviewKeyDown += OnKey;
        _clickTimer.Tick += (_, _) => { _clickTimer.Stop(); TogglePlay(); };
        _tick.Tick += (_, _) => OnTick();
        _hide.Tick += (_, _) => HideControls();
        Closing += (_, _) => Shutdown();
        Closed += (_, _) => _instance = null;
        _tick.Start();
    }

    void OnToast(string text)
    {
        if (!IsActive) return;
        _toastText.Text = text;
        _toast.Visibility = Visibility.Visible;
        _toastTimer.Stop();
        _toastTimer.Start();
    }

    // MARK: Building blocks

    static Brush Fade(bool top)
    {
        var b = new LinearGradientBrush { StartPoint = new Point(0, top ? 0 : 1), EndPoint = new Point(0, top ? 1 : 0) };
        b.GradientStops.Add(new GradientStop(Color.FromArgb(0xC0, 0, 0, 0), 0));
        b.GradientStops.Add(new GradientStop(Color.FromArgb(0, 0, 0, 0), 1));
        return b;
    }

    static Button PlayerButton(string glyph, string tooltip, Action onClick, double size = 18)
    {
        var b = new Button { Style = Kit.Style("IconButton"), Content = glyph, ToolTip = tooltip, FontSize = size, Foreground = Brushes.White, Width = 44, Height = 44 };
        System.Windows.Automation.AutomationProperties.SetName(b, tooltip);
        b.Click += (_, _) => onClick();
        b.Focusable = false;
        return b;
    }

    static Button TextButton(string text, Action onClick)
    {
        var b = new Button { Content = text, Height = 34, Margin = new Thickness(0, 0, 8, 0), Background = new SolidColorBrush(Color.FromArgb(0x40, 0xFF, 0xFF, 0xFF)), Foreground = Brushes.White, FontSize = 13 };
        b.Click += (_, _) => onClick();
        b.Focusable = false;
        return b;
    }

    MediaPlayer NewPlayer(bool withClarity)
    {
        var p = new MediaPlayer(Vlc) { EnableHardwareDecoding = true, EnableKeyInput = false, EnableMouseInput = false };
        p.Playing += (_, _) => Ui.Post(OnPlaying);
        p.Paused += (_, _) => Ui.Post(UpdatePlayButton);
        p.EndReached += (_, _) => Ui.Post(OnEnded);
        p.EncounteredError += (_, _) => Ui.Post(() => ShowError(_hadPlayback ? L("err_stream_network") : L("err_stream_generic", "VLC")));
        p.Buffering += (_, e) => Ui.Post(() => _spinner.Visibility = e.Cache < 100 && !_hadPlayback ? Visibility.Visible : Visibility.Collapsed);
        if (withClarity) _clarity = new ClarityAudio(p) { Level = 0 };
        p.Volume = Store.Settings.Volume;
        return p;
    }

    // MARK: Starting playback

    void Start(List<PlayItem> items, int index, bool fromStart)
    {
        SaveCurrent();
        _items = items;
        Load(index, fromStart ? 0 : null);
    }

    string ClarityKey(PlayItem item) => item.Kind switch
    {
        WatchKind.Episode => $"series:{item.SeriesId ?? item.StreamId}",
        WatchKind.Movie => $"movie:{item.StreamId}",
        _ => $"live:{item.StreamId}",
    };

    double SavedPosition(PlayItem item)
    {
        if (item.IsLive) return 0;
        var e = Session.HistoryEntry(item.Kind, item.StreamId);
        return e != null && !e.Finished && e.Position > 10 ? e.Position : 0;
    }

    void Load(int index, double? start = null)
    {
        if (index < 0 || index >= _items.Count) return;
        _index = index;
        var item = _items[index];
        _trackedIndex = index;
        _lastPosition = 0;
        _lastDuration = 0;
        _hadPlayback = false;
        _cancelledUpNext = false;
        _upNext.Visibility = Visibility.Collapsed;
        _errorPanel.Visibility = Visibility.Collapsed;
        _spinner.Visibility = Visibility.Visible;
        _title.Text = item.Title;
        _subtitle.Text = item.Subtitle ?? "";
        _subtitle.Visibility = string.IsNullOrEmpty(item.Subtitle) ? Visibility.Collapsed : Visibility.Visible;
        Title = item.Title;
        _resumeTarget = start ?? SavedPosition(item);
        _resumeSetAt = DateTime.UtcNow;

        var live = item.IsLive;
        _slider.Visibility = live ? Visibility.Hidden : Visibility.Visible;
        _time.Visibility = live ? Visibility.Hidden : Visibility.Visible;
        _remaining.Visibility = live ? Visibility.Hidden : Visibility.Visible;
        _back10.Visibility = live ? Visibility.Collapsed : Visibility.Visible;
        _fwd10.Visibility = live ? Visibility.Collapsed : Visibility.Visible;
        _prev.Visibility = _items.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        _next.Visibility = _items.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        _prev.ToolTip = live ? L("previous") : L("previous");
        _next.ToolTip = live ? L("next") : L("next_episode");

        // "Clearer sound" needs our own sound path; VLC only takes it on a new player.
        var level = Store.Clarity(ClarityKey(item));
        if (level > 0 && _clarity == null) SwapPlayer(withClarity: true);
        if (_clarity != null) _clarity.Level = level;
        _clarityButton.Content = L($"clarity_{level}");

        var media = item.IsRemote
            ? new Media(Vlc, new Uri(item.Url))
            : new Media(Vlc, item.Url, FromType.FromPath);
        media.AddOption($":http-user-agent={Http.UserAgent}");
        media.AddOption(":network-caching=1500");
        var player = _player;
        WhenVideoReady(_view, player, () =>
        {
            _ = Task.Run(() =>
            {
                player.Play(media);
                media.Dispose();
            });
        });
        Downloads.SetStreaming(item.IsRemote);
        LoadEpg(item);
        ShowControls();
        UpdatePlayButton();
    }

    /// <summary>The window VLC draws into, once the video view has made it.</summary>
    public static IntPtr VideoHandle(VideoView view)
    {
        view.ApplyTemplate();
        return (view.Template?.FindName("PART_PlayerHost", view) as System.Windows.Interop.HwndHost)?.Handle ?? IntPtr.Zero;
    }

    /// <summary>
    /// VLC must have the video surface before it starts, or it opens a window of its own.
    /// Waits (up to a few seconds) for the surface, then hands it to VLC and runs the action.
    /// </summary>
    public static void WhenVideoReady(VideoView view, MediaPlayer player, Action action)
    {
        var tries = 0;
        async void Check()
        {
            var handle = VideoHandle(view);
            if (handle == IntPtr.Zero && tries++ < 100)
            {
                await Task.Delay(50);
                Check();
                return;
            }
            if (view.MediaPlayer != player) view.MediaPlayer = player;
            if (handle != IntPtr.Zero) player.Hwnd = handle;
            action();
        }
        Check();
    }

    void SwapPlayer(bool withClarity)
    {
        var old = _player;
        var oldClarity = _clarity;
        _clarity = null;
        _player = NewPlayer(withClarity);
        _view.MediaPlayer = _player;
        _ = Task.Run(() =>
        {
            try { old.Stop(); } catch { }
            old.Dispose();
            oldClarity?.Dispose();
        });
    }

    void OnPlaying()
    {
        _spinner.Visibility = Visibility.Collapsed;
        _errorPanel.Visibility = Visibility.Collapsed;
        var item = Current;
        if (!_hadPlayback && item != null)
        {
            _hadPlayback = true;
            if (item.IsLive)
                Session.Record(new WatchEntry { Kind = WatchKind.Live, StreamId = item.StreamId, Title = item.Title, Image = item.Image }, item.AccountId);
            // Resume by seeking once playing (more reliable than a start-time option).
            if (_resumeTarget > 0 && _player.IsSeekable) _player.Time = (long)(_resumeTarget * 1000);
            ApplyAspect();
        }
        UpdatePlayButton();
        ScheduleHide();
    }

    void OnEnded()
    {
        var item = Current;
        if (item == null) return;
        if (!item.IsLive)
        {
            MarkFinished(_index, _lastDuration);
            if (_index + 1 < _items.Count && !_cancelledUpNext)
            {
                Load(_index + 1);
                return;
            }
        }
        _spinner.Visibility = Visibility.Collapsed;
        UpdatePlayButton();
        ShowControls(autoHide: false);
    }

    void ShowError(string text)
    {
        _spinner.Visibility = Visibility.Collapsed;
        _errorText.Text = text;
        _errorPanel.Visibility = Visibility.Visible;
        ShowControls(autoHide: false);
    }

    void Retry()
    {
        var at = Current?.IsLive == true ? 0 : Math.Max(_lastPosition, _resumeTarget);
        Load(_index, at);
    }

    // MARK: Tick: time bar, history, up next, sleep timer

    void OnTick()
    {
        var item = Current;
        if (item == null || _closing) return;
        var length = _player.Length / 1000.0;
        var pos = _player.Time / 1000.0;
        if (_resumeTarget > 0)
        {
            // Wait until the player has really jumped to the saved spot (or give up after 20 s).
            if (pos >= _resumeTarget - 15 || (DateTime.UtcNow - _resumeSetAt).TotalSeconds > 20) _resumeTarget = 0;
            else pos = _resumeTarget;
        }
        if (!item.IsLive)
        {
            if (!_seekingByUser)
            {
                _updatingSlider = true;
                _slider.Maximum = Math.Max(length, 1);
                _slider.Value = Math.Clamp(pos, 0, _slider.Maximum);
                _updatingSlider = false;
                _time.Text = Text.FormatTime(pos);
            }
            _remaining.Text = length > 0 ? "-" + Text.FormatTime(Math.Max(length - pos, 0)) : "";
            if (_resumeTarget == 0 && _hadPlayback)
            {
                if (pos > 0) _lastPosition = pos;
                if (length > 0) _lastDuration = length;
            }
            if ((DateTime.UtcNow - _lastSaved).TotalSeconds > 15)
            {
                _lastSaved = DateTime.UtcNow;
                SaveCurrent();
            }
            UpdateUpNext(pos, length);
        }
        if (_sleepEnd is DateTime end && DateTime.Now >= end)
        {
            _sleepEnd = null;
            _sleepIndex = 0;
            _player.SetPause(true);
            Toast.Show(L("sleep_done"));
            ShowControls(autoHide: false);
        }
    }

    void UpdateUpNext(double pos, double length)
    {
        var item = Current;
        var hasNext = item?.Kind == WatchKind.Episode && _index + 1 < _items.Count;
        var left = length - pos;
        if (!hasNext || _cancelledUpNext || length <= 0 || left > 20 || left < 0)
        {
            _upNext.Visibility = Visibility.Collapsed;
            return;
        }
        var next = _items[_index + 1];
        _upNextText.Text = L("next_episode_in", Text.FormatDuration(left));
        _upNextTitle.Text = string.Join(" · ", new[] { next.Title, next.Subtitle }.Where(s => !string.IsNullOrEmpty(s)));
        _upNext.Visibility = Visibility.Visible;
    }

    void SaveCurrent()
    {
        if (_trackedIndex < 0 || _trackedIndex >= _items.Count) return;
        var item = _items[_trackedIndex];
        if (item.IsLive || _lastPosition <= 0) return;
        var nearEnd = _lastDuration > 0 && (_lastPosition >= _lastDuration - 60 || _lastPosition >= _lastDuration * 0.95);
        if (nearEnd) MarkFinished(_trackedIndex, _lastDuration);
        else Record(item, _lastPosition, _lastDuration);
    }

    void MarkFinished(int i, double duration)
    {
        if (i < 0 || i >= _items.Count) return;
        var item = _items[i];
        if (item.IsLive) return;
        var known = duration > 0 ? duration : Session.HistoryEntry(item.Kind, item.StreamId)?.Duration ?? 0;
        Record(item, 0, known, finished: true);
        // Put the following episode in Continue watching as up next.
        if (item.Kind == WatchKind.Episode && i + 1 < _items.Count && _items[i + 1].SeriesId == item.SeriesId)
        {
            var next = _items[i + 1];
            var existing = Session.HistoryEntry(next.Kind, next.StreamId);
            if (existing == null || existing.Finished || existing.Position <= 10)
                Record(next, 0, 0, upNext: true, at: DateTime.UtcNow.AddSeconds(1));
        }
    }

    static void Record(PlayItem item, double position, double duration, bool finished = false, bool upNext = false, DateTime? at = null)
    {
        var episode = item.Kind == WatchKind.Episode;
        var code = string.Join(" · ", item.Title.Split(" · ").Skip(1));
        string? subtitle = episode
            ? (code.Length == 0 ? item.Title : code) + (item.Subtitle != null ? $" · {item.Subtitle}" : "")
            : item.Subtitle;
        Session.Record(new WatchEntry
        {
            Kind = item.Kind, StreamId = item.StreamId, Title = episode ? item.SeriesName ?? item.Title : item.Title,
            Subtitle = subtitle, Image = item.Image, Ext = item.Ext, SeriesId = item.SeriesId, SeriesName = item.SeriesName,
            Position = position, Duration = duration, Finished = finished, UpNext = upNext, UpdatedAt = at ?? DateTime.UtcNow,
        }, item.AccountId);
    }

    // MARK: TV guide

    async void LoadEpg(PlayItem item)
    {
        _epgCts?.Cancel();
        _epg.Visibility = Visibility.Collapsed;
        if (!item.IsLive || Session.Api == null || Session.Account?.Id != item.AccountId) return;
        var cts = _epgCts = new CancellationTokenSource();
        var list = await Session.Api.ShortEpg(item.StreamId, cts.Token);
        if (cts.IsCancellationRequested) return;
        var now = DateTime.Now;
        var current = list.FirstOrDefault(e => e.Start <= now && e.End > now);
        var next = list.FirstOrDefault(e => e.Start > now);
        if (current == null && next == null) return;
        string Time(DateTime d) => d.ToString("HH:mm", L10n.Culture);
        _epgNow.Text = current != null ? L("epg_now", current.Title, Time(current.Start), Time(current.End)) : "";
        _epgNow.Visibility = current != null ? Visibility.Visible : Visibility.Collapsed;
        _epgNext.Text = next != null ? L("epg_next", next.Title, Time(next.Start)) : "";
        _epgNext.Visibility = next != null ? Visibility.Visible : Visibility.Collapsed;
        _epg.Visibility = _top.Opacity > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    // MARK: Controls

    void TogglePlay()
    {
        if (Current == null) return;
        if (_player.IsPlaying) _player.SetPause(true);
        else if (_player.State == VLCState.Ended || _player.State == VLCState.Stopped || _player.State == VLCState.Error) Retry();
        else _player.SetPause(false);
        UpdatePlayButton();
        ShowControls();
    }

    void UpdatePlayButton()
    {
        var playing = _player.IsPlaying || _player.State == VLCState.Opening || _player.State == VLCState.Buffering;
        _playPause.Content = playing ? "\uE769" : "\uE768";
        _playPause.ToolTip = playing ? L("pause") : L("play");
    }

    void Skip(double seconds)
    {
        if (Current?.IsLive != false || !_player.IsSeekable) return;
        var target = Math.Max(0, _player.Time / 1000.0 + seconds);
        if (_player.Length > 0) target = Math.Min(target, _player.Length / 1000.0 - 1);
        _player.Time = (long)(target * 1000);
        _resumeTarget = 0;
        _skipText.Text = seconds > 0 ? $"+{seconds:0}" : $"{seconds:0}";
        _skipBubble.HorizontalAlignment = seconds > 0 ? HorizontalAlignment.Right : HorizontalAlignment.Left;
        _skipBubble.Margin = new Thickness(seconds > 0 ? 0 : 120, 0, seconds > 0 ? 120 : 0, 0);
        var fade = new System.Windows.Media.Animation.DoubleAnimationUsingKeyFrames();
        fade.KeyFrames.Add(new System.Windows.Media.Animation.LinearDoubleKeyFrame(1, TimeSpan.FromMilliseconds(80)));
        fade.KeyFrames.Add(new System.Windows.Media.Animation.LinearDoubleKeyFrame(1, TimeSpan.FromMilliseconds(500)));
        fade.KeyFrames.Add(new System.Windows.Media.Animation.LinearDoubleKeyFrame(0, TimeSpan.FromMilliseconds(800)));
        _skipBubble.BeginAnimation(OpacityProperty, fade);
        ShowControls();
    }

    void SeekTo(double seconds)
    {
        if (Current?.IsLive != false || !_player.IsSeekable) return;
        _player.Time = (long)(seconds * 1000);
        _resumeTarget = 0;
        ShowControls();
    }

    void Step(int delta)
    {
        if (_items.Count < 2) return;
        var i = _index + delta;
        if (Current?.IsLive == true) i = (i + _items.Count) % _items.Count;
        if (i < 0 || i >= _items.Count) return;
        SaveCurrent();
        Load(i);
    }

    void SetVolume(int value, bool save, bool show = false)
    {
        value = Math.Clamp(value, 0, 100);
        _player.Volume = value;
        if (_player.Mute && value > 0) _player.Mute = false;
        if ((int)_volume.Value != value) _volume.Value = value;
        _mute.Content = value == 0 || _player.Mute ? "\uE74F" : "\uE767";
        if (save) Store.Settings.Volume = value;
        if (show) Toast.Show(L("volume_n", value));
    }

    void ToggleMute()
    {
        _player.Mute = !_player.Mute;
        _mute.Content = _player.Mute ? "\uE74F" : "\uE767";
        _mute.ToolTip = _player.Mute ? L("unmute") : L("mute");
    }

    void CycleClarity()
    {
        var item = Current;
        if (item == null) return;
        var level = (Store.Clarity(ClarityKey(item)) + 1) % 3;
        Store.SetClarity(ClarityKey(item), level);
        _clarityButton.Content = L($"clarity_{level}");
        Toast.Show(L($"clarity_{level}"));
        if (_clarity != null)
        {
            _clarity.Level = level;
            return;
        }
        if (level > 0)
        {
            // Continue from the same spot through our own sound path.
            var at = item.IsLive ? 0 : _player.Time / 1000.0;
            SaveCurrent();
            Load(_index, at);
        }
    }

    void ShowTracks(Button anchor)
    {
        var menu = new ContextMenu();
        var audio = _player.AudioTrackDescription;
        if (audio.Length > 0)
        {
            menu.Items.Add(new MenuItem { Header = L("audio"), IsEnabled = false });
            foreach (var t in audio.Where(t => t.Id >= 0))
            {
                var id = t.Id;
                var mi = new MenuItem { Header = t.Name, Tag = _player.AudioTrack == id ? "\uE73E" : "" };
                mi.Click += (_, _) => _player.SetAudioTrack(id);
                menu.Items.Add(mi);
            }
        }
        var subs = _player.SpuDescription;
        menu.Items.Add(new Separator());
        menu.Items.Add(new MenuItem { Header = L("subtitles"), IsEnabled = false });
        var off = new MenuItem { Header = L("off"), Tag = _player.Spu < 0 ? "\uE73E" : "" };
        off.Click += (_, _) => _player.SetSpu(-1);
        menu.Items.Add(off);
        foreach (var t in subs.Where(t => t.Id >= 0))
        {
            var id = t.Id;
            var mi = new MenuItem { Header = t.Name, Tag = _player.Spu == id ? "\uE73E" : "" };
            mi.Click += (_, _) => _player.SetSpu(id);
            menu.Items.Add(mi);
        }
        menu.PlacementTarget = anchor;
        menu.Placement = PlacementMode.Bottom;
        menu.IsOpen = true;
        ShowControls(autoHide: false);
        menu.Closed += (_, _) => ScheduleHide();
    }

    void ShowAspect(Button anchor)
    {
        var menu = Kit.Menu(
            (_aspect == "fit" ? "\uE73E" : "", L("fit"), () => { _aspect = "fit"; ApplyAspect(); }),
            (_aspect == "fill" ? "\uE73E" : "", L("fill"), () => { _aspect = "fill"; ApplyAspect(); }),
            (_aspect == "stretch" ? "\uE73E" : "", L("stretch"), () => { _aspect = "stretch"; ApplyAspect(); }));
        menu.FlowDirection = FlowDirection.LeftToRight;
        menu.PlacementTarget = anchor;
        menu.Placement = PlacementMode.Bottom;
        menu.IsOpen = true;
    }

    void ApplyAspect()
    {
        var w = (int)Math.Max(1, _view.ActualWidth);
        var h = (int)Math.Max(1, _view.ActualHeight);
        switch (_aspect)
        {
            case "fill":
                _player.AspectRatio = null;
                _player.CropGeometry = $"{w}:{h}";
                break;
            case "stretch":
                _player.CropGeometry = null;
                _player.AspectRatio = $"{w}:{h}";
                break;
            default:
                _player.CropGeometry = null;
                _player.AspectRatio = null;
                break;
        }
    }

    void CycleSleep()
    {
        _sleepIndex = (_sleepIndex + 1) % SleepOptions.Length;
        var minutes = SleepOptions[_sleepIndex];
        if (minutes == 0)
        {
            _sleepEnd = null;
            _sleep.ToolTip = L("sleep");
            Toast.Show(L("sleep_off"));
        }
        else
        {
            _sleepEnd = DateTime.Now.AddMinutes(minutes);
            _sleep.ToolTip = L("sleep_n", minutes);
            Toast.Show(L("sleep_set", minutes));
        }
    }

    // MARK: Full screen and mini player

    void ToggleFullScreen()
    {
        if (_miniMode) ToggleMini();
        if (!_fullScreen)
        {
            _savedState = WindowState;
            _fullScreen = true;
            WindowState = WindowState.Normal;
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            WindowState = WindowState.Maximized;
            _fullscreen.Content = "\uE73F";
            _fullscreen.ToolTip = L("exit_full_screen");
        }
        else
        {
            _fullScreen = false;
            WindowStyle = WindowStyle.SingleBorderWindow;
            ResizeMode = ResizeMode.CanResize;
            WindowState = _savedState == WindowState.Maximized ? WindowState.Maximized : WindowState.Normal;
            _fullscreen.Content = "\uE740";
            _fullscreen.ToolTip = L("full_screen");
        }
        Dispatcher.InvokeAsync(ApplyAspect, DispatcherPriority.Background);
    }

    void ToggleMini()
    {
        if (_fullScreen) ToggleFullScreen();
        if (!_miniMode)
        {
            _miniMode = true;
            _savedState = WindowState;
            WindowState = WindowState.Normal;
            _savedBounds = new Rect(Left, Top, Width, Height);
            Topmost = true;
            Width = 480;
            Height = 270;
            var area = SystemParameters.WorkArea;
            Left = area.Right - Width - 24;
            Top = area.Bottom - Height - 24;
            _mini.Content = "\uE73F";
            _mini.ToolTip = L("back_to_player");
        }
        else
        {
            _miniMode = false;
            Topmost = false;
            Left = _savedBounds.Left;
            Top = _savedBounds.Top;
            Width = _savedBounds.Width;
            Height = _savedBounds.Height;
            WindowState = _savedState;
            _mini.Content = "\uE944";
            _mini.ToolTip = L("mini_player");
        }
        Dispatcher.InvokeAsync(ApplyAspect, DispatcherPriority.Background);
    }

    // MARK: Showing and hiding the controls

    void ShowControls(bool autoHide = true)
    {
        _top.Opacity = 1;
        _bottom.Opacity = 1;
        _top.IsHitTestVisible = true;
        _bottom.IsHitTestVisible = true;
        if (_epgNow.Text.Length > 0 || _epgNext.Text.Length > 0) _epg.Visibility = Current?.IsLive == true ? Visibility.Visible : Visibility.Collapsed;
        _overlay.Cursor = null;
        if (autoHide) ScheduleHide(); else _hide.Stop();
    }

    void ScheduleHide()
    {
        _hide.Stop();
        _hide.Start();
    }

    void HideControls()
    {
        _hide.Stop();
        if (!_player.IsPlaying || _bottom.IsMouseOver || _top.IsMouseOver) { if (_player.IsPlaying) ScheduleHide(); return; }
        _top.Opacity = 0;
        _bottom.Opacity = 0;
        _top.IsHitTestVisible = false;
        _bottom.IsHitTestVisible = false;
        _epg.Visibility = Visibility.Collapsed;
        _overlay.Cursor = Cursors.None;
    }

    void OnOverlayClick(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource != _overlay) return;
        if (e.ClickCount == 2)
        {
            _clickTimer.Stop();
            if (_miniMode) ToggleMini(); else ToggleFullScreen();
            return;
        }
        // Wait a moment in case it's a double-click.
        _clickTimer.Stop();
        _clickTimer.Start();
        _overlay.Focus();
    }

    void OnKey(object sender, KeyEventArgs e)
    {
        if (e.Handled) return;
        var live = Current?.IsLive == true;
        switch (e.Key)
        {
            case Key.Space: case Key.MediaPlayPause: case Key.K: TogglePlay(); break;
            case Key.Left: if (!live) Skip(-10); break;
            case Key.Right: if (!live) Skip(10); break;
            case Key.J: if (!live) Skip(-10); break;
            case Key.L: if (!live) Skip(10); break;
            case Key.Up: SetVolume((int)_volume.Value + 5, save: true, show: true); break;
            case Key.Down: SetVolume((int)_volume.Value - 5, save: true, show: true); break;
            case Key.PageUp: case Key.MediaPreviousTrack: Step(-1); break;
            case Key.PageDown: case Key.MediaNextTrack: Step(1); break;
            case Key.F: case Key.F11: ToggleFullScreen(); break;
            case Key.M: ToggleMute(); break;
            case Key.Escape:
                if (_fullScreen) ToggleFullScreen();
                else if (_miniMode) ToggleMini();
                else Close();
                break;
            default: return;
        }
        e.Handled = true;
    }

    // MARK: Closing

    void Shutdown()
    {
        if (_closing) return;
        _closing = true;
        Toast.Shown -= OnToast;
        _tick.Stop();
        _hide.Stop();
        _epgCts?.Cancel();
        SaveCurrent();
        Store.SaveSettings();
        var player = _player;
        var clarity = _clarity;
        _view.MediaPlayer = null;
        // Stopping VLC can take a moment on a stalled stream: don't hold up the window.
        _ = Task.Run(() =>
        {
            try { player.Stop(); } catch { }
            player.Dispose();
            clarity?.Dispose();
        });
        Downloads.SetStreaming(false);
        Application.Current.MainWindow?.Activate();
    }
}
