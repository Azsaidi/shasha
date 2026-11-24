using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using Shasha.Core;
using static Shasha.Core.Loc;

namespace Shasha.UI;

public sealed class SettingsPage : Page
{
    readonly StackPanel _col = new() { Margin = new Thickness(32, 28, 32, 40), MaxWidth = 820, HorizontalAlignment = HorizontalAlignment.Left };

    public SettingsPage()
    {
        Content = new ScrollViewer { Content = _col, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        Session.AccountChanged += Build;
        HomeLayout.Changed += Build;
        Build();
    }

    public override void Closed()
    {
        Session.AccountChanged -= Build;
        HomeLayout.Changed -= Build;
    }

    StackPanel Section(string title)
    {
        var header = Kit.Text(title, "H2");
        header.Margin = new Thickness(0, 26, 0, 10);
        _col.Children.Add(header);
        var body = new StackPanel();
        _col.Children.Add(Kit.Card(body, padding: 10));
        return body;
    }

    void Build()
    {
        _col.Children.Clear();
        _col.Children.Add(Kit.Text(L("tab_settings"), "H1"));
        var acc = Session.Account;
        if (acc == null) return;

        // Account
        var a = Section(acc.Name);
        a.Children.Add(Info("\uE774", L("server"), acc.Server));
        a.Children.Add(Info("\uE77B", L("username"), acc.Username));
        var auth = Session.Auth;
        if (auth?.Status != null) a.Children.Add(Info("\uE930", L("status"), auth.Status));
        a.Children.Add(Info("\uE787", L("expires"), Session.ExpiryDate?.ToString("d MMMM yyyy", L10n.Culture) ?? L("never")));
        if (auth?.MaxConnections != null)
            a.Children.Add(Info("\uE8CE", L("connections"), L("connections_in_use", auth.ActiveConnections ?? "0", auth.MaxConnections)));
        a.Children.Add(Kit.SettingRow("\uE70F", L("edit_account"), null, () => MainWindow.Push(new LoginPage(acc))));
        a.Children.Add(Kit.SettingRow("\uE748", L("switch_account"), null, SwitchAccount));
        var remove = Kit.SettingRow("\uE74D", L("remove_account"), null, () =>
        {
            if (!Kit.Confirm(L("remove_account_title", acc.Name), L("remove_account_body"), L("remove"), danger: true)) return;
            Store.Remove(acc);
            var next = Store.Accounts.FirstOrDefault();
            if (next != null) Session.Open(next); else Session.Close();
        });
        remove.Foreground = Kit.Brush("Danger");
        a.Children.Add(remove);

        // Content
        var c = Section(L("library"));
        c.Children.Add(Kit.SettingRow("\uE72C", L("refresh_all"), null, () => Session.RefreshAll()));
        c.Children.Add(Choice("\uE823", L("auto_refresh"), L("auto_refresh_desc"),
            new[] { (0, L("refresh_off")), (3, L("refresh_3_days")), (7, L("refresh_week")) },
            Store.Settings.AutoRefreshDays, v => { Store.Settings.AutoRefreshDays = v; Store.SaveSettings(); }));
        c.Children.Add(Choice("\uE7F4", L("live_format"), L("live_format_hint"),
            new[] { ("ts", "TS"), ("m3u8", "HLS"), ("auto", L("format_auto")) },
            Store.Settings.LiveFormat, v => { Store.Settings.LiveFormat = v; Store.SaveSettings(); }));
        c.Children.Add(Padded(Kit.Switch(L("hide_adult"), L("hide_adult_desc"), Session.HideAdult, Session.SetHideAdult)));

        // Language
        var l = Section(L("language"));
        l.Children.Add(Choice("\uE774", L("language"), null,
            new[] { (L10n.System, L("lang_system")), (L10n.English, L("lang_en")), (L10n.Arabic, L("lang_ar")) },
            Store.Settings.Language, v =>
            {
                if (v == Store.Settings.Language) return;
                Store.Settings.Language = v;
                Store.SaveSettings();
                Restart();
            }));

        // Home rows
        var r = Section(L("home_rows"));
        var hint = Kit.Text(L("home_rows_hint"), "Caption", wrap: true);
        hint.Margin = new Thickness(10, 4, 10, 6);
        r.Children.Add(hint);
        var rows = HomeLayout.Rows;
        for (var i = 0; i < rows.Count; i++) r.Children.Add(HomeRowLine(rows[i], i, rows.Count));
        r.Children.Add(Kit.SettingRow("\uE72C", L("reset_order"), null, HomeLayout.Reset));

        // Downloads
        var d = Section(L("tab_downloads"));
        d.Children.Add(Kit.SettingRow("\uE8B7", L("downloads_folder"), Store.DownloadsFolder, ChangeFolder));
        d.Children.Add(Kit.SettingRow("\uE838", L("open_folder"), null, () =>
        {
            try { Process.Start(new ProcessStartInfo { FileName = Store.DownloadsFolder, UseShellExecute = true }); } catch { }
        }));

        // History
        var h = Section(L("history_section"));
        h.Children.Add(Kit.SettingRow("\uE81C", L("clear_watch_history"), null, () =>
        {
            if (!Kit.Confirm(L("clear_watch_history"), L("clear_watch_history_body"), L("clear"), danger: true)) return;
            Session.ClearHistory();
            Toast.Show(L("history_cleared"));
        }));
        h.Children.Add(Kit.SettingRow("\uE721", L("clear_search_history"), null, () =>
        {
            if (!Kit.Confirm(L("clear_search_history"), L("clear_search_history_body"), L("clear"), danger: true)) return;
            Session.ClearSearches();
            Toast.Show(L("searches_cleared"));
        }));

        // About
        var u = Section(L("check_updates"));
        u.Children.Add(Kit.SettingRow("\uE895", L("check_updates"), L("version_line", Updater.CurrentVersion), CheckUpdates));
        var keys = Kit.Text(L("keyboard_hint"), "Caption", wrap: true);
        keys.Margin = new Thickness(10, 8, 10, 8);
        u.Children.Add(keys);
    }

    /// <summary>One Home row with Move up / Move down buttons.</summary>
    static FrameworkElement HomeRowLine(HomeRow row, int index, int count)
    {
        var grid = new Grid { Margin = new Thickness(6, 4, 6, 4) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(36) });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var number = Kit.Text((index + 1).ToString(L10n.Culture), "Caption", size: 14);
        number.VerticalAlignment = VerticalAlignment.Center;
        grid.Children.Add(number);
        var t = Kit.Text(L(HomeLayout.TitleKey(row)), size: 14);
        t.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(t, 1);
        grid.Children.Add(t);
        var up = Kit.IconButton("\uE70E", L("move_up"), () => HomeLayout.Move(row, -1));
        up.IsEnabled = index > 0;
        var down = Kit.IconButton("\uE70D", L("move_down"), () => HomeLayout.Move(row, 1));
        down.IsEnabled = index < count - 1;
        var buttons = Kit.Row(4, up, down);
        Grid.SetColumn(buttons, 2);
        grid.Children.Add(buttons);
        return grid;
    }

