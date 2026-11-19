using System.IO;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Text.Json;
using System.Windows;
using static Shasha.Core.Loc;

namespace Shasha.Core;

public sealed class DownloadItem
{
    public WatchKind Kind { get; set; }
    public string StreamId { get; set; } = "";
    public string AccountId { get; set; } = "";
    public string Title { get; set; } = "";
    public string? Subtitle { get; set; }
    public string? Image { get; set; }
    public string Ext { get; set; } = "mp4";
    public string? SeriesId { get; set; }
    public string? SeriesName { get; set; }
    public int Season { get; set; }
    public int Episode { get; set; }
    public string FilePath { get; set; } = "";
    public DateTime AddedAt { get; set; } = DateTime.UtcNow;
    public string Url { get; set; } = "";
    public bool Done { get; set; }
    /// <summary>0 = no failure; an HTTP code; or one of the reason values.</summary>
    public int FailedReason { get; set; }
    public long Total { get; set; }

    public string Key => WatchEntry.KeyOf(Kind, StreamId);
}

public enum DownloadState { Queued, Running, Paused, Done, Failed }

public sealed record DownloadStatus(DownloadState State, long Bytes, long Total, int Reason = 0, double Speed = 0)
{
    public double? Fraction => Total > 0 ? Math.Clamp((double)Bytes / Total, 0, 1) : null;
    public double? Eta => State == DownloadState.Running && Total > 0 && Speed > 1 ? Math.Max(Total - Bytes, 0) / Speed : null;
    public bool Active => State is DownloadState.Queued or DownloadState.Running or DownloadState.Paused;
}

/// <summary>Runs on the UI thread (posts back to it from the download task).</summary>
public static class Ui
{
    public static void Post(Action action)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null) return;
        if (d.CheckAccess()) action(); else d.BeginInvoke(action);
    }
}

/// <summary>
/// The download queue: one at a time, continues from where it stopped (HTTP ranges), retries with a
/// growing wait, and pauses while something streams on plans that allow only one connection.
/// </summary>
public static class Downloads
{
    public const int ReasonDropped = -2;
    public const int ReasonNoSpace = -3;
    public const int ReasonMissing = -4;
    public const int ReasonRetrying = -10;
    public const int ReasonStreaming = -11;
    const int MaxAttempts = 8;

    static readonly string ListFile = Path.Combine(Store.Root, "downloads.json");
    static readonly HttpClient Client = Http.Create(Timeout.InfiniteTimeSpan);

    public static List<DownloadItem> Items { get; private set; } = new();
    public static Dictionary<string, DownloadStatus> Status { get; } = new();
    public static bool StreamingNow { get; private set; }
    public static bool IsOnline { get; private set; } = true;
    public static event Action? Changed;

    static int _maxConnections = 1;
    public static int MaxConnections
    {
        get => _maxConnections;
        set { if (_maxConnections != value) { _maxConnections = value; Kick(); } }
    }

    static string? _runningKey;
    static CancellationTokenSource? _runningCts;
    static bool _stopIsPause;
    static readonly Dictionary<string, int> Attempts = new();

    public static void Start()
    {
        try
        {
            if (File.Exists(ListFile))
                Items = JsonSerializer.Deserialize<List<DownloadItem>>(File.ReadAllText(ListFile), Store.Json) ?? new();
        }
        catch { Items = new(); }
        foreach (var item in Items) Status[item.Key] = InitialStatus(item);
        IsOnline = NetworkInterface.GetIsNetworkAvailable();
        NetworkChange.NetworkAvailabilityChanged += (_, e) => Ui.Post(() =>
        {
            IsOnline = e.IsAvailable;
            Changed?.Invoke();
            if (IsOnline) Kick();
        });
        Kick();
    }

    static long Size(string path)
    {
        try { return File.Exists(path) ? new FileInfo(path).Length : 0; } catch { return 0; }
    }

    static string Part(DownloadItem item) => item.FilePath + ".part";

