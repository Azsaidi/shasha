using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using Shasha.Core;
using static Shasha.Core.Loc;

namespace Shasha.UI;

/// <summary>Small helpers so pages can be built in code and stay readable.</summary>
public static class Kit
{
    public static T Res<T>(string key) => (T)Application.Current.Resources[key];
    public static Brush Brush(string key) => Res<Brush>(key);
    public static Style Style(string key) => Res<Style>(key);
    public static DataTemplate Template(string key) => Res<DataTemplate>(key);

    public static TextBlock Text(string text, string? style = null, double? size = null, Brush? color = null, bool wrap = false)
    {
        var t = new TextBlock { Text = text };
        if (style != null) t.Style = Style(style);
        if (size != null) t.FontSize = size.Value;
        if (color != null) t.Foreground = color;
        if (wrap)
        {
            t.TextWrapping = TextWrapping.Wrap;
            t.TextTrimming = TextTrimming.None;
        }
        return t;
    }

    public static TextBlock Glyph(string glyph, double size = 16, Brush? color = null)
    {
        var t = new TextBlock { Text = glyph, Style = Style("Icon"), FontSize = size };
        if (color != null) t.Foreground = color;
        return t;
    }

    /// <summary>A pill button with an optional icon in front of the label.</summary>
    public static Button Button(string label, Action onClick, string? glyph = null, string? style = null)
    {
        var b = new Button();
        if (style != null) b.Style = Style(style);
        b.Content = glyph == null ? label : Row(4, Glyph(glyph, 14, null), new TextBlock { Text = label, Margin = new Thickness(8, 0, 0, 0) });
        b.Click += (_, _) => onClick();
        return b;
    }

    public static Button IconButton(string glyph, string tooltip, Action onClick, double size = 16)
    {
        var b = new Button { Style = Style("IconButton"), Content = glyph, ToolTip = tooltip, FontSize = size };
        AutomationProps(b, tooltip);
        b.Click += (_, _) => onClick();
        return b;
    }

    static void AutomationProps(DependencyObject d, string name) => System.Windows.Automation.AutomationProperties.SetName(d, name);

