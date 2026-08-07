using System.Collections.ObjectModel;
using System.ComponentModel;
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
    private string selectedAttachmentStatus = "Tất cả";
    private DateTimeOffset? fromDate;
    private DateTimeOffset? toDate;
    private bool isLoading;
    private bool isAllSelected;
    private bool isUpdatingSelection;
    private string statusMessage = "Nhập địa chỉ email rồi bấm Tìm kiếm.";
    private long totalEmails;
    private long actionableEmails;
    private long completedEmails;
    private long blockedEmails;
    private int selectedEmailCount;
    private int selectableEmailCount;

    public ObservableCollection<EmailGroupRow> Emails { get; } = [];
    public IReadOnlyList<string> OverallStatuses { get; } =
        ["Tất cả", "FAILED", "QUARANTINED", "PROCESSING", "SUCCESS", "SKIPPED"];
    public IReadOnlyList<string> AttachmentStatuses { get; } =
        ["Tất cả", "FAILED", "QUARANTINED", "SAVED", "SKIPPED", "PROCESSING", "NO_ATTACHMENT", "BLOCKED"];

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

    public string SelectedAttachmentStatus
    {
        get => selectedAttachmentStatus;
        set => SetProperty(ref selectedAttachmentStatus, value);
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
                OnPropertyChanged(nameof(CanResendSelected));
            }
        }
    }

    public bool IsNotLoading => !IsLoading;

    public bool IsAllSelected
    {
        get => isAllSelected;
        set
        {
            if (!SetProperty(ref isAllSelected, value) || isUpdatingSelection)
            {
                return;
            }

            isUpdatingSelection = true;
            try
            {
                foreach (var email in Emails.Where(email => email.CanResend))
                {
                    email.IsSelected = value;
                }
            }
            finally
            {
                isUpdatingSelection = false;
            }
            UpdateSelectionSummary();
        }
    }

    public string StatusMessage
    {
        get => statusMessage;
        set => SetProperty(ref statusMessage, value);
    }

    public long TotalEmails { get => totalEmails; set => SetProperty(ref totalEmails, value); }
    public long ActionableEmails { get => actionableEmails; set => SetProperty(ref actionableEmails, value); }
    public long CompletedEmails { get => completedEmails; set => SetProperty(ref completedEmails, value); }
    public long BlockedEmails { get => blockedEmails; set => SetProperty(ref blockedEmails, value); }

    public int SelectedEmailCount
    {
        get => selectedEmailCount;
        private set
        {
            if (SetProperty(ref selectedEmailCount, value))
            {
                OnPropertyChanged(nameof(SelectionLabel));
                OnPropertyChanged(nameof(CanResendSelected));
            }
        }
    }

    public int SelectableEmailCount
    {
        get => selectableEmailCount;
        private set
        {
            if (SetProperty(ref selectableEmailCount, value))
            {
                OnPropertyChanged(nameof(SelectAllLabel));
            }
        }
    }

    public string SelectAllLabel => $"Chọn tất cả email có thể resend ({SelectableEmailCount})";
    public string SelectionLabel => SelectedEmailCount == 0
        ? "Chưa chọn email"
        : $"Đã chọn {SelectedEmailCount} email";
    public bool CanResendSelected => SelectedEmailCount > 0 && !IsLoading;

    public IReadOnlyList<EmailGroupRow> SelectedEmails => Emails
        .Where(email => email.IsSelected && email.CanResend)
        .ToList();

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
            StatusMessage = "Đang tải và nhóm email theo Message-ID...";
            var rows = await apiClient.GetEmailReportsAsync(SenderFilter);
            var groups = await Task.Run(() => rows
                .Where(row => !string.IsNullOrWhiteSpace(row.MessageId))
                .GroupBy(row => row.MessageId, StringComparer.OrdinalIgnoreCase)
                .Select(group => new EmailGroupRow(group.ToList()))
                .Where(MatchesFilters)
                .OrderBy(group => group.StatusSortOrder)
                .ThenByDescending(group => group.ReceivedAt)
                .ToList());

            ReplaceEmails(groups);
            TotalEmails = groups.Count;
            ActionableEmails = groups.LongCount(group => group.IsActionable);
            CompletedEmails = groups.LongCount(group => group.OverallStatus == "SUCCESS");
            BlockedEmails = groups.LongCount(group => group.OverallStatus == "PROCESSING" || !group.CanResend);
            StatusMessage = $"Đã tải đầy đủ {rows.Count} bản ghi, nhóm thành {groups.Count} email phù hợp; email cần xử lý được xếp lên trước.";
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
        SelectedAttachmentStatus = "Tất cả";
        FromDate = null;
        ToDate = null;
        await SearchAsync();
    }

    public async Task<BulkEmailResendResult> ResendManyAsync(
        IReadOnlyList<EmailGroupRow> emails,
        IReadOnlySet<string> selectedStatuses,
        CancellationToken cancellationToken = default)
    {
        var attempts = new List<EmailResendAttempt>();
        var eligible = emails
            .Where(email => email.CanResend && email.HasAnyStatus(selectedStatuses))
            .ToList();
        var skipped = emails.Count - eligible.Count;

        for (var index = 0; index < eligible.Count; index++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var email = eligible[index];
            var matchingStatuses = selectedStatuses
                .Where(status => email.CountStatus(status) > 0)
                .ToHashSet(StringComparer.OrdinalIgnoreCase);

            email.ResendState = $"Đang gửi {index + 1}/{eligible.Count}";
            StatusMessage = $"Đang gửi email {index + 1}/{eligible.Count}: {email.Sender} — {email.Subject}";
            try
            {
                var response = await apiClient.ResendEmailViaGmailAsync(
                    email.MessageId,
                    matchingStatuses,
                    cancellationToken);
                email.ResendState = $"Đã gửi {response.AttachmentsSent} file";
                attempts.Add(new EmailResendAttempt(email, response, null));
            }
            catch (Exception ex)
            {
                var error = VietnameseErrorMessage.FromException(ex, $"Gửi lại email {email.Subject}");
                email.ResendState = "Gửi lỗi";
                attempts.Add(new EmailResendAttempt(email, null, error));
            }
        }

        return new BulkEmailResendResult(attempts, skipped);
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
        if (SelectedOverallStatus != "Tất cả"
            && group.OverallStatus != SelectedOverallStatus)
        {
            return false;
        }
        return SelectedAttachmentStatus == "Tất cả"
               || group.HasDisplayStatus(SelectedAttachmentStatus);
    }

    private void ReplaceEmails(IReadOnlyList<EmailGroupRow> groups)
    {
        foreach (var email in Emails)
        {
            email.PropertyChanged -= EmailSelectionChanged;
        }
        Emails.Clear();
        foreach (var email in groups)
        {
            email.PropertyChanged += EmailSelectionChanged;
            Emails.Add(email);
        }
        UpdateSelectionSummary();
    }

    private void EmailSelectionChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(EmailGroupRow.IsSelected) && !isUpdatingSelection)
        {
            UpdateSelectionSummary();
        }
    }

    private void UpdateSelectionSummary()
    {
        SelectableEmailCount = Emails.Count(email => email.CanResend);
        SelectedEmailCount = Emails.Count(email => email.CanResend && email.IsSelected);
        var allSelected = SelectableEmailCount > 0 && SelectedEmailCount == SelectableEmailCount;

        isUpdatingSelection = true;
        try
        {
            SetProperty(ref isAllSelected, allSelected, nameof(IsAllSelected));
        }
        finally
        {
            isUpdatingSelection = false;
        }
    }
}