    static DownloadStatus InitialStatus(DownloadItem item)
    {
        var size = Size(item.FilePath);
        if (item.Done && size > 0) return new DownloadStatus(DownloadState.Done, size, size);
        if (item.Done) return new DownloadStatus(DownloadState.Failed, 0, 0, ReasonMissing);
        var part = Size(Part(item));
        if (item.FailedReason != 0) return new DownloadStatus(DownloadState.Failed, part, item.Total, item.FailedReason);
        return new DownloadStatus(DownloadState.Queued, part, item.Total);
    }

    static void Save()
    {
        var copy = JsonSerializer.Serialize(Items, Store.Json);
        Task.Run(() =>
        {
            try { File.WriteAllText(ListFile + ".tmp", copy); File.Move(ListFile + ".tmp", ListFile, true); } catch { }
        });
    }

    static void Notify() => Changed?.Invoke();

    public static DownloadItem? Find(WatchKind kind, string id) => Items.FirstOrDefault(i => i.Key == WatchEntry.KeyOf(kind, id));
    public static DownloadStatus? StatusOf(DownloadItem item) => Status.GetValueOrDefault(item.Key);
    public static bool IsDownloaded(WatchKind kind, string id) => Status.GetValueOrDefault(WatchEntry.KeyOf(kind, id))?.State == DownloadState.Done;

    /// <summary>The file for a finished download, so playback works without internet.</summary>
    public static string? LocalPath(WatchKind kind, string id)
    {
        var item = Find(kind, id);
        if (item == null || !IsDownloaded(kind, id)) return null;
        return Size(item.FilePath) > 0 ? item.FilePath : null;
    }

    static bool OnePerAccount => MaxConnections <= 1;
    public static bool CanDownloadNow => !(StreamingNow && OnePerAccount);

    /// <summary>Called by the players. On one-connection plans the download pauses and continues afterwards.</summary>
    public static void SetStreaming(bool active)
    {
        if (StreamingNow == active) return;
        StreamingNow = active;
        if (active && OnePerAccount)
        {
            if (_runningCts != null) { _stopIsPause = true; _runningCts.Cancel(); }
        }
        else if (!active)
        {
            // Give the provider a few seconds to notice the stream has stopped.
            _ = Task.Delay(4000).ContinueWith(_ => Ui.Post(() => { if (!StreamingNow) Kick(); }));
        }
        Notify();
    }

    static string SafeName(string s)
    {
        foreach (var c in Path.GetInvalidFileNameChars()) s = s.Replace(c, ' ');
        s = s.Trim().TrimEnd('.');
        return s.Length > 120 ? s[..120] : s;
    }

    public static bool Enqueue(string url, WatchKind kind, string streamId, string accountId, string title, string? subtitle,
                               string? image, string ext, string? seriesId = null, string? seriesName = null, int season = 0, int episode = 0)
    {
        var existing = Find(kind, streamId);
        if (existing != null)
        {
            if (StatusOf(existing)?.State != DownloadState.Failed) return true;
            Remove(existing);
        }
        if (string.IsNullOrWhiteSpace(ext)) ext = "mp4";
        var name = kind == WatchKind.Episode
            ? $"{SafeName(seriesName ?? title)} - S{season:00}E{episode:00}"
            : SafeName(title);
        var folder = Store.DownloadsFolder;
        var path = Path.Combine(folder, $"{name}.{ext}");
        if (File.Exists(path) || File.Exists(path + ".part") || Items.Any(i => i.FilePath == path))
            path = Path.Combine(folder, $"{name} ({streamId}).{ext}");
        var item = new DownloadItem
        {
            Kind = kind, StreamId = streamId, AccountId = accountId, Title = title, Subtitle = subtitle, Image = image, Ext = ext,
            SeriesId = seriesId, SeriesName = seriesName, Season = season, Episode = episode, FilePath = path, Url = url,
        };
        Items.Add(item);
        Status[item.Key] = new DownloadStatus(DownloadState.Queued, 0, 0);
        Save();
        Notify();
        Kick();
        return true;
    }

    public static void Retry(DownloadItem item)
    {
        item.FailedReason = 0;
        Attempts[item.Key] = 0;
        Status[item.Key] = new DownloadStatus(DownloadState.Queued, Size(Part(item)), item.Total);
        Save();
        Notify();
        Kick();
    }

