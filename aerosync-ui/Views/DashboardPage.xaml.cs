using AeroSync.UI.Services;
using AeroSync.UI.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace AeroSync.UI.Views;

public sealed partial class DashboardPage : Page
{
    private readonly DispatcherTimer refreshTimer = new();
    private bool hasLoaded;

    public DashboardViewModel ViewModel { get; }

    public DashboardPage(AeroSyncApiClient apiClient)
    {
        ViewModel = new DashboardViewModel(apiClient);
        DataContext = ViewModel;
        InitializeComponent();
        Loaded += DashboardPage_Loaded;
        Unloaded += DashboardPage_Unloaded;
        refreshTimer.Interval = TimeSpan.FromSeconds(3);
        refreshTimer.Tick += RefreshTimer_Tick;
    }

    private async void DashboardPage_Loaded(object sender, RoutedEventArgs e)
    {
        if (!hasLoaded)
        {
            hasLoaded = true;
            await ViewModel.RefreshAsync();
        }
        refreshTimer.Start();
    }

    private void DashboardPage_Unloaded(object sender, RoutedEventArgs e)
    {
        refreshTimer.Stop();
    }

    private async void RefreshTimer_Tick(object? sender, object e)
    {
        await ViewModel.RefreshAsync();
    }

}
