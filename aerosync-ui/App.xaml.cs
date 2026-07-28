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

    private static Uri ResolveApiBaseAddress()
    {
        const string defaultApiUrl = "http://localhost:8081";
        var configuredApiUrl = Environment.GetEnvironmentVariable("AEROSYNC_API_URL");
        var apiUrl = string.IsNullOrWhiteSpace(configuredApiUrl)
            ? defaultApiUrl
            : configuredApiUrl.Trim();

        if (!Uri.TryCreate(apiUrl, UriKind.Absolute, out var baseAddress)
            || (baseAddress.Scheme != Uri.UriSchemeHttp
                && baseAddress.Scheme != Uri.UriSchemeHttps))
        {
            throw new InvalidOperationException(
                $"AEROSYNC_API_URL must be an absolute HTTP(S) URL; received '{apiUrl}'.");
        }

        return baseAddress;
    }
}
