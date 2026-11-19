using System.Collections.Concurrent;
using System.ComponentModel;
using System.IO;
using System.Net.Http;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Shasha.Core;

namespace Shasha.UI;

/// <summary>
/// &lt;Image ui:Remote.Url="..."/&gt;: downloads posters in the background, shrinks them to the size
/// shown, and keeps them in memory and on disk. Images that scroll away before loading are skipped.
/// </summary>
public static class Remote
{
    public static readonly DependencyProperty UrlProperty = DependencyProperty.RegisterAttached(
        "Url", typeof(string), typeof(Remote), new PropertyMetadata(null, OnUrlChanged));

    public static readonly DependencyProperty DecodeWidthProperty = DependencyProperty.RegisterAttached(
        "DecodeWidth", typeof(int), typeof(Remote), new PropertyMetadata(320));

    public static string? GetUrl(DependencyObject d) => (string?)d.GetValue(UrlProperty);
    public static void SetUrl(DependencyObject d, string? value) => d.SetValue(UrlProperty, value);
    public static int GetDecodeWidth(DependencyObject d) => (int)d.GetValue(DecodeWidthProperty);
    public static void SetDecodeWidth(DependencyObject d, int value) => d.SetValue(DecodeWidthProperty, value);

    static readonly ConcurrentDictionary<string, WeakReference<ImageSource>> Memory = new();
    static readonly ConcurrentDictionary<string, Task<byte[]?>> Running = new();
    static readonly SemaphoreSlim Gate = new(6);
    static readonly HttpClient Client = Http.Create(TimeSpan.FromSeconds(20));
    static readonly string DiskDir = Path.Combine(Store.CacheDir, "images");

    static async void OnUrlChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not Image image) return;
        var url = e.NewValue as string;
        image.Source = null;
        if (string.IsNullOrWhiteSpace(url)) return;
        var width = GetDecodeWidth(image);
        var key = $"{width}|{url}";
        if (Memory.TryGetValue(key, out var weak) && weak.TryGetTarget(out var cached))
        {
            image.Source = cached;
            return;
        }
        // Skip images that only fly past while scrolling.
        await Task.Delay(80);
        if (GetUrl(image) != url || !image.IsLoaded && PresentationSource.FromVisual(image) == null) return;
        var bytes = await Fetch(url);
        if (bytes == null || GetUrl(image) != url) return;
        var source = await Task.Run(() => Decode(bytes, width));
        if (source == null || GetUrl(image) != url) return;
        Memory[key] = new WeakReference<ImageSource>(source);
        image.Source = source;
    }

    static ImageSource? Decode(byte[] bytes, int width)
    {
        try
        {
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            bmp.DecodePixelWidth = width;
            bmp.StreamSource = new MemoryStream(bytes);
            bmp.EndInit();
            bmp.Freeze();
            return bmp;
        }
        catch { return null; }
    }

    static string DiskPath(string url)
    {
        var hash = Convert.ToHexString(SHA1.HashData(Encoding.UTF8.GetBytes(url)));
        return Path.Combine(DiskDir, hash[..2], hash);
    }

    static Task<byte[]?> Fetch(string url) => Running.GetOrAdd(url, u => FetchCore(u).ContinueWith(t =>
    {
        Running.TryRemove(u, out _);
        return t.IsCompletedSuccessfully ? t.Result : null;
    }));

    static async Task<byte[]?> FetchCore(string url)
    {
        var path = DiskPath(url);
        try
        {
            if (File.Exists(path)) return await File.ReadAllBytesAsync(path);
        }
        catch { }
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return null;
        await Gate.WaitAsync();
        try
        {
            var bytes = await Client.GetByteArrayAsync(uri);
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                await File.WriteAllBytesAsync(path, bytes);
            }
            catch { }
            return bytes;
        }
        catch { return null; }
        finally { Gate.Release(); }
    }
}

