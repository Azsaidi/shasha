using System.Globalization;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace Shasha.Core;

public sealed class Account
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string Name { get; set; } = "";
    public string Server { get; set; } = "";
    public string Username { get; set; } = "";
    public string Password { get; set; } = "";
}

public enum ContentType { Live, Movie, Series }

public static class ContentTypeText
{
    public static string TitleKey(this ContentType t) => t switch
    {
        ContentType.Live => "tab_live",
        ContentType.Movie => "tab_movies",
        _ => "tab_series",
    };

    public static string NounKey(this ContentType t) => t switch
    {
        ContentType.Live => "noun_channels",
        ContentType.Movie => "noun_movies",
        _ => "noun_series",
    };
}

public sealed record Category(string Id, string Name);

public sealed class StreamItem
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string? Icon { get; set; }
    public string? CategoryId { get; set; }
    public ContentType Type { get; set; }
    public string? Ext { get; set; }
    public string? Rating { get; set; }
    public long? Added { get; set; }
    public int? Year { get; set; }

    [JsonIgnore] public string Uid => $"{Type}:{Id}";
    [JsonIgnore] public double RatingValue => double.TryParse(Rating, NumberStyles.Float, CultureInfo.InvariantCulture, out var r) ? r : 0;
    [JsonIgnore] public string? YearText => Year?.ToString(CultureInfo.InvariantCulture);
}

public sealed class Library
{
    public List<Category> Categories { get; set; } = new();
    public List<StreamItem> Items { get; set; } = new();
}

public sealed record AuthInfo(string? Status, long? ExpDate, string? MaxConnections, string? ActiveConnections, List<string> AllowedFormats);

public sealed record Episode(string Id, string Title, int Season, int Number, string Ext, string? Plot, string? Image, string? Duration);

public sealed class SeriesInfo
{
    public string? Name { get; init; }
    public string? Cover { get; init; }
    public string? Plot { get; init; }
    public string? Genre { get; init; }
    public string? ReleaseDate { get; init; }
    public string? Rating { get; init; }
    public Dictionary<int, List<Episode>> Seasons { get; init; } = new();
    public List<int> SeasonNumbers => Seasons.Keys.OrderBy(k => k).ToList();
}

public sealed record VodInfo(string? Name, string? Plot, string? Genre, string? Duration, string? ReleaseDate, string? Rating,
                             string? Cover, string? Director, string? Cast, string? Ext);

public sealed record EpgEntry(string Title, DateTime Start, DateTime End);

public enum WatchKind { Live, Movie, Episode }

/// <summary>One row of watch history: what was played, where it stopped, and enough to play it again.</summary>
public sealed class WatchEntry
{
    public WatchKind Kind { get; set; }
    public string StreamId { get; set; } = "";
    public string Title { get; set; } = "";
    public string? Subtitle { get; set; }
    public string? Image { get; set; }
    public string? Ext { get; set; }
    public string? SeriesId { get; set; }
    public string? SeriesName { get; set; }
    /// <summary>Seconds.</summary>
    public double Position { get; set; }
    public double Duration { get; set; }
    public bool Finished { get; set; }
    /// <summary>Set on the episode after one that was finished, so it shows as "Up next".</summary>
    public bool UpNext { get; set; }
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;

    public static string KeyOf(WatchKind kind, string id) => $"{kind}:{id}";
    [JsonIgnore] public string Key => KeyOf(Kind, StreamId);
    [JsonIgnore] public double Progress => Duration > 0 ? Math.Clamp(Position / Duration, 0, 1) : 0;
    [JsonIgnore] public bool Resumable => Kind != WatchKind.Live && !Finished && (Position > 10 || UpNext);
    /// <summary>An episode that was only opened for a moment. It shouldn't hide the one before it.</summary>
    [JsonIgnore] public bool BarelyStarted => Kind == WatchKind.Episode && !Finished && !UpNext && Position <= 10;

