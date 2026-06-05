using AeroSync.UI.Services;
using AeroSync.UI.ViewModels;
using Microsoft.UI.Xaml.Controls;

namespace AeroSync.UI.Views;

public sealed partial class JobDetailsDialog : ContentDialog
{
    public JobDetailsViewModel ViewModel { get; }

    public JobDetailsDialog(AeroSyncApiClient apiClient, long jobId)
    {
        ViewModel = new JobDetailsViewModel(apiClient, jobId);
        DataContext = ViewModel;
        InitializeComponent();
        Loaded += JobDetailsDialog_Loaded;
    }

    private async void JobDetailsDialog_Loaded(object sender, Microsoft.UI.Xaml.RoutedEventArgs e)
    {
        await ViewModel.LoadAsync();
    }
}
