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

    public MainWindow(AeroSyncApiClient apiClient, string initialTag = "dashboard")
    {
        this.apiClient = apiClient;
        InitializeComponent();
        AppWindow.Title = "HTSLB AeroSync";
        AppWindow.Resize(new SizeInt32(1180, 760));
        RootNav.SelectedItem = RootNav.MenuItems.OfType<NavigationViewItem>()
            .First(item => (string)item.Tag == initialTag);
        Navigate(initialTag);
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
            "email-resend" => new EmailResendPage(apiClient),
            _ => new PlaceholderPage((RootNav.SelectedItem as NavigationViewItem)?.Content?.ToString() ?? "AeroSync")
        };
    }
}
