using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using static Shasha.Core.Loc;

namespace Shasha.Core;

public sealed class XtreamException : Exception
{
    public string Kind { get; }
    public int Code { get; }
    public XtreamException(string kind, int code = 0) : base(kind) { Kind = kind; Code = code; }
}

public static class Http
{
    /// <summary>Many IPTV servers reject unknown clients, so present ourselves as VLC.</summary>
    public const string UserAgent = "VLC/3.0.20 LibVLC/3.0.20";

    public static readonly HttpClient Client = Create(TimeSpan.FromSeconds(60));

    public static HttpClient Create(TimeSpan timeout)
    {
        var handler = new SocketsHttpHandler
        {
            AutomaticDecompression = DecompressionMethods.All,
            ConnectTimeout = TimeSpan.FromSeconds(20),
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        };
        var client = new HttpClient(handler) { Timeout = timeout };
        client.DefaultRequestHeaders.UserAgent.ParseAdd(UserAgent);
        return client;
    }

    /// <summary>Plain-language message for an error.</summary>
    public static string Friendly(Exception e)
    {
        switch (e)
        {
            case XtreamException x:
                return x.Kind switch
                {
                    "not_xtream" => L("err_not_xtream"),
                    "wrong_login" => L("err_wrong_login"),
                    "invalid_address" => L("err_invalid_address"),
                    "refused" => L("err_denied", x.Code),
                    "server" => L("err_server_code", x.Code),
                    _ => L("err_unexpected"),
                };
            case TaskCanceledException:
                return L("err_timeout");
            case HttpRequestException h when h.InnerException is System.Net.Sockets.SocketException s:
                return s.SocketErrorCode == System.Net.Sockets.SocketError.HostNotFound ? L("err_unknown_host") : L("err_connect");
            case HttpRequestException h when h.InnerException is System.Security.Authentication.AuthenticationException:
                return L("err_ssl");
            case HttpRequestException:
                return L("err_network");
            default:
                return L("err_generic");
        }
    }
}

/// <summary>Client for the Xtream Codes "player_api.php" API.</summary>
public sealed class XtreamApi
{
    readonly Account _account;
    public XtreamApi(Account account) => _account = account;

    string Base => _account.Server.TrimEnd('/');
    string User => Uri.EscapeDataString(_account.Username);
    string Pass => Uri.EscapeDataString(_account.Password);

    public string LiveUrl(string id, string ext) => $"{Base}/live/{User}/{Pass}/{id}.{ext}";
    public string MovieUrl(string id, string ext) => $"{Base}/movie/{User}/{Pass}/{id}.{ext}";
    public string EpisodeUrl(string id, string ext) => $"{Base}/series/{User}/{Pass}/{id}.{ext}";

    string ApiUrl(string? action = null, params (string, string)[] extra)
    {
        var sb = new StringBuilder($"{Base}/player_api.php?username={User}&password={Pass}");
        if (action != null) sb.Append("&action=").Append(action);
        foreach (var (k, v) in extra) sb.Append('&').Append(k).Append('=').Append(Uri.EscapeDataString(v));
        return sb.ToString();
    }

