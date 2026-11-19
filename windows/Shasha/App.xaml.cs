using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Threading;
using Shasha.Core;
using Shasha.UI;

namespace Shasha;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        DispatcherUnhandledException += OnUnhandled;
        // Errors on other threads would close the app without a trace: at least write them down.
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log(e.ExceptionObject?.ToString() ?? "unknown");
        TaskScheduler.UnobservedTaskException += (_, e) => { Log(e.Exception.ToString()); e.SetObserved(); };
        Store.Load();
        L10n.Load(Store.Settings.Language);
        FrameworkElement.LanguageProperty.OverrideMetadata(typeof(FrameworkElement),
            new FrameworkPropertyMetadata(System.Windows.Markup.XmlLanguage.GetLanguage(L10n.Culture.IetfLanguageTag)));
        LibVLCSharp.Shared.Core.Initialize();
        Downloads.Start();
        Session.Start();
        Playback.Present = (items, index, fromStart) => PlayerWindow.Open(items, index, fromStart);
        var main = new MainWindow();
        MainWindow = main;
        main.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        Store.SaveSettings();
        base.OnExit(e);
    }

    static void OnUnhandled(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        // Keep going: a single failed action shouldn't close the app.
        Log(e.Exception.ToString());
        Toast.Show(Loc.L("err_unexpected"));
        e.Handled = true;
    }

    public static void Log(string text)
    {
        try
        {
            System.IO.Directory.CreateDirectory(Store.Root);
            System.IO.File.AppendAllText(System.IO.Path.Combine(Store.Root, "errors.log"), $"{DateTime.Now:u} {text}\n\n");
        }
        catch { }
    }

    /// <summary>Dark title bar (and Mica on Windows 11) for a window.</summary>
    public static void StyleWindow(Window window)
    {
        window.SourceInitialized += (_, _) =>
        {
            var hwnd = new WindowInteropHelper(window).Handle;
            var on = 1;
            DwmSetWindowAttribute(hwnd, 20, ref on, sizeof(int));
            var caption = 0x0020130B; // the app background as COLORREF (0x00BBGGRR)
            DwmSetWindowAttribute(hwnd, 35, ref caption, sizeof(int));
        };
        window.FlowDirection = L10n.Direction;
        window.FontFamily = (System.Windows.Media.FontFamily)Current.Resources["UiFont"];
        window.Foreground = (System.Windows.Media.Brush)Current.Resources["Fg"];
    }

    [DllImport("dwmapi.dll")]
    static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
}