    public static void Remove(DownloadItem item)
    {
        if (_runningKey == item.Key) { _stopIsPause = false; _runningCts?.Cancel(); }
        Items.Remove(item);
        Status.Remove(item.Key);
        Attempts.Remove(item.Key);
        _ = Task.Run(async () =>
        {
            await Task.Delay(500);
            try { File.Delete(item.FilePath); } catch { }
            try { File.Delete(Part(item)); } catch { }
        });
        Save();
        Notify();
        Kick();
    }

    public static long UsedBytes => Items.Sum(i => Math.Max(Size(i.FilePath), Size(Part(i))));

    public static long FreeBytes
    {
        get
        {
            try { return new DriveInfo(Path.GetPathRoot(Store.DownloadsFolder)!).AvailableFreeSpace; } catch { return 0; }
        }
    }

    static DownloadItem? Next() =>
        !CanDownloadNow ? null :
        Items.Where(i => Status.GetValueOrDefault(i.Key) is { } s &&
                         (s.State == DownloadState.Queued || (s.State == DownloadState.Paused && s.Reason != ReasonRetrying)))
             .OrderBy(i => i.AddedAt).FirstOrDefault();

    /// <summary>Starts the next download if nothing is downloading.</summary>
    public static void Kick()
    {
        if (_runningKey != null || !IsOnline) return;
        var item = Next();
        if (item == null) return;
        var cts = new CancellationTokenSource();
        _runningKey = item.Key;
        _runningCts = cts;
        _stopIsPause = false;
        var have = Size(Part(item));
        Status[item.Key] = new DownloadStatus(DownloadState.Running, have, item.Total);
        Notify();
        _ = Run(item, cts.Token);
    }