    static async Task<JsonDocument> Fetch(string url, IProgress<long>? progress = null, CancellationToken ct = default)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) throw new XtreamException("invalid_address");
        using var response = await Http.Client.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, ct);
        var code = (int)response.StatusCode;
        if (code is 401 or 403) throw new XtreamException("refused", code);
        if (!response.IsSuccessStatusCode) throw new XtreamException("server", code);
        await using var stream = await response.Content.ReadAsStreamAsync(ct);
        if (progress == null) return await JsonDocument.ParseAsync(stream, cancellationToken: ct);
        // Count bytes as they arrive for the progress bar (lists can be tens of MB).
        using var buffer = new MemoryStream();
        var chunk = new byte[81920];
        long total = 0, last = 0;
        int n;
        while ((n = await stream.ReadAsync(chunk, ct)) > 0)
        {
            buffer.Write(chunk, 0, n);
            total += n;
            if (total - last > 256 * 1024) { last = total; progress.Report(total); }
        }
        progress.Report(total);
        buffer.Position = 0;
        return await JsonDocument.ParseAsync(buffer, cancellationToken: ct);
    }

    public async Task<AuthInfo> Login(CancellationToken ct = default)
    {
        JsonDocument doc;
        try { doc = await Fetch(ApiUrl(), ct: ct); }
        catch (JsonException) { throw new XtreamException("not_xtream"); }
        using (doc)
        {
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("user_info", out var info) || info.ValueKind != JsonValueKind.Object)
                throw new XtreamException("not_xtream");
            if (info.Str("auth").Int() != 1) throw new XtreamException("wrong_login");
            var formats = new List<string>();
            if (info.TryGetProperty("allowed_output_formats", out var f) && f.ValueKind == JsonValueKind.Array)
                foreach (var x in f.EnumerateArray()) if (x.ValueKind == JsonValueKind.String) formats.Add(x.GetString()!.ToLowerInvariant());
            return new AuthInfo(info.Str("status"), info.Str("exp_date").Long(), info.Str("max_connections"), info.Str("active_cons"), formats);
        }
    }

    public async Task<List<Category>> Categories(ContentType type, CancellationToken ct = default)
    {
        var action = type switch { ContentType.Live => "get_live_categories", ContentType.Movie => "get_vod_categories", _ => "get_series_categories" };
        using var doc = await Fetch(ApiUrl(action), ct: ct);
        var list = new List<Category>();
        if (doc.RootElement.ValueKind != JsonValueKind.Array) return list;
        foreach (var o in doc.RootElement.EnumerateArray())
        {
            var id = o.Str("category_id");
            if (id != null) list.Add(new Category(id, o.Str("category_name") ?? "—"));
        }
        return list;
    }

    public async Task<List<StreamItem>> Streams(ContentType type, IProgress<long>? progress = null, CancellationToken ct = default)
    {
        var action = type switch { ContentType.Live => "get_live_streams", ContentType.Movie => "get_vod_streams", _ => "get_series" };
        using var doc = await Fetch(ApiUrl(action), progress, ct);
        var list = new List<StreamItem>();
        if (doc.RootElement.ValueKind != JsonValueKind.Array) return list;
        foreach (var o in doc.RootElement.EnumerateArray())
        {
            if (type == ContentType.Series)
            {
                var id = o.Str("series_id");
                if (id == null) continue;
                var name = o.Str("name") ?? "—";
                list.Add(new StreamItem
                {
                    Id = id, Name = name, Icon = o.Str("cover"), CategoryId = o.Str("category_id"), Type = type,
                    Rating = o.Str("rating"), Added = o.Str("last_modified").Long(), Year = ReleaseYear(o, name),
                });
            }
            else
            {
                var id = o.Str("stream_id");
                if (id == null) continue;
                var name = o.Str("name") ?? "—";
                list.Add(new StreamItem
                {
                    Id = id, Name = name, Icon = o.Str("stream_icon"), CategoryId = o.Str("category_id"), Type = type,
                    Ext = o.Str("container_extension"), Rating = o.Str("rating"), Added = o.Str("added").Long(),
                    Year = type == ContentType.Movie ? ReleaseYear(o, name) : null,
                });
            }
        }
        return list;
    }

    static int? ReleaseYear(JsonElement o, string name)
    {
        var y = o.Str("year");
        if (y != null && y.Length >= 4 && int.TryParse(y[..4], out var year) && year is >= 1900 and <= 2100) return year;
        return Text.FirstYear(o.Str("releaseDate") ?? o.Str("release_date") ?? o.Str("releasedate")) ?? Text.YearFromTitle(name);
    }

    public async Task<VodInfo> VodInfo(string id, CancellationToken ct = default)
    {
        using var doc = await Fetch(ApiUrl("get_vod_info", ("vod_id", id)), ct: ct);
        var root = doc.RootElement;
        var info = root.ValueKind == JsonValueKind.Object && root.TryGetProperty("info", out var i) && i.ValueKind == JsonValueKind.Object ? i : default;
        var data = root.ValueKind == JsonValueKind.Object && root.TryGetProperty("movie_data", out var d) && d.ValueKind == JsonValueKind.Object ? d : default;
        return new VodInfo(
            data.Str("name") ?? info.Str("name"), info.Str("plot") ?? info.Str("description"), info.Str("genre"),
            info.Str("duration"), info.Str("releasedate") ?? info.Str("release_date"), info.Str("rating"),
            info.Str("cover_big") ?? info.Str("movie_image"), info.Str("director"), info.Str("cast") ?? info.Str("actors"),
            data.Str("container_extension"));
    }

    public async Task<SeriesInfo> SeriesInfo(string id, CancellationToken ct = default)
    {
        using var doc = await Fetch(ApiUrl("get_series_info", ("series_id", id)), ct: ct);
        var root = doc.RootElement;
        var info = root.ValueKind == JsonValueKind.Object && root.TryGetProperty("info", out var i) && i.ValueKind == JsonValueKind.Object ? i : default;
        var seasons = new Dictionary<int, List<Episode>>();

        void Add(JsonElement o, int fallbackSeason)
        {
            var epId = o.Str("id");
            if (epId == null) return;
            var season = o.Str("season").Int() ?? fallbackSeason;
            var number = o.Str("episode_num").Int() ?? 0;
            var epInfo = o.TryGetProperty("info", out var ei) && ei.ValueKind == JsonValueKind.Object ? ei : default;
            var ep = new Episode(epId, o.Str("title") ?? number.ToString(), season, number, o.Str("container_extension") ?? "mp4",
                epInfo.Str("plot"), epInfo.Str("movie_image"), epInfo.Str("duration"));
            if (!seasons.TryGetValue(season, out var list)) seasons[season] = list = new List<Episode>();
            list.Add(ep);
        }

        if (root.ValueKind == JsonValueKind.Object && root.TryGetProperty("episodes", out var eps))
        {
            // Servers return episodes either as {"1": [...], "2": [...]} or as [[...], [...]].
            if (eps.ValueKind == JsonValueKind.Object)
            {
                foreach (var p in eps.EnumerateObject())
                    if (p.Value.ValueKind == JsonValueKind.Array)
                        foreach (var o in p.Value.EnumerateArray()) Add(o, p.Name.Int() ?? 1);
            }
            else if (eps.ValueKind == JsonValueKind.Array)
            {
                var index = 0;
                foreach (var v in eps.EnumerateArray())
                {
                    index++;
                    if (v.ValueKind == JsonValueKind.Array) foreach (var o in v.EnumerateArray()) Add(o, index);
                    else if (v.ValueKind == JsonValueKind.Object) Add(v, 1);
                }
            }
        }
        foreach (var k in seasons.Keys.ToList()) seasons[k] = seasons[k].OrderBy(e => e.Number).ToList();
        return new SeriesInfo
        {
            Name = info.Str("name"), Cover = info.Str("cover"), Plot = info.Str("plot"), Genre = info.Str("genre"),
            ReleaseDate = info.Str("releaseDate") ?? info.Str("release_date"), Rating = info.Str("rating"), Seasons = seasons,
        };
    }

    /// <summary>Now/next programme info for a live channel. Empty if the provider has none.</summary>
    public async Task<List<EpgEntry>> ShortEpg(string streamId, CancellationToken ct = default)
    {
        try
        {
            using var doc = await Fetch(ApiUrl("get_short_epg", ("stream_id", streamId), ("limit", "4")), ct: ct);
            var list = new List<EpgEntry>();
            if (doc.RootElement.ValueKind != JsonValueKind.Object || !doc.RootElement.TryGetProperty("epg_listings", out var l) || l.ValueKind != JsonValueKind.Array)
                return list;
            foreach (var o in l.EnumerateArray())
            {
                var raw = o.Str("title");
                var start = o.Str("start_timestamp").Long();
                var end = (o.Str("stop_timestamp") ?? o.Str("end_timestamp")).Long();
                if (raw == null || start == null || end == null) continue;
                string title;
                try { title = Encoding.UTF8.GetString(Convert.FromBase64String(raw)); } catch { title = raw; }
                list.Add(new EpgEntry(title.Trim(), DateTimeOffset.FromUnixTimeSeconds(start.Value).LocalDateTime,
                    DateTimeOffset.FromUnixTimeSeconds(end.Value).LocalDateTime));
            }
            return list.OrderBy(e => e.Start).ToList();
        }
        catch { return new(); }
    }

    /// <summary>"example.com:8080/", "http://example.com:8080/get.php?..." → "http://example.com:8080".</summary>
    public static string? NormalizeServer(string input)
    {
        var s = input.Trim();
        if (s.Length == 0) return null;
        if (!s.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !s.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            s = "http://" + s;
        if (!Uri.TryCreate(s, UriKind.Absolute, out var uri) || string.IsNullOrEmpty(uri.Host)) return null;
        var port = uri.IsDefaultPort ? "" : $":{uri.Port}";
        return $"{uri.Scheme.ToLowerInvariant()}://{uri.Host}{port}";
    }
}