    public static StackPanel Row(double gap, params UIElement[] children)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal };
        foreach (var c in children)
        {
            if (p.Children.Count > 0 && c is FrameworkElement fe && fe.Margin == default) fe.Margin = new Thickness(gap, 0, 0, 0);
            p.Children.Add(c);
        }
        return p;
    }

    public static StackPanel Column(double gap, params UIElement[] children)
    {
        var p = new StackPanel();
        foreach (var c in children)
        {
            if (p.Children.Count > 0 && c is FrameworkElement fe && fe.Margin == default) fe.Margin = new Thickness(0, gap, 0, 0);
            p.Children.Add(c);
        }
        return p;
    }

    public static Border Card(UIElement child, double padding = 16, double radius = 14) => new()
    {
        Background = Brush("Card"),
        CornerRadius = new CornerRadius(radius),
        Padding = new Thickness(padding),
        Child = child,
    };

    public static TextBox Field(string placeholder, string text = "")
    {
        var t = new TextBox { Text = text, Tag = placeholder };
        System.Windows.Automation.AutomationProperties.SetName(t, placeholder);
        return t;
    }

    /// <summary>On/off switch with a title and an explanation under it.</summary>
    public static FrameworkElement Switch(string title, string? detail, bool value, Action<bool> changed)
    {
        var box = new CheckBox { Style = Style("Switch"), IsChecked = value, VerticalAlignment = VerticalAlignment.Center };
        box.Checked += (_, _) => changed(true);
        box.Unchecked += (_, _) => changed(false);
        var text = Column(2, Text(title, size: 14));
        if (detail != null) text.Children.Add(Text(detail, "Caption", wrap: true));
        var grid = new Grid { Margin = new Thickness(0, 6, 0, 6), Cursor = Cursors.Hand };
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(text);
        Grid.SetColumn(box, 1);
        grid.Children.Add(box);
        grid.MouseLeftButtonUp += (_, e) => { if (e.OriginalSource is not CheckBox) box.IsChecked = !box.IsChecked; };
        return grid;
    }

    /// <summary>A setting with a title, value text and a click action.</summary>
    public static Button SettingRow(string glyph, string title, string? value, Action onClick)
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(36) });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(Glyph(glyph, 16, Brush("Accent")));
        var t = Text(title, size: 14);
        t.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(t, 1);
        grid.Children.Add(t);
        if (value != null)
        {
            var v = Text(value, "Caption", size: 13);
            v.VerticalAlignment = VerticalAlignment.Center;
            v.MaxWidth = 320;
            Grid.SetColumn(v, 2);
            grid.Children.Add(v);
        }
        var b = new Button { Style = Style("CardButton"), Content = new Border { Padding = new Thickness(6, 12, 6, 12), Background = Brushes.Transparent, Child = grid } };
        b.Click += (_, _) => onClick();
        return b;
    }

    public static ContextMenu Menu(params (string glyph, string label, Action action)?[] items)
    {
        var menu = new ContextMenu { FlowDirection = L10n.Direction };
        foreach (var item in items)
        {
            if (item == null)
            {
                if (menu.Items.Count > 0 && menu.Items[^1] is not Separator) menu.Items.Add(new Separator());
                continue;
            }
            var (glyph, label, action) = item.Value;
            var mi = new MenuItem { Header = label, Tag = glyph };
            mi.Click += (_, _) => action();
            menu.Items.Add(mi);
        }
        return menu;
    }

    public static void ShowMenu(FrameworkElement target, ContextMenu menu)
    {
        menu.PlacementTarget = target;
        menu.Placement = PlacementMode.MousePoint;
        menu.IsOpen = true;
    }

    /// <summary>A simple message box in the app's style. Returns true for the main button.</summary>
    public static bool Confirm(string title, string body, string ok, bool danger = false, Window? owner = null)
    {
        var dlg = new Window
        {
            Title = title,
            Width = 440,
            SizeToContent = SizeToContent.Height,
            ResizeMode = ResizeMode.NoResize,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Owner = owner ?? Application.Current.MainWindow,
            Background = Brush("Panel"),
            ShowInTaskbar = false,
        };
        App.StyleWindow(dlg);
        var result = false;
        var okButton = Button(ok, () => { result = true; dlg.Close(); }, style: "AccentButton");
        if (danger)
        {
            okButton.Background = Brush("Danger");
            okButton.Foreground = Brushes.White;
        }
        okButton.IsDefault = true;
        var cancel = Button(L("cancel"), () => dlg.Close());
        cancel.IsCancel = true;
        var buttons = Row(10, cancel, okButton);
        buttons.HorizontalAlignment = HorizontalAlignment.Right;
        dlg.Content = new Border
        {
            Padding = new Thickness(24),
            Child = Column(18, Text(title, "H2"), Text(body, wrap: true, color: Brush("Subtle")), buttons),
        };
        dlg.ShowDialog();
        return result;
    }

    public static void Info(string title, string body, Window? owner = null)
    {
        var dlg = new Window
        {
            Title = title,
            Width = 440,
            SizeToContent = SizeToContent.Height,
            ResizeMode = ResizeMode.NoResize,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Owner = owner ?? Application.Current.MainWindow,
            Background = Brush("Panel"),
            ShowInTaskbar = false,
        };
        App.StyleWindow(dlg);
        var ok = Button(L("done"), () => dlg.Close(), style: "AccentButton");
        ok.IsDefault = true;
        ok.IsCancel = true;
        ok.HorizontalAlignment = HorizontalAlignment.Right;
        dlg.Content = new Border { Padding = new Thickness(24), Child = Column(18, Text(title, "H2"), Text(body, wrap: true, color: Brush("Subtle")), ok) };
        dlg.ShowDialog();
    }

    /// <summary>Finds the item behind a click inside a list (the clicked Button's DataContext) and its Tag.</summary>
    public static (object? item, string? tag) Clicked(RoutedEventArgs e) =>
        e.OriginalSource is FrameworkElement fe
            ? (fe.DataContext, (fe as Button)?.Tag as string ?? FindTag(fe))
            : (null, null);

    static string? FindTag(DependencyObject d)
    {
        while (d != null)
        {
            if (d is Button b) return b.Tag as string;
            d = VisualTreeHelper.GetParent(d);
        }
        return null;
    }

    /// <summary>A horizontally scrolling shelf of cards (Home rows).</summary>
    public static ListBox Shelf(string template, System.Collections.IEnumerable items)
    {
        var list = new ListBox
        {
            ItemsSource = items,
            ItemTemplate = Template(template),
            ItemContainerStyle = Style("BareItem"),
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Focusable = false,
        };
        ScrollViewer.SetHorizontalScrollBarVisibility(list, ScrollBarVisibility.Auto);
        ScrollViewer.SetVerticalScrollBarVisibility(list, ScrollBarVisibility.Disabled);
        ScrollViewer.SetCanContentScroll(list, true);
        VirtualizingPanel.SetScrollUnit(list, ScrollUnit.Pixel);
        list.ItemsPanel = Res<ItemsPanelTemplate>("HorizontalPanel");
        list.HorizontalAlignment = HorizontalAlignment.Stretch;
        list.HorizontalContentAlignment = HorizontalAlignment.Left;
        // Let the mouse wheel scroll the page, not the shelf (shift+wheel scrolls sideways).
        list.PreviewMouseWheel += (s, e) =>
        {
            if (Keyboard.Modifiers == ModifierKeys.Shift)
            {
                var sv = FindChild<ScrollViewer>(list);
                if (sv != null) sv.ScrollToHorizontalOffset(sv.HorizontalOffset - e.Delta);
                e.Handled = true;
                return;
            }
            e.Handled = true;
            var parent = ((FrameworkElement)s).Parent as UIElement;
            parent?.RaiseEvent(new MouseWheelEventArgs(e.MouseDevice, e.Timestamp, e.Delta) { RoutedEvent = UIElement.MouseWheelEvent, Source = s });
        };
        return list;
    }

    /// <summary>A vertical, virtualised list (episodes, channels, downloads).</summary>
    public static ListBox List(string template, System.Collections.IEnumerable? items = null)
    {
        var list = new ListBox
        {
            ItemsSource = items,
            ItemTemplate = Template(template),
            ItemContainerStyle = Style("BareItem"),
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
        };
        ScrollViewer.SetHorizontalScrollBarVisibility(list, ScrollBarVisibility.Disabled);
        ScrollViewer.SetCanContentScroll(list, true);
        VirtualizingPanel.SetIsVirtualizing(list, true);
        VirtualizingPanel.SetVirtualizationMode(list, VirtualizationMode.Recycling);
        VirtualizingPanel.SetScrollUnit(list, ScrollUnit.Pixel);
        return list;
    }

    public static T? FindChild<T>(DependencyObject parent) where T : DependencyObject
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is T t) return t;
            var found = FindChild<T>(child);
            if (found != null) return found;
        }
        return null;
    }

    /// <summary>A spinner-free loading / empty / error message in the middle of a page.</summary>
    public static FrameworkElement Message(string glyph, string title, string? body = null, string? action = null, Action? onAction = null)
    {
        var col = Column(10, Glyph(glyph, 40, Brush("Subtle")), Text(title, "H2"));
        ((TextBlock)col.Children[1]).HorizontalAlignment = HorizontalAlignment.Center;
        if (body != null)
        {
            var b = Text(body, wrap: true, color: Brush("Subtle"));
            b.TextAlignment = TextAlignment.Center;
            b.MaxWidth = 460;
            col.Children.Add(b);
        }
        if (action != null && onAction != null)
        {
            var btn = Button(action, onAction, style: "AccentButton");
            btn.HorizontalAlignment = HorizontalAlignment.Center;
            btn.Margin = new Thickness(0, 10, 0, 0);
            col.Children.Add(btn);
        }
        col.HorizontalAlignment = HorizontalAlignment.Center;
        col.VerticalAlignment = VerticalAlignment.Center;
        col.Margin = new Thickness(24);
        return col;
    }

    /// <summary>Splits items into rows of `perRow` for a virtualised grid.</summary>
    public static List<RowVM> Rows(IReadOnlyList<CardVM> cards, int perRow)
    {
        var rows = new List<RowVM>();
        for (var i = 0; i < cards.Count; i += perRow)
            rows.Add(new RowVM { Cells = cards.Skip(i).Take(perRow).ToList() });
        return rows;
    }
}

/// <summary>A page shown in the main window's content area.</summary>
public abstract class Page : UserControl
{
    /// <summary>Called when the page becomes visible again (e.g. after Back).</summary>
    public virtual void Shown() { }
    /// <summary>Called when the page is removed for good.</summary>
    public virtual void Closed() { }
    public virtual string Title => "";
    /// <summary>Text box to focus with Ctrl+F.</summary>
    public virtual TextBox? SearchBox => null;
}