    static async Task Run(DownloadItem item, CancellationToken ct)
    {
        int? failCode = null;
        int? retryCode = null;
        var finished = false;
        try
        {
            var part = Part(item);
            var have = Size(part);
            using var request = new HttpRequestMessage(HttpMethod.Get, item.Url);
            if (have > 0) request.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(have, null);
            using var response = await Client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
            var code = (int)response.StatusCode;
            if (code == 416 && have > 0)
            {
                finished = true;
            }
            else if (code is 401 or 404 or 410)
            {
                failCode = code;
            }
            else if (!response.IsSuccessStatusCode)
            {
                // 403/429/509 usually mean "connection limit reached": retry with a growing wait.
                retryCode = code;
            }
            else
            {
                var append = have > 0 && code == 206;
                if (!append) have = 0;
                var total = response.Content.Headers.ContentRange?.Length
                            ?? (response.Content.Headers.ContentLength is long len ? len + have : 0);
                if (total > 0) Ui.Post(() => { item.Total = total; Save(); });
                await using var input = await response.Content.ReadAsStreamAsync(ct);
                await using var output = new FileStream(part, append ? FileMode.Append : FileMode.Create, FileAccess.Write, FileShare.Read, 1 << 20, true);
                var buffer = new byte[1 << 20];
                var done = have;
                var lastTime = DateTime.UtcNow;
                var lastBytes = done;
                double speed = 0;
                int n;
                while ((n = await input.ReadAsync(buffer, ct)) > 0)
                {
                    await output.WriteAsync(buffer.AsMemory(0, n), ct);
                    done += n;
                    var now = DateTime.UtcNow;
                    var dt = (now - lastTime).TotalSeconds;
                    if (dt >= 0.5)
                    {
                        var instant = (done - lastBytes) / dt;
                        speed = speed > 0 ? speed * 0.8 + instant * 0.2 : instant;
                        lastTime = now;
                        lastBytes = done;
                        var status = new DownloadStatus(DownloadState.Running, done, total, 0, speed);
                        Ui.Post(() =>
                        {
                            if (_runningKey != item.Key) return;
                            Status[item.Key] = status;
                            Attempts[item.Key] = 0;
                            Notify();
                        });
                    }
                }
                await output.FlushAsync(CancellationToken.None);
                finished = total <= 0 || done >= total;
                if (!finished) retryCode = ReasonDropped;
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            Ui.Post(() =>
            {
                if (_runningKey == item.Key) { _runningKey = null; _runningCts = null; }
                if (_stopIsPause && Status.ContainsKey(item.Key))
                {
                    Status[item.Key] = new DownloadStatus(DownloadState.Paused, Size(Part(item)), item.Total, ReasonStreaming);
                }
                _stopIsPause = false;
                Notify();
                Kick();
            });
            return;
        }
        catch (IOException e) when ((e.HResult & 0xFFFF) is 0x70 or 0x27)
        {
            failCode = ReasonNoSpace;
        }
        catch (Exception)
        {
            retryCode = ReasonDropped;
        }

        Ui.Post(() =>
        {
            if (_runningKey == item.Key) { _runningKey = null; _runningCts = null; }
            if (!Items.Contains(item)) { Kick(); return; }
            if (finished) Finish(item);
            else if (failCode is int f) Fail(item, f);
            else ScheduleRetry(item, retryCode ?? ReasonDropped);
            Notify();
            Kick();
        });
    }

    static void Finish(DownloadItem item)
    {
        try
        {
            if (File.Exists(item.FilePath)) File.Delete(item.FilePath);
            File.Move(Part(item), item.FilePath);
        }
        catch
        {
            Fail(item, ReasonMissing);
            return;
        }
        var size = Size(item.FilePath);
        item.Done = true;
        item.FailedReason = 0;
        item.Total = size;
        Status[item.Key] = new DownloadStatus(DownloadState.Done, size, size);
        Attempts.Remove(item.Key);
        Save();
    }

    static void Fail(DownloadItem item, int reason)
    {
        item.FailedReason = reason;
        Status[item.Key] = new DownloadStatus(DownloadState.Failed, Size(Part(item)), item.Total, reason);
        Save();
    }

    static void ScheduleRetry(DownloadItem item, int code)
    {
        var attempt = Attempts.GetValueOrDefault(item.Key) + 1;
        Attempts[item.Key] = attempt;
        if (attempt > MaxAttempts)
        {
            Fail(item, code);
            return;
        }
        Status[item.Key] = new DownloadStatus(DownloadState.Paused, Size(Part(item)), item.Total, ReasonRetrying);
        var wait = TimeSpan.FromSeconds(Math.Min(attempt * 5, 60));
        _ = Task.Delay(wait).ContinueWith(_ => Ui.Post(() =>
        {
            if (Status.GetValueOrDefault(item.Key) is { State: DownloadState.Paused, Reason: ReasonRetrying } s)
            {
                Status[item.Key] = s with { State = DownloadState.Queued, Reason = 0 };
                Notify();
                Kick();
            }
        }));
    }

    /// <summary>Plain-language status line.</summary>
    public static string StatusText(DownloadStatus? status)
    {
        if (status == null) return L("download_queued");
        switch (status.State)
        {
            case DownloadState.Done:
                return $"{L("downloaded")} · {Text.FormatBytes(status.Total)}";
            case DownloadState.Running:
                var parts = new List<string>();
                if (status.Fraction is double f)
                {
                    parts.Add(L("downloading_pct", (int)(f * 100)));
                    parts.Add($"{Text.FormatBytes(status.Bytes)} / {Text.FormatBytes(status.Total)}");
                }
                else
                {
                    parts.Add(L("downloading"));
                    parts.Add(Text.FormatBytes(status.Bytes));
                }
                if (status.Speed > 1) parts.Add($"{Text.FormatBytes((long)status.Speed)}/s");
                if (status.Eta is double eta) parts.Add(L("time_left", Text.FormatDuration(eta)));
                return string.Join(" · ", parts);
            case DownloadState.Failed:
                return status.Reason switch
                {
                    ReasonNoSpace => L("dl_err_space"),
                    ReasonDropped => L("dl_err_dropped"),
                    ReasonMissing => L("download_failed"),
                    401 or 403 => L("dl_err_refused", status.Reason, MaxConnections),
                    404 or 410 => L("dl_err_not_found"),
                    _ => L("dl_err_code", status.Reason),
                };
            default:
                if (status.Reason == ReasonRetrying) return L("dl_retrying");
                if (!IsOnline) return L("download_paused");
                if (!CanDownloadNow) return L("dl_waiting_stream");
                if (status.State == DownloadState.Queued && _runningKey != null) return L("dl_waiting_turn");
                return L("download_queued");
        }
    }
}
