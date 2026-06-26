using AeroSync.UI.Models;
using AeroSync.UI.Services;
using AeroSync.UI.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using CommunityToolkit.WinUI.UI.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using System.Diagnostics;
using System.IO;

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

    private void JobsGrid_RightTapped(object sender, RightTappedRoutedEventArgs e)
    {
        if (e.OriginalSource is not FrameworkElement element)
        {
            return;
        }

        var row = FindVisualParent<DataGridRow>(element);
        if (row?.DataContext is not SyncJobSummaryResponse job || string.IsNullOrWhiteSpace(job.StoredPath))
        {
            return;
        }

        var flyout = new MenuFlyout();
        var revealItem = new MenuFlyoutItem
        {
            Text = "Mở trong File Explorer",
            Icon = new SymbolIcon(Symbol.Folder)
        };
        revealItem.Click += (_, _) => RevealInExplorer(job.StoredPath);
        flyout.Items.Add(revealItem);

        flyout.ShowAt(element, e.GetPosition(element));
    }

    private static void RevealInExplorer(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                Process.Start("explorer.exe", $"/select, \"{path}\"");
            }
            else if (Directory.Exists(path))
            {
                Process.Start("explorer.exe", path);
            }
            else if (Directory.Exists(Path.GetDirectoryName(path)))
            {
                // File doesn't exist (may have been archived/moved),
                // but its parent directory does — open the parent folder
                Process.Start("explorer.exe", Path.GetDirectoryName(path)!);
            }
        }
        catch
        {
            // Silently ignore if Explorer can't be opened
        }
    }

    private static T? FindVisualParent<T>(DependencyObject child) where T : DependencyObject
    {
        var parent = VisualTreeHelper.GetParent(child);
        while (parent is not null)
        {
            if (parent is T match)
            {
                return match;
            }
            parent = VisualTreeHelper.GetParent(parent);
        }
        return null;
    }
}
