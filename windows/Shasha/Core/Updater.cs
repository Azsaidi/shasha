using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Text.Json;

namespace Shasha.Core;

/// <summary>Short messages at the bottom of the window.</summary>
public static class Toast
{
    public static event Action<string>? Shown;
    public static void Show(string text) => Ui.Post(() => Shown?.Invoke(text));
}

/// <summary>Checks GitHub for a newer Windows release (tags "win-v…"), downloads the installer and runs it.</summary>
public static class Updater
{
    const string Releases = "https://api.github.com/repos/Azsaidi/shasha/releases?per_page=40";

    public sealed record Release(string Version, string Url, long Size);

    public static string CurrentVersion
    {
        get
        {
            var v = Assembly.GetExecutingAssembly().GetName().Version;
            return v == null ? "1.0.0" : $"{v.Major}.{v.Minor}.{v.Build}";
        }
    }

    static bool IsNewer(string candidate, string current)
    {
        var a = candidate.Split('.').Select(p => int.TryParse(p, out var n) ? n : 0).ToArray();
        var b = current.Split('.').Select(p => int.TryParse(p, out var n) ? n : 0).ToArray();
        for (var i = 0; i < Math.Max(a.Length, b.Length); i++)
        {
            var x = i < a.Length ? a[i] : 0;
            var y = i < b.Length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /// <summary>The newer release, or null when this is the latest.</summary>
    public static async Task<Release?> Check()
    {
        try { return await FromApi(); }
        catch (Exception e)
        {
            // GitHub's API allows 60 checks an hour per connection, then answers 403: use the feed.
            Shasha.Core.Trace.Write("update: api failed (" + e.Message + "), using the releases feed");
            return await FromFeed();
        }
    }

    /// <summary>
    /// The public releases feed (no hourly limit): the newest "win-v" tag, and the installer's
    /// address follows from it.
    /// </summary>
    static async Task<Release?> FromFeed()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, "https://github.com/Azsaidi/shasha/releases.atom");
        request.Headers.UserAgent.ParseAdd($"Shasha-Windows/{CurrentVersion}");
        using var response = await Http.Client.SendAsync(request);
        response.EnsureSuccessStatusCode();
        var xml = await response.Content.ReadAsStringAsync();
        foreach (System.Text.RegularExpressions.Match m in System.Text.RegularExpressions.Regex.Matches(xml, @"releases/tag/(win-v[0-9.]+)"))
        {
            var tag = m.Groups[1].Value;
            var version = tag["win-v".Length..];
            return IsNewer(version, CurrentVersion)
                ? new Release(version, $"https://github.com/Azsaidi/shasha/releases/download/{tag}/Shasha-Setup-{version}.exe", 0)
                : null;
        }
        return null;
    }

    static async Task<Release?> FromApi()
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, Releases);
        request.Headers.TryAddWithoutValidation("Accept", "application/vnd.github+json");
        request.Headers.UserAgent.Clear();
        request.Headers.UserAgent.ParseAdd($"Shasha-Windows/{CurrentVersion}");
        using var response = await Http.Client.SendAsync(request);
        response.EnsureSuccessStatusCode();
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        foreach (var r in doc.RootElement.EnumerateArray())
        {
            if (r.TryGetProperty("draft", out var d) && d.GetBoolean()) continue;
            var tag = r.Str("tag_name") ?? "";
            if (!tag.StartsWith("win-v")) continue;
            if (!r.TryGetProperty("assets", out var assets)) continue;
            foreach (var a in assets.EnumerateArray())
            {
                var name = a.Str("name") ?? "";
                if (!name.StartsWith("Shasha-Setup", StringComparison.OrdinalIgnoreCase) || !name.EndsWith(".exe")) continue;
                var version = tag["win-v".Length..];
                return IsNewer(version, CurrentVersion) ? new Release(version, a.Str("browser_download_url")!, a.Str("size").Long() ?? 0) : null;
            }
        }
        return null;
    }

    /// <summary>Downloads the installer, starts it (it closes this app, updates and reopens it).</summary>
    public static async Task Install(Release release, IProgress<double> progress, CancellationToken ct)
    {
        var path = Path.Combine(Path.GetTempPath(), $"Shasha-Setup-{release.Version}.exe");
        using (var response = await Http.Client.GetAsync(release.Url, HttpCompletionOption.ResponseHeadersRead, ct))
        {
            response.EnsureSuccessStatusCode();
            var total = response.Content.Headers.ContentLength ?? release.Size;
            await using var input = await response.Content.ReadAsStreamAsync(ct);
            await using var output = File.Create(path);
            var buffer = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = await input.ReadAsync(buffer, ct)) > 0)
            {
                await output.WriteAsync(buffer.AsMemory(0, n), ct);
                done += n;
                if (total > 0) progress.Report((double)done / total);
            }
        }
        Process.Start(new ProcessStartInfo(path, "/SILENT /SUPPRESSMSGBOXES /NORESTART") { UseShellExecute = true });
        System.Windows.Application.Current.Shutdown();
    }
}
