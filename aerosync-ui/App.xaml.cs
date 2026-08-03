using AeroSync.UI.Services;
using Microsoft.UI.Xaml;

namespace AeroSync.UI;

public partial class App : Application
{
    private Window? window;

    public App()
    {
        InitializeComponent();
        UnhandledException += App_UnhandledException;
        TaskScheduler.UnobservedTaskException += (_, args) =>
        {
            WriteUiError(args.Exception);
            args.SetObserved();
        };
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var apiClient = new AeroSyncApiClient(new HttpClient
        {
            BaseAddress = new Uri("http://localhost:8081")
        });

        var openEmailResend = args.Arguments.Contains("--email-resend", StringComparison.OrdinalIgnoreCase)
                              || Environment.GetCommandLineArgs().Any(argument =>
                                  argument.Equals("--email-resend", StringComparison.OrdinalIgnoreCase));
        var initialTag = openEmailResend
            ? "email-resend"
            : "dashboard";
        window = new MainWindow(apiClient, initialTag);
        window.Activate();
    }

    private void App_UnhandledException(object sender, Microsoft.UI.Xaml.UnhandledExceptionEventArgs args)
    {
        WriteUiError(args.Exception);
        args.Handled = true;
    }

    private static void WriteUiError(Exception exception)
    {
        try
        {
            var logDirectory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "VATM-AeroSync");
            Directory.CreateDirectory(logDirectory);
            File.AppendAllText(
                Path.Combine(logDirectory, "ui-errors.log"),
                $"[{DateTimeOffset.Now:O}] {exception}\n\n");
        }
        catch
        {
            // Không để lỗi ghi log làm ứng dụng đóng.
        }
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
