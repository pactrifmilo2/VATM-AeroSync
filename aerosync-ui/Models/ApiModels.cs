using CommunityToolkit.Mvvm.ComponentModel;

namespace AeroSync.UI.Models;

public sealed class DashboardStatsResponse
{
    public long TotalJobs { get; set; }
    public Dictionary<string, long> StatusCounts { get; set; } = [];
    public long JobsLast24Hours { get; set; }
    public long FailedLast24Hours { get; set; }
    public long ActiveAlerts { get; set; }
}

public sealed class SyncJobSummaryResponse
{
    public long Id { get; set; }
    public string FileHash { get; set; } = "";
    public string OriginalFileName { get; set; } = "";
    public string Status { get; set; } = "";
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public string FileName => !string.IsNullOrWhiteSpace(OriginalFileName)
        ? OriginalFileName
        : FileHash.Length > 12 ? $"{FileHash[..12]}..." : FileHash;
    public string TimeLabel => UpdatedAt.ToString("HH:mm:ss");
}

public sealed class SyncJobDetailResponse
{
    public long Id { get; set; }
    public string FileHash { get; set; } = "";
    public string Status { get; set; } = "";
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public List<FileRecordResponse> FileRecords { get; set; } = [];
    public List<RowValidationError> RowErrors { get; set; } = [];
    public string? LatestLogMessage { get; set; }
}

public sealed class FileRecordResponse
{
    public long Id { get; set; }
    public string SourceType { get; set; } = "";
    public string OriginalFileName { get; set; } = "";
    public string StoredPath { get; set; } = "";
    public DateTime CreatedAt { get; set; }
    public string? Sender { get; set; }
    public string? Subject { get; set; }
}

public sealed class RowValidationError
{
    public int RowNumber { get; set; }
    public string Field { get; set; } = "";
    public string Code { get; set; } = "";
    public string Message { get; set; } = "";
    public string? Value { get; set; }
}

public sealed class AuditLogResponse
{
    public long Id { get; set; }
    public long? SyncJobId { get; set; }
    public string Action { get; set; } = "";
    public string ResultStatus { get; set; } = "";
    public DateTime Timestamp { get; set; }
    public long? DurationMs { get; set; }
    public string? SourceType { get; set; }
    public string DisplayLine => $"[{Timestamp:HH:mm:ss}] [{ResultStatus}] {Action}";
}

public sealed class RuntimeConfigModel : ObservableObject
{
    private long schedulerFixedDelayMs = 300_000;
    private int maxFilesPerCycle = 100;
    private List<string> blacklistSenders = [];
    private string incomingDir = "";
    private string processedDir = "";
    private string errorDir = "";
    private string emailHost = "";
    private int emailPort = 993;
    private string emailProtocol = "IMAP SSL/TLS";
    private string emailUser = "";
    private string emailPassword = "";
    private string retryMode = "Exponential";
    private int maxSizePerFileMb = 10;
    private bool autoQuarantine = true;
    private bool skipDuplicateIdempotency = true;
    private bool sendZaloAlert;

    public long SchedulerFixedDelayMs
    {
        get => schedulerFixedDelayMs;
        set => SetProperty(ref schedulerFixedDelayMs, value);
    }

    public int MaxFilesPerCycle
    {
        get => maxFilesPerCycle;
        set => SetProperty(ref maxFilesPerCycle, value);
    }

    public List<string> BlacklistSenders
    {
        get => blacklistSenders;
        set => SetProperty(ref blacklistSenders, value);
    }

    public string IncomingDir
    {
        get => incomingDir;
        set => SetProperty(ref incomingDir, value);
    }

    public string ProcessedDir
    {
        get => processedDir;
        set => SetProperty(ref processedDir, value);
    }

    public string ErrorDir
    {
        get => errorDir;
        set => SetProperty(ref errorDir, value);
    }

    public string EmailHost
    {
        get => emailHost;
        set => SetProperty(ref emailHost, value);
    }

    public int EmailPort
    {
        get => emailPort;
        set => SetProperty(ref emailPort, value);
    }

    public string EmailProtocol
    {
        get => emailProtocol;
        set => SetProperty(ref emailProtocol, value);
    }

    public string EmailUser
    {
        get => emailUser;
        set => SetProperty(ref emailUser, value);
    }

    public string EmailPassword
    {
        get => emailPassword;
        set => SetProperty(ref emailPassword, value);
    }

    public string RetryMode
    {
        get => retryMode;
        set => SetProperty(ref retryMode, value);
    }

    public int MaxSizePerFileMb
    {
        get => maxSizePerFileMb;
        set => SetProperty(ref maxSizePerFileMb, value);
    }

    public bool AutoQuarantine
    {
        get => autoQuarantine;
        set => SetProperty(ref autoQuarantine, value);
    }

    public bool SkipDuplicateIdempotency
    {
        get => skipDuplicateIdempotency;
        set => SetProperty(ref skipDuplicateIdempotency, value);
    }

    public bool SendZaloAlert
    {
        get => sendZaloAlert;
        set => SetProperty(ref sendZaloAlert, value);
    }
}