public sealed class EmailGroupRow : ObservableObject
{
    private bool isSelected;
    private string resendState = "Sẵn sàng";

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
        SkippedCount = Count("SKIPPED");
        NoAttachmentCount = Count("NO_ATTACHMENT");
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
    public int NoAttachmentCount { get; }
    public int ProcessingCount { get; }
    public string OverallStatus { get; }
    public string StatusBreakdown =>
        $"SAVED {SavedCount}  ·  FAILED {FailedCount}  ·  QUARANTINED {QuarantinedCount}  ·  SKIPPED {SkippedCount}  ·  PROCESSING {ProcessingCount}";
    public bool IsActionable => FailedCount > 0 || QuarantinedCount > 0;
    public bool CanResend => Attachments.Any(row => row.SyncJobId.HasValue)
                             && Attachments.Any(row => row.ProcessingStatus is "FAILED" or "QUARANTINED" or "SAVED" or "SKIPPED");

    public bool IsSelected
    {
        get => isSelected;
        set => SetProperty(ref isSelected, value && CanResend);
    }

    public string ResendState
    {
        get => resendState;
        set => SetProperty(ref resendState, value);
    }

    public int StatusSortOrder => OverallStatus switch
    {
        "FAILED" => 0,
        "QUARANTINED" => 1,
        "PROCESSING" => 2,
        "SUCCESS" => 3,
        _ => 4
    };

    public int CountStatus(string status) => Count(status);

    public bool HasAnyStatus(IEnumerable<string> statuses) =>
        statuses.Any(status => CountStatus(status) > 0);

    public bool HasDisplayStatus(string status) => status switch
    {
        "PROCESSING" => ProcessingCount > 0,
        _ => CountStatus(status) > 0
    };

    private int Count(string status) => Attachments.Count(row =>
        string.Equals(row.ProcessingStatus, status, StringComparison.OrdinalIgnoreCase));
}

public sealed record EmailResendAttempt(
    EmailGroupRow Email,
    EmailResendResponseModel? Response,
    string? Error)
{
    public bool Succeeded => Response is not null;
}

public sealed record BulkEmailResendResult(
    IReadOnlyList<EmailResendAttempt> Attempts,
    int Skipped)
{
    public int Succeeded => Attempts.Count(attempt => attempt.Succeeded);
    public int Failed => Attempts.Count(attempt => !attempt.Succeeded);
    public int AttachmentsSent => Attempts.Sum(attempt => attempt.Response?.AttachmentsSent ?? 0);
    public IReadOnlyList<string> Errors => Attempts
        .Where(attempt => !attempt.Succeeded)
        .Select(attempt => $"{attempt.Email.Subject}: {attempt.Error}")
        .ToList();
}