public abstract class Bindable : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
        return true;
    }

    protected void Changed(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

/// <summary>A poster (film, series) or a channel tile / row.</summary>
public sealed class CardVM : Bindable
{
    public required string Title { get; init; }
    public string? Subtitle { get; init; }
    public string? Image { get; init; }
    public double Progress { get; init; }
    public Visibility ProgressVisible => Progress > 0 ? Visibility.Visible : Visibility.Collapsed;
    public string? Badge { get; init; }
    public Visibility BadgeVisible => string.IsNullOrEmpty(Badge) ? Visibility.Collapsed : Visibility.Visible;
    public Visibility SubtitleVisible => string.IsNullOrEmpty(Subtitle) ? Visibility.Collapsed : Visibility.Visible;

    bool _favourite;
    public bool Favourite { get => _favourite; set { if (Set(ref _favourite, value)) { Changed(nameof(FavouriteVisible)); Changed(nameof(StarGlyph)); } } }
    public Visibility FavouriteVisible => Favourite ? Visibility.Visible : Visibility.Collapsed;
    public string StarGlyph => Favourite ? "\uE735" : "\uE734";

    public bool Downloaded { get; init; }
    public Visibility DownloadedVisible => Downloaded ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>The thing this card stands for (StreamItem, WatchEntry, ...).</summary>
    public object? Model { get; init; }
    public int Index { get; init; }
}

/// <summary>One row of posters in a virtualised grid.</summary>
public sealed class RowVM
{
    public required List<CardVM> Cells { get; init; }
}

public sealed class EpisodeVM : Bindable
{
    public required Episode Episode { get; init; }
    public required string Title { get; init; }
    public string? Details { get; init; }
    public string? Image { get; init; }
    public double Progress { get; init; }
    public Visibility ProgressVisible => Progress > 0 ? Visibility.Visible : Visibility.Collapsed;
    public bool Watched { get; init; }
    public Visibility WatchedVisible => Watched ? Visibility.Visible : Visibility.Collapsed;
    public int Index { get; init; }

    string _downloadGlyph = "\uE896";
    public string DownloadGlyph { get => _downloadGlyph; set => Set(ref _downloadGlyph, value); }
    Brush _downloadBrush = Brushes.White;
    public Brush DownloadBrush { get => _downloadBrush; set => Set(ref _downloadBrush, value); }
    string? _downloadText;
    public string? DownloadText { get => _downloadText; set { if (Set(ref _downloadText, value)) Changed(nameof(DownloadTextVisible)); } }
    public Visibility DownloadTextVisible => string.IsNullOrEmpty(DownloadText) ? Visibility.Collapsed : Visibility.Visible;
}

public sealed class DownloadVM : Bindable
{
    public required DownloadItem Item { get; init; }
    public string Title => Item.Kind == WatchKind.Episode ? Item.Subtitle ?? Item.Title : Item.Title;
    public string? Image => Item.Image;
    public double ImageWidth => Item.Kind == WatchKind.Movie ? 54 : 96;

    string _status = "";
    public string Status { get => _status; set => Set(ref _status, value); }
    double _fraction;
    public double Fraction { get => _fraction; set => Set(ref _fraction, value); }
    Visibility _barVisible = Visibility.Collapsed;
    public Visibility BarVisible { get => _barVisible; set => Set(ref _barVisible, value); }
    bool _indeterminate;
    public bool Indeterminate { get => _indeterminate; set => Set(ref _indeterminate, value); }
    Brush _statusBrush = Brushes.Gray;
    public Brush StatusBrush { get => _statusBrush; set => Set(ref _statusBrush, value); }
    Visibility _playVisible = Visibility.Collapsed;
    public Visibility PlayVisible { get => _playVisible; set => Set(ref _playVisible, value); }
    Visibility _retryVisible = Visibility.Collapsed;
    public Visibility RetryVisible { get => _retryVisible; set => Set(ref _retryVisible, value); }

    public void Update()
    {
        var s = Downloads.StatusOf(Item);
        Status = Downloads.StatusText(s);
        Fraction = s?.Fraction ?? 0;
        Indeterminate = s?.State == DownloadState.Running && s.Fraction == null;
        BarVisible = s != null && (s.State == DownloadState.Running || (s.State == DownloadState.Paused && s.Bytes > 0) || (s.State == DownloadState.Queued && s.Bytes > 0))
            ? Visibility.Visible : Visibility.Collapsed;
        StatusBrush = s?.State == DownloadState.Failed ? (Brush)Application.Current.Resources["Warning"] : (Brush)Application.Current.Resources["Subtle"];
        PlayVisible = s?.State == DownloadState.Done ? Visibility.Visible : Visibility.Collapsed;
        RetryVisible = s?.State == DownloadState.Failed ? Visibility.Visible : Visibility.Collapsed;
    }
}

public sealed class GroupVM
{
    public required string Title { get; init; }
    public required List<DownloadVM> Items { get; init; }
}
