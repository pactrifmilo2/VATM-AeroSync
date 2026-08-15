using AeroSync.UI.Services;
using AeroSync.UI.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace AeroSync.UI.Views;

public sealed partial class ConfigPage : Page
{
    private bool hasLoaded;

    public ConfigViewModel ViewModel { get; }

    public ConfigPage(AeroSyncApiClient apiClient)
    {
        ViewModel = new ConfigViewModel(apiClient);
        DataContext = ViewModel;
        InitializeComponent();
        Loaded += ConfigPage_Loaded;
    }

    private async void ConfigPage_Loaded(object sender, RoutedEventArgs e)
    {
        if (hasLoaded)
        {
            return;
        }

        hasLoaded = true;
        await ViewModel.LoadAsync();
        EmailPasswordBox.Password = ViewModel.Config.EmailPassword;
    }

    private void EmailPasswordBox_PasswordChanged(object sender, RoutedEventArgs e)
    {
        ViewModel.Config.EmailPassword = ((PasswordBox)sender).Password;
    }
}
