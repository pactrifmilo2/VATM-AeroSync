using AeroSync.UI.Models;
using AeroSync.UI.Services;
using AeroSync.UI.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace AeroSync.UI.Views;

public sealed partial class DashboardPage : Page
{
    private readonly AeroSyncApiClient apiClient;
    private readonly DispatcherTimer refreshTimer = new();

    public DashboardViewModel ViewModel { get; }

    public DashboardPage(AeroSyncApiClient apiClient)
    {
        this.apiClient = apiClient;
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
        await ViewModel.RefreshAsync();
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

    private async void JobsGrid_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (JobsGrid.SelectedItem is not SyncJobSummaryResponse job)
        {
            return;
        }

        JobsGrid.SelectedItem = null;
        var dialog = new JobDetailsDialog(apiClient, job.Id)
        {
            XamlRoot = XamlRoot
        };
        await dialog.ShowAsync();
        await ViewModel.RefreshAsync();
    }
}
