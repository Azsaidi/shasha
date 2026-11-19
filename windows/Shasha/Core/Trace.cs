using System.IO;

namespace Shasha.Core;

/// <summary>
/// A short running log (trace.log next to the settings) of what the app was doing, so a crash
/// that leaves no error behind can still be traced to its last step. The previous run is kept
/// as trace.old.log.
/// </summary>
public static class Trace
{
    static readonly object Lock = new();
    static string? _path;
    static long _written;

    public static void Start()
    {
        try
        {
            Directory.CreateDirectory(Store.Root);
            _path = Path.Combine(Store.Root, "trace.log");
            var old = Path.Combine(Store.Root, "trace.old.log");
            if (File.Exists(_path)) File.Copy(_path, old, overwrite: true);
            File.WriteAllText(_path, "");
        }
        catch { _path = null; }
    }

    public static void Write(string text)
    {
        if (_path == null || _written > 2_000_000) return;
        try
        {
            var line = $"{DateTime.Now:HH:mm:ss.fff} [{Environment.CurrentManagedThreadId}] {text}\n";
            lock (Lock)
            {
                File.AppendAllText(_path, line);
                _written += line.Length;
            }
        }
        catch { }
    }
}
