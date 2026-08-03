using System.Collections.ObjectModel;
using AeroSync.UI.Models;
using AeroSync.UI.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace AeroSync.UI.ViewModels;

public sealed partial class EmailResendViewModel : ObservableObject
{
    private readonly AeroSyncApiClient apiClient;
    private string senderFilter = "";
    private string selectedOverallStatus = "Tất cả";
    private DateTimeOffset? fromDate;
    private DateTimeOffset? toDate;
    private bool isLoading;
    private string statusMessage = "Nhập địa chỉ email rồi bấm Tìm kiếm.";
    private long totalEmails;
    private long actionableEmails;
    private long completedEmails;
    private long blockedEmails;

    public ObservableCollection<EmailGroupRow> Emails { get; } = [];
    public IReadOnlyList<string> OverallStatuses { get; } =
        ["Tất cả", "FAILED", "QUARANTINED", "PROCESSING", "SUCCESS", "SKIPPED"];

    public EmailResendViewModel(AeroSyncApiClient apiClient)
    {
        this.apiClient = apiClient;
    }

    public string SenderFilter
    {
        get => senderFilter;
        set => SetProperty(ref senderFilter, value);
    }

    public string SelectedOverallStatus
    {
        get => selectedOverallStatus;
        set => SetProperty(ref selectedOverallStatus, value);
    }

    public DateTimeOffset? FromDate
    {
        get => fromDate;
        set => SetProperty(ref fromDate, value);
    }

    public DateTimeOffset? ToDate
    {
        get => toDate;
        set => SetProperty(ref toDate, value);
    }

    public bool IsLoading
    {
        get => isLoading;
        set
        {
            if (SetProperty(ref isLoading, value))
            {
                OnPropertyChanged(nameof(IsNotLoading));
            }
        }
    }

    public bool IsNotLoading => !IsLoading;

    public string StatusMessage
    {
        get => statusMessage;
        set => SetProperty(ref statusMessage, value);
    }

    public long TotalEmails { get => totalEmails; set => SetProperty(ref totalEmails, value); }
    public long ActionableEmails { get => actionableEmails; set => SetProperty(ref actionableEmails, value); }
    public long CompletedEmails { get => completedEmails; set => SetProperty(ref completedEmails, value); }
    public long BlockedEmails { get => blockedEmails; set => SetProperty(ref blockedEmails, value); }

    [RelayCommand]
    public async Task SearchAsync()
    {
        if (IsLoading)
        {
            return;
        }

        try
        {
            IsLoading = true;
            StatusMessage = "Đang tải và nhóm email theo messageId...";
            var rows = await apiClient.GetEmailReportsAsync(SenderFilter);
            var groups = rows
                .Where(row => !string.IsNullOrWhiteSpace(row.MessageId))
                .GroupBy(row => row.MessageId, StringComparer.OrdinalIgnoreCase)
                .Select(group => new EmailGroupRow(group.ToList()))
                .Where(MatchesFilters)
                .OrderBy(group => group.StatusSortOrder)
                .ThenByDescending(group => group.ReceivedAt)
                .ToList();

            Replace(Emails, groups);
            TotalEmails = groups.Count;
            ActionableEmails = groups.LongCount(group => group.IsActionable);
            CompletedEmails = groups.LongCount(group => group.OverallStatus == "SUCCESS");
            BlockedEmails = groups.LongCount(group => group.OverallStatus == "PROCESSING" || !group.CanResend);
            StatusMessage = string.IsNullOrWhiteSpace(SenderFilter)
                ? $"Đang hiển thị {groups.Count} email từ 100 bản ghi mới nhất · nhập người gửi để tải đầy đủ."
                : $"Đã tìm thấy đầy đủ {groups.Count} email phù hợp · email cần xử lý được xếp lên trước.";
        }
        catch (Exception ex)
        {
            StatusMessage = VietnameseErrorMessage.FromException(ex, "Tải danh sách email");
        }
        finally
        {
            IsLoading = false;
        }
    }

    [RelayCommand]
    public async Task ClearAsync()
    {
        SenderFilter = "";
        SelectedOverallStatus = "Tất cả";
        FromDate = null;
        ToDate = null;
        await SearchAsync();
    }

    public Task<EmailResendResponseModel> ResendAsync(
        EmailGroupRow email,
        IReadOnlySet<string> selectedStatuses)
    {
        return apiClient.ResendEmailViaGmailAsync(email.MessageId, selectedStatuses);
    }

    private bool MatchesFilters(EmailGroupRow group)
    {
        if (FromDate.HasValue && group.ReceivedAt.Date < FromDate.Value.Date)
        {
            return false;
        }
        if (ToDate.HasValue && group.ReceivedAt.Date > ToDate.Value.Date)
        {
            return false;
        }
        return SelectedOverallStatus == "Tất cả"
               || group.OverallStatus == SelectedOverallStatus;
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

public sealed class EmailGroupRow
{
    public EmailGroupRow(List<EmailReportRowResponse> attachments)
    {
        Attachments = attachments;
        var first = attachments.OrderBy(row => row.AttachmentIndex).First();
        MessageId = first.MessageId;
        Sender = first.Sender;
        Subject = first.Subject;
        ReceivedAt = attachments.Max(row => row.ReceivedAt);
        TotalAttachments = attachments.Count;
        SavedCount = Count("SAVED");
        FailedCount = Count("FAILED");
        QuarantinedCount = Count("QUARANTINED");
        SkippedCount = Count("SKIPPED") + Count("NO_ATTACHMENT");
        ProcessingCount = Count("PROCESSING") + Count("DOWNLOADED") + Count("DISCOVERED");
        OverallStatus = FailedCount > 0 ? "FAILED"
            : QuarantinedCount > 0 ? "QUARANTINED"
            : ProcessingCount > 0 ? "PROCESSING"
            : SavedCount > 0 && SavedCount == TotalAttachments ? "SUCCESS"
            : "SKIPPED";
    }

    public List<EmailReportRowResponse> Attachments { get; }
    public string MessageId { get; }
    public string MaskedMessageId => MessageId.Length <= 36
        ? MessageId
        : $"{MessageId[..16]}...{MessageId[^14..]}";
    public string Sender { get; }
    public string Subject { get; }
    public DateTime ReceivedAt { get; }
    public string ReceivedLabel => ReceivedAt.ToString("dd/MM/yyyy HH:mm:ss");
    public int TotalAttachments { get; }
    public int SavedCount { get; }
    public int FailedCount { get; }
    public int QuarantinedCount { get; }
    public int SkippedCount { get; }
    public int ProcessingCount { get; }
    public string OverallStatus { get; }
    public string StatusBreakdown =>
        $"SAVED {SavedCount}  ·  FAILED {FailedCount}  ·  QUARANTINED {QuarantinedCount}  ·  SKIPPED {SkippedCount}  ·  PROCESSING {ProcessingCount}";
    public bool IsActionable => FailedCount > 0 || QuarantinedCount > 0;
    public bool CanResend => Attachments.Any(row => row.SyncJobId.HasValue) && ProcessingCount < TotalAttachments;
    public int StatusSortOrder => OverallStatus switch
    {
        "FAILED" => 0,
        "QUARANTINED" => 1,
        "PROCESSING" => 2,
        "SUCCESS" => 3,
        _ => 4
    };

    public int CountStatus(string status) => Count(status);

    private int Count(string status) => Attachments.Count(row =>
        string.Equals(row.ProcessingStatus, status, StringComparison.OrdinalIgnoreCase));
}
