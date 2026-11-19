using System.Globalization;
using System.Reflection;
using System.Text.Json;
using System.Windows;

namespace Shasha.Core;

/// <summary>English (UK) and Arabic text. Arabic also turns the layout right-to-left.</summary>
public static class L10n
{
    public const string System = "system";
    public const string English = "en";
    public const string Arabic = "ar";

    static Dictionary<string, string> _strings = new();
    static Dictionary<string, string> _fallback = new();

    public static string Code { get; private set; } = "en";
    public static bool IsArabic => Code == "ar";
    public static FlowDirection Direction => IsArabic ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
    public static CultureInfo Culture => IsArabic ? new CultureInfo("ar-AE") : new CultureInfo("en-GB");

    public static void Load(string setting)
    {
        Code = setting switch
        {
            Arabic => "ar",
            English => "en",
            _ => CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "ar" ? "ar" : "en",
        };
        _fallback = Read("en");
        _strings = Code == "en" ? _fallback : Read(Code);
    }

    static Dictionary<string, string> Read(string code)
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream($"Shasha.Strings.{code}.json");
        if (stream == null) return new();
        return JsonSerializer.Deserialize<Dictionary<string, string>>(stream) ?? new();
    }

    public static string Get(string key) =>
        _strings.TryGetValue(key, out var s) ? s : _fallback.TryGetValue(key, out var f) ? f : key;

    /// <summary>Plural-aware text (Arabic has zero/one/two/few/many/other forms).</summary>
    public static string Plural(string key, long count, params object[] args)
    {
        var form = PluralForm(count);
        var text = _strings.TryGetValue($"{key}#{form}", out var s) ? s
            : _strings.TryGetValue($"{key}#other", out var o) ? o
            : _fallback.TryGetValue($"{key}#{(count == 1 ? "one" : "other")}", out var f) ? f : key;
        return string.Format(Culture, text, new object[] { count }.Concat(args).ToArray());
    }

    static string PluralForm(long n)
    {
        if (!IsArabic) return n == 1 ? "one" : "other";
        var mod = n % 100;
        return n switch
        {
            0 => "zero",
            1 => "one",
            2 => "two",
            _ when mod is >= 3 and <= 10 => "few",
            _ when mod is >= 11 and <= 99 => "many",
            _ => "other",
        };
    }
}

public static class Loc
{
    public static string L(string key) => L10n.Get(key);
    public static string L(string key, params object[] args) => string.Format(L10n.Culture, L10n.Get(key), args);
}
