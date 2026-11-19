using System.Text;
using System.Windows.Threading;
using Microsoft.Diagnostics.Runtime;

namespace Shasha.Core;

/// <summary>
/// Notices when the window stops responding (the UI thread is stuck) and writes what every thread
/// was doing into trace.log, so a freeze can be found and fixed.
/// </summary>
public static class Watchdog
{
    static long _lastPong = Environment.TickCount64;
    static int _reports;

    public static void Start(Dispatcher ui)
    {
        var thread = new Thread(() =>
        {
            var reported = false;
            while (true)
            {
                Thread.Sleep(1000);
                ui.BeginInvoke(() => Interlocked.Exchange(ref _lastPong, Environment.TickCount64));
                var stuck = Environment.TickCount64 - Interlocked.Read(ref _lastPong);
                if (stuck > 5000 && !reported && _reports < 3)
                {
                    reported = true;
                    _reports++;
                    Trace.Write($"FREEZE: the window hasn't responded for {stuck / 1000} s");
                    Trace.Write(Stacks());
                }
                if (stuck < 2000) reported = false;
            }
        }) { IsBackground = true, Name = "Shasha watchdog", Priority = ThreadPriority.AboveNormal };
        thread.Start();
    }

    static string Stacks()
    {
        try
        {
            using var target = DataTarget.CreateSnapshotAndAttach(Environment.ProcessId);
            using var runtime = target.ClrVersions[0].CreateRuntime();
            var sb = new StringBuilder();
            foreach (var t in runtime.Threads)
            {
                if (!t.IsAlive) continue;
                var frames = t.EnumerateStackTrace().Take(t.ManagedThreadId == 1 ? 60 : 12).ToList();
                if (frames.Count == 0) continue;
                sb.Append("thread ").Append(t.ManagedThreadId).Append('\n');
                foreach (var f in frames)
                {
                    var text = f.Method?.Signature ?? f.FrameName ?? f.Kind.ToString();
                    sb.Append("    ").Append(text).Append('\n');
                }
            }
            return sb.ToString();
        }
        catch (Exception e)
        {
            return "couldn't read the threads: " + e.Message;
        }
    }
}
