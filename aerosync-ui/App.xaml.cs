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
            BaseAddress = ResolveApiBaseAddress(),
            // SMTP delivery can take longer than the default HttpClient timeout.
            // Keep the resend dialog open long enough to receive the API result.
            Timeout = TimeSpan.FromMinutes(3)
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
            // KhÃ´ng Ä‘á»ƒ lá»—i ghi log lÃ m á»©ng dá»¥ng Ä‘Ã³ng.
        }
    }

    private static Uri ResolveApiBaseAddress()
    {
        const string defaultApiUrl = "http://localhost:8081";
        var config = ReadDotEnvValues();
        var configuredApiUrl = Environment.GetEnvironmentVariable("AEROSYNC_API_URL");

        if (string.IsNullOrWhiteSpace(configuredApiUrl)
            && config.TryGetValue("AEROSYNC_API_URL", out var fileApiUrl))
        {
            configuredApiUrl = fileApiUrl;
        }

        if (string.IsNullOrWhiteSpace(configuredApiUrl)
            && config.TryGetValue("SERVER_PORT", out var serverPort)
            && int.TryParse(serverPort, out var parsedPort)
            && parsedPort is >= 1 and <= 65_535)
        {
            configuredApiUrl = $"http://localhost:{parsedPort}";
        }

        var apiUrl = string.IsNullOrWhiteSpace(configuredApiUrl)
            ? defaultApiUrl
            : configuredApiUrl.Trim();

        if (!Uri.TryCreate(apiUrl, UriKind.Absolute, out var baseAddress)
            || (baseAddress.Scheme != Uri.UriSchemeHttp
                && baseAddress.Scheme != Uri.UriSchemeHttps))
        {
            throw new InvalidOperationException(
                $"AEROSYNC_API_URL phai la dia chi HTTP(S) tuyet doi; gia tri hien tai la '{apiUrl}'.");
        }

        return baseAddress;
    }

    private static Dictionary<string, string> ReadDotEnvValues()
    {
        var configuredPath = Environment.GetEnvironmentVariable("AEROSYNC_CONFIG_FILE");
        var candidates = new[]
        {
            configuredPath,
            Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "config", ".env")),
            Path.Combine(Environment.CurrentDirectory, "config", ".env"),
            Path.Combine(Environment.CurrentDirectory, ".env"),
            Path.Combine(AppContext.BaseDirectory, ".env")
        };

        foreach (var path in candidates
                     .Where(path => !string.IsNullOrWhiteSpace(path))
                     .Select(path => Path.GetFullPath(path!))
                     .Distinct(StringComparer.OrdinalIgnoreCase))
        {
            if (!File.Exists(path))
            {
                continue;
            }

            var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var line in File.ReadLines(path))
            {
                var trimmed = line.Trim();
                if (trimmed.Length == 0 || trimmed.StartsWith('#'))
                {
                    continue;
                }

                var separator = trimmed.IndexOf('=');
                if (separator <= 0)
                {
                    continue;
                }

                var key = trimmed[..separator].Trim();
                var value = trimmed[(separator + 1)..].Trim();
                if (value.Length >= 2 && value[0] == value[^1] && value[0] is '\'' or '"')
                {
                    value = value[1..^1];
                }
                values[key] = value;
            }

            return values;
        }

        return new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
    }
}
