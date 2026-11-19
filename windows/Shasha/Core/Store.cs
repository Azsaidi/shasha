using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Shasha.Core;

public sealed class Settings
{
    public string Language { get; set; } = L10n.System;
    public string? CurrentAccountId { get; set; }
    /// <summary>"ts", "m3u8" or "auto" (VLC plays both; TS is usually the most reliable).</summary>
    public string LiveFormat { get; set; } = "ts";
    public bool HideAdult { get; set; } = true;
    public int AutoRefreshDays { get; set; } = 7;
    public Dictionary<string, string> Sorts { get; set; } = new();
    /// <summary>"Clearer sound" level per series/film/channel.</summary>
    public Dictionary<string, int> Clarity { get; set; } = new();
    public List<string> Searches { get; set; } = new();
    public string? DownloadsFolder { get; set; }
    public int Volume { get; set; } = 100;
    public double WindowWidth { get; set; } = 1280;
    public double WindowHeight { get; set; } = 800;
    public bool WindowMaximized { get; set; }
}

/// <summary>Favourites, watch history and refresh date for one account.</summary>
public sealed class AccountData
{
    public List<StreamItem> Favourites { get; set; } = new();
    public List<WatchEntry> History { get; set; } = new();
    public DateTime? LastRefresh { get; set; }
}

/// <summary>Everything saved on disk, under %LOCALAPPDATA%\Shasha.</summary>
public static class Store
{
    public static readonly string Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Shasha");
    static readonly string SettingsFile = Path.Combine(Root, "settings.json");
    static readonly string AccountsFile = Path.Combine(Root, "accounts.json");
    static readonly string AccountsDir = Path.Combine(Root, "accounts");
    public static readonly string CacheDir = Path.Combine(Root, "cache");

    public static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = false,
        Converters = { new JsonStringEnumConverter() },
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public static Settings Settings { get; private set; } = new();

    public static void Load()
    {
        Directory.CreateDirectory(Root);
        Directory.CreateDirectory(AccountsDir);
        Settings = Read<Settings>(SettingsFile) ?? new Settings();
    }

    static T? Read<T>(string path)
    {
        try { return File.Exists(path) ? JsonSerializer.Deserialize<T>(File.ReadAllText(path), Json) : default; }
        catch { return default; }
    }

    static readonly object WriteLock = new();

    static void Write<T>(string path, T value)
    {
        try
        {
            var text = JsonSerializer.Serialize(value, Json);
            lock (WriteLock)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                var tmp = path + ".tmp";
                File.WriteAllText(tmp, text);
                File.Move(tmp, path, true);
            }
        }
        catch { /* Saving is best-effort. */ }
    }

    public static void SaveSettings() => Write(SettingsFile, Settings);

    public static string DownloadsFolder
    {
        get
        {
            var folder = Settings.DownloadsFolder;
            if (string.IsNullOrWhiteSpace(folder))
                folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyVideos), "Shasha");
            Directory.CreateDirectory(folder);
            return folder;
        }
        set
        {
            Settings.DownloadsFolder = value;
            SaveSettings();
        }
    }

    // Accounts

    public static List<Account> Accounts
    {
        get => Read<List<Account>>(AccountsFile) ?? new List<Account>();
        set => Write(AccountsFile, value);
    }

    public static void Upsert(Account account)
    {
        var list = Accounts;
        var i = list.FindIndex(a => a.Id == account.Id);
        if (i >= 0) list[i] = account; else list.Add(account);
        Accounts = list;
    }

    public static void Remove(Account account)
    {
        Accounts = Accounts.Where(a => a.Id != account.Id).ToList();
        if (Settings.CurrentAccountId == account.Id)
        {
            Settings.CurrentAccountId = null;
            SaveSettings();
        }
        try { File.Delete(Path.Combine(AccountsDir, account.Id + ".json")); } catch { }
        CatalogCache.Clear(account.Id);
    }

    public static AccountData LoadAccountData(string id) => Read<AccountData>(Path.Combine(AccountsDir, id + ".json")) ?? new AccountData();

    public static void SaveAccountData(string id, AccountData data)
    {
        var copy = new AccountData { Favourites = data.Favourites.ToList(), History = data.History.Select(h => h.Copy()).ToList(), LastRefresh = data.LastRefresh };
        Task.Run(() => Write(Path.Combine(AccountsDir, id + ".json"), copy));
    }

    // Settings helpers

    public static string Sort(ContentType type) => Settings.Sorts.TryGetValue(type.ToString(), out var s) ? s : "default";

    public static void SetSort(ContentType type, string value)
    {
        Settings.Sorts[type.ToString()] = value;
        SaveSettings();
    }

    public static int Clarity(string key) => Settings.Clarity.TryGetValue(key, out var v) ? v : 0;

    public static void SetClarity(string key, int level)
    {
        if (level == 0) Settings.Clarity.Remove(key); else Settings.Clarity[key] = level;
        SaveSettings();
    }
}

/// <summary>Channel, film and series lists saved on disk so the app opens instantly.</summary>
public static class CatalogCache
{
    static string File(string accountId, ContentType type) => Path.Combine(Store.CacheDir, accountId, type.ToString().ToLowerInvariant() + ".json");

    public static Library? Load(string accountId, ContentType type)
    {
        try
        {
            var path = File(accountId, type);
            if (!System.IO.File.Exists(path)) return null;
            using var stream = System.IO.File.OpenRead(path);
            return JsonSerializer.Deserialize<Library>(stream, Store.Json);
        }
        catch { return null; }
    }

    public static void Save(string accountId, ContentType type, Library library)
    {
        try
        {
            var path = File(accountId, type);
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            using var stream = System.IO.File.Create(path + ".tmp");
            JsonSerializer.Serialize(stream, library, Store.Json);
            stream.Close();
            System.IO.File.Move(path + ".tmp", path, true);
        }
        catch { }
    }

    public static void Clear(string accountId)
    {
        try { Directory.Delete(Path.Combine(Store.CacheDir, accountId), true); } catch { }
    }
}
