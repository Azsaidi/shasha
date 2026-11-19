using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using Shasha.Core;
using static Shasha.Core.Loc;

namespace Shasha.UI;

/// <summary>Sign in with Xtream Codes details, or edit the open playlist.</summary>
public sealed class LoginPage : Page
{
    readonly Account? _editing;
    readonly TextBox _name, _server, _user, _passwordText;
    readonly PasswordBox _password = new();
    readonly Button _show;
    readonly Button _submit;
    readonly TextBlock _error = Kit.Text("", wrap: true, color: Kit.Brush("Danger"));
    bool _busy;

    public LoginPage(Account? editing)
    {
        _editing = editing;
        _name = Kit.Field(L("playlist_name"), editing?.Name ?? "");
        _server = Kit.Field(L("server_placeholder"), editing?.Server ?? "");
        _user = Kit.Field(L("username"), editing?.Username ?? "");
        _passwordText = Kit.Field(L("password"), editing?.Password ?? "");
        _passwordText.Visibility = Visibility.Collapsed;
        _password.Password = editing?.Password ?? "";
        _password.Tag = L("password");
        _server.TextChanged += (_, _) => ParseM3U();
        _show = Kit.IconButton("\uE7B3", L("show_password"), TogglePassword);
        foreach (var box in new Control[] { _name, _server, _user, _passwordText, _password })
            box.KeyDown += (_, e) => { if (e.Key == Key.Enter) Submit(); };

        var passwordRow = new Grid();
        passwordRow.ColumnDefinitions.Add(new ColumnDefinition());
        passwordRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        passwordRow.Children.Add(_password);
        passwordRow.Children.Add(_passwordText);
        _show.Margin = new Thickness(6, 0, 0, 0);
        Grid.SetColumn(_show, 1);
        passwordRow.Children.Add(_show);

        _submit = Kit.Button(editing == null ? L("log_in") : L("save"), Submit, style: "AccentButton");
        _submit.HorizontalAlignment = HorizontalAlignment.Stretch;

        var form = Kit.Column(10,
            Label(L("playlist_name")), _name,
            Label(L("server_field")), _server,
            Label(L("username")), _user,
            Label(L("password")), passwordRow,
            _error,
            _submit,
            Kit.Text(editing == null ? L("login_tip") : L("edit_tip"), "Caption", wrap: true));
        _error.Visibility = Visibility.Collapsed;

        var col = new StackPanel { Width = 440, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 40, 0, 40) };
        if (editing == null)
        {
            var logo = new Image { Source = new BitmapImage(new Uri("pack://application:,,,/Assets/logo.png")), Width = 72, Height = 72 };
            col.Children.Add(logo);
            var title = Kit.Text(L("app_name"), "H1");
            title.HorizontalAlignment = HorizontalAlignment.Center;
            title.Margin = new Thickness(0, 12, 0, 4);
            col.Children.Add(title);
            var sub = Kit.Text(L("login_subtitle"), wrap: true, color: Kit.Brush("Subtle"));
            sub.TextAlignment = TextAlignment.Center;
            sub.Margin = new Thickness(0, 0, 0, 20);
            col.Children.Add(sub);

            // Saved playlists to go back to.
            var saved = Store.Accounts;
            if (saved.Count > 0 && Session.Account == null)
            {
                col.Children.Add(Kit.Text(L("choose_account"), "H2"));
                foreach (var acc in saved)
                {
                    var row = Kit.SettingRow("\uE77B", acc.Name, acc.Username, () => Session.Open(acc));
                    col.Children.Add(row);
                }
                var add = Kit.Text(L("add_account"), "H2");
                add.Margin = new Thickness(0, 24, 0, 8);
                col.Children.Add(add);
            }
        }
        if (string.IsNullOrEmpty(_name.Text)) _name.Text = L("default_playlist");
        col.Children.Add(Kit.Card(form, padding: 24));
        Content = new ScrollViewer { Content = col, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        Loaded += (_, _) => (_editing == null ? _server : _name).Focus();
    }

    public override string Title => _editing == null ? L("add_account") : L("edit_account");

    static TextBlock Label(string text)
    {
        var t = Kit.Text(text, "Caption", size: 13);
        t.Margin = new Thickness(2, 4, 0, -4);
        return t;
    }

    string Password => _passwordText.Visibility == Visibility.Visible ? _passwordText.Text : _password.Password;

    void TogglePassword()
    {
        if (_passwordText.Visibility == Visibility.Visible)
        {
            _password.Password = _passwordText.Text;
            _passwordText.Visibility = Visibility.Collapsed;
            _password.Visibility = Visibility.Visible;
            _show.Content = "\uE7B3";
            _show.ToolTip = L("show_password");
        }
        else
        {
            _passwordText.Text = _password.Password;
            _password.Visibility = Visibility.Collapsed;
            _passwordText.Visibility = Visibility.Visible;
            _show.Content = "\uED1A";
            _show.ToolTip = L("hide_password");
        }
    }

    /// <summary>A pasted M3U / player_api link fills in the username and password too.</summary>
    void ParseM3U()
    {
        var text = _server.Text.Trim();
        if (!text.Contains("username=", StringComparison.OrdinalIgnoreCase)) return;
        if (!Uri.TryCreate(text, UriKind.Absolute, out var uri)) return;
        var query = System.Web.HttpUtility.ParseQueryString(uri.Query);
        var user = query["username"];
        var pass = query["password"];
        if (string.IsNullOrEmpty(user)) return;
        _user.Text = user;
        if (!string.IsNullOrEmpty(pass))
        {
            _password.Password = pass;
            _passwordText.Text = pass;
        }
        _server.Text = uri.GetLeftPart(UriPartial.Authority);
        _server.CaretIndex = _server.Text.Length;
    }

    async void Submit()
    {
        if (_busy) return;
        _error.Visibility = Visibility.Collapsed;
        var server = XtreamApi.NormalizeServer(_server.Text.Trim());
        if (server == null || _user.Text.Trim().Length == 0 || Password.Length == 0)
        {
            ShowError(server == null ? L("err_invalid_address") : L("err_credentials"));
            return;
        }
        var account = new Account
        {
            Id = _editing?.Id ?? Guid.NewGuid().ToString(),
            Name = _name.Text.Trim().Length > 0 ? _name.Text.Trim() : L("default_playlist"),
            Server = server,
            Username = _user.Text.Trim(),
            Password = Password,
        };
        _busy = true;
        _submit.IsEnabled = false;
        _submit.Content = L("checking");
        try
        {
            var auth = await new XtreamApi(account).Login();
            Store.Upsert(account);
            if (_editing != null)
            {
                Session.AccountEdited(account, auth);
                Toast.Show(L("saved_changes"));
                MainWindow.Current?.Back();
            }
            else
            {
                Session.Open(account, auth);
                // First sign-in: fetch everything now so browsing is instant.
                Session.RefreshAll();
            }
        }
        catch (Exception e)
        {
            ShowError(Http.Friendly(e));
        }
        finally
        {
            _busy = false;
            _submit.IsEnabled = true;
            _submit.Content = _editing == null ? L("log_in") : L("save");
        }
    }

    void ShowError(string text)
    {
        _error.Text = text;
        _error.Visibility = Visibility.Visible;
    }
}
