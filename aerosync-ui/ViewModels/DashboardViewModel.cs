using System.Collections.ObjectModel;
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
    private long activeAlerts;
    private bool isLoading;
    private string statusMessage = "Sẵn sàng";
    private string incomingNotice = "Chưa có thông báo mới từ thư mục Incoming.";

    public ObservableCollection<SyncJobSummaryResponse> Jobs { get; } = [];
    public ObservableCollection<AuditLogResponse> Logs { get; } = [];

    public DashboardViewModel(AeroSyncApiClient apiClient)
    {
        this.apiClient = apiClient;
    }

    public long SuccessCount
    {
        get => successCount;
        set => SetProperty(ref successCount, value);
    }

    public long ErrorCount
    {
        get => errorCount;
        set => SetProperty(ref errorCount, value);
    }

    public long WarnCount
    {
        get => warnCount;
        set => SetProperty(ref warnCount, value);
    }

    public long SkippedCount
    {
        get => skippedCount;
        set => SetProperty(ref skippedCount, value);
    }

    public long ProcessingCount
    {
        get => processingCount;
        set => SetProperty(ref processingCount, value);
    }

    public long TotalJobs
    {
        get => totalJobs;
        set => SetProperty(ref totalJobs, value);
    }

    public long ActiveAlerts
    {
        get => activeAlerts;
        set => SetProperty(ref activeAlerts, value);
    }

    public bool IsLoading
    {
        get => isLoading;
        set => SetProperty(ref isLoading, value);
    }

    public string StatusMessage
    {
        get => statusMessage;
        set => SetProperty(ref statusMessage, value);
    }

    public string IncomingNotice
    {
        get => incomingNotice;
        set => SetProperty(ref incomingNotice, value);
    }

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
            var statsTask = apiClient.GetDashboardStatsAsync();
            var jobsTask = apiClient.GetJobsAsync();
            var auditLogsTask = apiClient.GetAuditLogsAsync();
            await Task.WhenAll(statsTask, jobsTask, auditLogsTask);

            var stats = await statsTask;
            SuccessCount = Count(stats, "SUCCESS");
            ErrorCount = Count(stats, "FAILED");
            WarnCount = Count(stats, "QUARANTINED");
            SkippedCount = Count(stats, "SKIPPED");
            ProcessingCount = Count(stats, "PENDING") + Count(stats, "IN_PROGRESS");
            TotalJobs = stats.TotalJobs > 0 ? stats.TotalJobs : SuccessCount + ErrorCount + WarnCount + SkippedCount + ProcessingCount;
            ActiveAlerts = stats.ActiveAlerts;

            Replace(Jobs, await jobsTask);
            var auditLogs = await auditLogsTask;
            Replace(Logs, auditLogs.Take(20));
            var latestIncoming = auditLogs.FirstOrDefault(log =>
                log.Action.StartsWith("INCOMING_", StringComparison.OrdinalIgnoreCase));
            if (latestIncoming is not null)
            {
                IncomingNotice = $"{latestIncoming.Timestamp:dd/MM/yyyy HH:mm:ss} - "
                    + (string.IsNullOrWhiteSpace(latestIncoming.Message)
                        ? VietnameseErrorMessage.TranslateAction(latestIncoming.Action)
                        : latestIncoming.Message);
            }
            StatusMessage = $"Đang theo dõi {TotalJobs} tác vụ · {ProcessingCount} tác vụ đang xử lý · cập nhật lúc {DateTime.Now:HH:mm:ss}";
        }
        catch (Exception ex)
        {
            StatusMessage = VietnameseErrorMessage.FromException(ex, "Tải dữ liệu bảng điều khiển");
        }
        finally
        {
            IsLoading = false;
        }
    }

    private static long Count(DashboardStatsResponse stats, string status)
    {
        return stats.StatusCounts.TryGetValue(status, out var count) ? count : 0;
    }

    private static void Replace<T>(ObservableCollection<T> target, IEnumerable<T> source)
    {
        target.Clear();
        foreach (var item in source)
        {
            target.Add(item);
        }
    }
}
