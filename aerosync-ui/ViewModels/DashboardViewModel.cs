using AeroSync.UI.Models;
using AeroSync.UI.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace AeroSync.UI.ViewModels;

public sealed partial class DashboardViewModel : ObservableObject
{
    private readonly AeroSyncApiClient apiClient;
    private long successCount;
    private long errorCount;
    private long warnCount;
    private long skippedCount;
    private long processingCount;
    private long totalJobs;
    private bool isLoading;
    private string statusMessage = "S\u1EB5n s\u00E0ng";

    public DashboardViewModel(AeroSyncApiClient apiClient)
    {
        this.apiClient = apiClient;
    }

    public string Title => "Gi\u00E1m s\u00E1t \u0111\u1ED3ng b\u1ED9";
    public string Subtitle => "Theo d\u00F5i s\u1ED1 li\u1EC7u t\u1ED5ng h\u1EE3p, kh\u00F4ng t\u1EA3i danh s\u00E1ch b\u1EA3n ghi.";
    public string RefreshLabel => "L\u00E0m m\u1EDBi";
    public string SuccessLabel => "Th\u00E0nh c\u00F4ng";
    public string ErrorLabel => "Th\u1EA5t b\u1EA1i";
    public string WarningLabel => "C\u00E1ch ly";
    public string SkippedLabel => "B\u1ECF qua";
    public string ProcessingLabel => "\u0110ang x\u1EED l\u00FD";
    public string TotalLabel => "T\u1ED5ng s\u1ED1";

    public long SuccessCount { get => successCount; set => SetProperty(ref successCount, value); }
    public long ErrorCount { get => errorCount; set => SetProperty(ref errorCount, value); }
    public long WarnCount { get => warnCount; set => SetProperty(ref warnCount, value); }
    public long SkippedCount { get => skippedCount; set => SetProperty(ref skippedCount, value); }
    public long ProcessingCount { get => processingCount; set => SetProperty(ref processingCount, value); }
    public long TotalJobs { get => totalJobs; set => SetProperty(ref totalJobs, value); }
    public bool IsLoading { get => isLoading; set => SetProperty(ref isLoading, value); }
    public string StatusMessage { get => statusMessage; set => SetProperty(ref statusMessage, value); }

    [RelayCommand]
    public async Task RefreshAsync()
    {
        if (IsLoading)
        {
            return;
        }

        try
        {
            IsLoading = true;
            ApplyStats(await apiClient.GetDashboardStatsAsync());
            StatusMessage = $"C\u1EADp nh\u1EADt l\u00FAc {DateTime.Now:HH:mm:ss}";
        }
        catch (Exception ex)
        {
            StatusMessage = VietnameseErrorMessage.FromException(ex, "T\u1EA3i s\u1ED1 li\u1EC7u t\u1ED5ng h\u1EE3p");
        }
        finally
        {
            IsLoading = false;
        }
    }

    private void ApplyStats(DashboardStatsResponse stats)
    {
        SuccessCount = Count(stats, "SUCCESS");
        ErrorCount = Count(stats, "FAILED");
        WarnCount = Count(stats, "QUARANTINED");
        SkippedCount = Count(stats, "SKIPPED");
        ProcessingCount = Count(stats, "PENDING") + Count(stats, "IN_PROGRESS");
        TotalJobs = stats.TotalJobs;
    }

    private static long Count(DashboardStatsResponse stats, string status)
    {
        return stats.StatusCounts.TryGetValue(status, out var count) ? count : 0;
    }
}