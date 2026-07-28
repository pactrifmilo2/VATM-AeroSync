using AeroSync.UI.Services;
using Microsoft.UI.Xaml;

namespace AeroSync.UI;

public partial class App : Application
{
    private Window? window;

    public App()
    {
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var apiClient = new AeroSyncApiClient(new HttpClient
        {
            BaseAddress = new Uri("http://localhost:8081")
        });

        window = new MainWindow(apiClient);
        window.Activate();
    }
}