    public WatchEntry Copy() => (WatchEntry)MemberwiseClone();
}

public static class Text
{
    static readonly Regex YearRx = new(@"\b(19\d{2}|20\d{2})\b", RegexOptions.Compiled);

    public static int? FirstYear(string? s)
    {
        if (string.IsNullOrWhiteSpace(s)) return null;
        var m = YearRx.Match(s);
        return m.Success ? int.Parse(m.Value, CultureInfo.InvariantCulture) : null;
    }

    /// <summary>Year in brackets at the end of a title, e.g. "Film (2019)".</summary>
    public static int? YearFromTitle(string name)
    {
        var m = Regex.Match(name, @"[\(\[]\s*(19\d{2}|20\d{2})\s*[\)\]]");
        return m.Success ? int.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture) : null;
    }

    /// <summary>Removes the series name and "S01E02" codes providers put in episode titles.</summary>
    public static string? TidyEpisodeTitle(string? title, string? seriesName)
    {
        if (string.IsNullOrWhiteSpace(title)) return null;
        var t = title.Trim();
        if (!string.IsNullOrWhiteSpace(seriesName) && t.StartsWith(seriesName.Trim(), StringComparison.OrdinalIgnoreCase))
            t = t[seriesName.Trim().Length..];
        t = Regex.Replace(t, @"^[\s\-–—:|·.]*", "");
        t = Regex.Replace(t, @"^S\d{1,3}\s*[\.\-]?\s*E\d{1,4}", "", RegexOptions.IgnoreCase);
        t = Regex.Replace(t, @"^[\s\-–—:|·.]*", "").Trim();
        if (t.Length == 0 || Regex.IsMatch(t, @"^(Episode|Ep\.?|E)\s*\d+$", RegexOptions.IgnoreCase) || Regex.IsMatch(t, @"^\d+$"))
            return null;
        return t;
    }

    public static string FormatTime(double seconds)
    {
        if (double.IsNaN(seconds) || seconds < 0) seconds = 0;
        var t = TimeSpan.FromSeconds(Math.Floor(seconds));
        return t.TotalHours >= 1 ? $"{(int)t.TotalHours}:{t.Minutes:00}:{t.Seconds:00}" : $"{t.Minutes}:{t.Seconds:00}";
    }

    public static string FormatBytes(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";
        double v = bytes;
        string[] units = { "KB", "MB", "GB", "TB" };
        var i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < units.Length - 1);
        return v >= 100 ? $"{v:0} {units[i]}" : $"{v:0.0} {units[i]}";
    }

    /// <summary>"1 hr 5 min", "12 min", "40 sec".</summary>
    public static string FormatDuration(double seconds)
    {
        var s = (long)Math.Max(1, seconds);
        var ar = L10n.IsArabic;
        if (s >= 3600) return ar ? $"{s / 3600} س {s % 3600 / 60} د" : $"{s / 3600} hr {s % 3600 / 60} min";
        if (s >= 60) return ar ? $"{s / 60} د" : $"{s / 60} min";
        return ar ? $"{s} ث" : $"{s} sec";
    }

    public static string? Str(this System.Text.Json.JsonElement e, string name)
    {
        if (e.ValueKind != System.Text.Json.JsonValueKind.Object || !e.TryGetProperty(name, out var v)) return null;
        var s = v.ValueKind switch
        {
            System.Text.Json.JsonValueKind.String => v.GetString(),
            System.Text.Json.JsonValueKind.Number => v.GetRawText(),
            System.Text.Json.JsonValueKind.True => "1",
            System.Text.Json.JsonValueKind.False => "0",
            _ => null,
        };
        s = s?.Trim();
        return string.IsNullOrEmpty(s) || s == "null" ? null : s;
    }

    public static long? Long(this string? s) => long.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out var v) ? v : null;
    public static int? Int(this string? s) => int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out var v) ? v : null;
    public static double? Double(this string? s) => double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out var v) ? v : null;
}
