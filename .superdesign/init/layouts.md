# Shared Layouts

## Main application shell

Source: `aerosync-ui/MainWindow.xaml`

Left-side WinUI `NavigationView` with VATM branding and a content frame. The requested resend feature should be added as a first-class navigation destination inside this shell.

```xml
<Window
    x:Class="AeroSync.UI.MainWindow"
    xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
    xmlns:local="using:AeroSync.UI"
    Title="HTSLB AeroSync">
    <Grid>
        <NavigationView
            x:Name="RootNav"
            IsBackButtonVisible="Collapsed"
            IsSettingsVisible="False"
            PaneDisplayMode="Left"
            SelectionChanged="RootNav_SelectionChanged">
            <NavigationView.PaneHeader>
                <StackPanel Margin="12,16,12,20">
                    <TextBlock Text="VATM" FontSize="22" FontWeight="Bold" Foreground="#0B4EA2" />
                    <TextBlock Text="HTSLB ADMIN" FontSize="12" Foreground="#526174" />
                </StackPanel>
            </NavigationView.PaneHeader>
            <NavigationView.MenuItems>
                <NavigationViewItem Content="Tổng quan" Icon="Home" Tag="overview" />
                <NavigationViewItem Content="Giám sát đồng bộ" Icon="View" Tag="dashboard" />
                <NavigationViewItem Content="Lịch sử đồng bộ" Icon="Clock" Tag="history" />
                <NavigationViewItem Content="Nguồn dữ liệu" Icon="Folder" Tag="sources" />
                <NavigationViewItem Content="Cấu hình" Icon="Setting" Tag="config" />
                <NavigationViewItem Content="Báo cáo" Icon="Document" Tag="reports" />
                <NavigationViewItem Content="Cảnh báo" Icon="Important" Tag="alerts" />
                <NavigationViewItem Content="Hệ thống" Icon="Contact" Tag="system" />
            </NavigationView.MenuItems>
            <Frame x:Name="ContentFrame" />
        </NavigationView>
    </Grid>
</Window>
```

Source: `aerosync-ui/MainWindow.xaml.cs`

```csharp
using AeroSync.UI.Services;
using AeroSync.UI.Views;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Graphics;

namespace AeroSync.UI;

public sealed partial class MainWindow : Window
{
    private readonly AeroSyncApiClient apiClient;

    public MainWindow(AeroSyncApiClient apiClient)
    {
        this.apiClient = apiClient;
        InitializeComponent();
        AppWindow.Title = "HTSLB AeroSync";
        AppWindow.Resize(new SizeInt32(1180, 760));
        RootNav.SelectedItem = RootNav.MenuItems.OfType<NavigationViewItem>()
            .First(item => (string)item.Tag == "dashboard");
        Navigate("dashboard");
    }

    private void RootNav_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.SelectedItem is NavigationViewItem item && item.Tag is string tag)
        {
            Navigate(tag);
        }
    }

    private void Navigate(string tag)
    {
        ContentFrame.Content = tag switch
        {
            "config" => new ConfigPage(apiClient),
            "dashboard" => new DashboardPage(apiClient),
            _ => new PlaceholderPage((RootNav.SelectedItem as NavigationViewItem)?.Content?.ToString() ?? "AeroSync")
        };
    }
}
```