    static FrameworkElement Padded(FrameworkElement e)
    {
        e.Margin = new Thickness(10, 8, 10, 8);
        return e;
    }

    static FrameworkElement Info(string glyph, string title, string value)
    {
        var grid = new Grid { Margin = new Thickness(6, 10, 6, 10) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(36) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(160) });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.Children.Add(Kit.Glyph(glyph, 16, Kit.Brush("Subtle")));
        var t = Kit.Text(title, "Caption", size: 14);
        Grid.SetColumn(t, 1);
        grid.Children.Add(t);
        var plain = new TextBlock { Text = value, FontSize = 14 };
        Grid.SetColumn(plain, 2);
        grid.Children.Add(plain);
        return grid;
    }

    /// <summary>A setting with a few options shown as chips.</summary>
    static FrameworkElement Choice<T>(string glyph, string title, string? detail, (T value, string label)[] options, T current, Action<T> changed)
    {
        var chips = new WrapPanel { Margin = new Thickness(36, 8, 0, 0) };
        var group = Guid.NewGuid().ToString();
        foreach (var (value, label) in options)
        {
            var chip = new RadioButton { Style = Kit.Style("Chip"), GroupName = group, Content = label, IsChecked = Equals(value, current), Margin = new Thickness(0, 0, 8, 4) };
            chip.Checked += (_, _) => changed(value);
            chips.Children.Add(chip);
        }
        var head = Kit.Row(0, new Border { Width = 36, Child = Kit.Glyph(glyph, 16, Kit.Brush("Accent")) }, Kit.Text(title, size: 14));
        var col = Kit.Column(2, head);
        if (detail != null)
        {
            var dt = Kit.Text(detail, "Caption", wrap: true);
            dt.Margin = new Thickness(36, 2, 0, 0);
            col.Children.Add(dt);
        }
        col.Children.Add(chips);
        col.Margin = new Thickness(6, 10, 6, 10);
        return col;
    }

    void SwitchAccount()
    {
        var items = Store.Accounts
            .Select(acc => ((string, string, Action)?)(acc.Id == Session.Account?.Id ? "\uE73E" : "\uE77B", acc.Name, () =>
            {
                if (acc.Id != Session.Account?.Id) Session.Open(acc);
            }))
            .ToList();
        items.Add(null);
        items.Add(("\uE710", L("add_account"), () => MainWindow.Push(new LoginPage(null))));
        var menu = Kit.Menu(items.ToArray());
        menu.Placement = System.Windows.Controls.Primitives.PlacementMode.MousePoint;
        menu.IsOpen = true;
    }

    void ChangeFolder()
    {
        var dlg = new Microsoft.Win32.OpenFolderDialog { InitialDirectory = Store.DownloadsFolder, Title = L("downloads_folder") };
        if (dlg.ShowDialog(Window.GetWindow(this)) != true) return;
        Store.DownloadsFolder = dlg.FolderName;
        Build();
    }

    static void Restart()
    {
        try
        {
            var exe = Environment.ProcessPath;
            if (exe != null) Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true });
        }
        catch { }
        Application.Current.Shutdown();
    }

    async void CheckUpdates()
    {
        Toast.Show(L("checking_updates"));
        try
        {
            var release = await Updater.Check();
            if (release == null) Toast.Show(L("up_to_date", Updater.CurrentVersion));
            else OfferUpdate(release);
        }
        catch (Exception e)
        {
            Toast.Show(L("update_failed", Http.Friendly(e)));
        }
    }

    static bool _offering;

    /// <summary>Asks to install a newer version, then downloads it with progress.</summary>
    public static async void OfferUpdate(Updater.Release release)
    {
        if (_offering) return;
        _offering = true;
        try
        {
            if (!Kit.Confirm(L("update_available"), L("update_available_body_win", release.Version, Updater.CurrentVersion), L("update_install"))) return;
            var progress = new Progress<double>(p => Toast.Show(L("update_downloading", (int)(p * 100))));
            await Updater.Install(release, progress, CancellationToken.None);
        }
        catch (Exception e)
        {
            Toast.Show(L("update_failed", Http.Friendly(e)));
        }
        finally { _offering = false; }
    }
}
