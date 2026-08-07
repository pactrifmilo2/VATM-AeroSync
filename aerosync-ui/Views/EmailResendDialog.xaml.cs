using System.Collections.ObjectModel;
using AeroSync.UI.ViewModels;
using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace AeroSync.UI.Views;

public sealed partial class EmailResendDialog : ContentDialog
{
    private bool updatingSelectAll;

    public EmailResendDialogModel ViewModel { get; }

    public EmailResendDialog(EmailGroupRow email) : this([email])
    {
    }

    public EmailResendDialog(IReadOnlyList<EmailGroupRow> emails)
    {
        ViewModel = new EmailResendDialogModel(emails);
        DataContext = ViewModel;
        InitializeComponent();
        Loaded += (_, _) => UpdateSelectionState();
    }

    public IReadOnlySet<string> SelectedStatuses => ViewModel.StatusOptions
        .Where(option => option.IsSelected && option.IsSelectable)
        .Select(option => option.Status)
        .ToHashSet(StringComparer.OrdinalIgnoreCase);

    private void StatusCheckBox_Changed(object sender, RoutedEventArgs e) => UpdateSelectionState();

    private void SelectAllStatuses_Changed(object sender, RoutedEventArgs e)
    {
        if (updatingSelectAll)
        {
            return;
        }

        var selected = SelectAllStatusesCheckBox.IsChecked == true;
        foreach (var option in ViewModel.StatusOptions.Where(option => option.IsSelectable))
        {
            option.IsSelected = selected;
        }
        UpdateSelectionState();
    }

    private void UpdateSelectionState()
    {
        if (SelectionSummaryText is null || SelectAllStatusesCheckBox is null)
        {
            return;
        }

        var selectable = ViewModel.StatusOptions.Where(option => option.IsSelectable).ToList();
        var selected = selectable.Where(option => option.IsSelected).ToList();
        var fileCount = selected.Sum(option => option.Count);
        var matchingEmails = ViewModel.Emails.Count(email =>
            selected.Any(option => email.CountStatus(option.Status) > 0));
        IsPrimaryButtonEnabled = selected.Count > 0 && matchingEmails > 0;

        updatingSelectAll = true;
        try
        {
            SelectAllStatusesCheckBox.IsChecked = selectable.Count > 0 && selected.Count == selectable.Count;
        }
        finally
        {
            updatingSelectAll = false;
        }

        SelectionSummaryText.Text = selected.Count == 0
            ? "Chưa chọn trạng thái nào."
            : $"Sẽ resend {matchingEmails}/{ViewModel.Emails.Count} email, tối đa {fileCount} file thuộc {selected.Count} trạng thái: {string.Join(", ", selected.Select(option => option.Status))}.";
    }
}

public sealed class EmailResendDialogModel
{
    public EmailResendDialogModel(IReadOnlyList<EmailGroupRow> emails)
    {
        if (emails.Count == 0)
        {
            throw new ArgumentException("Phải chọn ít nhất một email.", nameof(emails));
        }

        Emails = emails;
        var totalAttachments = emails.Sum(email => email.TotalAttachments);
        if (emails.Count == 1)
        {
            var email = emails[0];
            ScopeTitle = email.Sender;
            ScopeSubtitle = email.Subject;
            ScopeDetail = $"{email.ReceivedLabel}\n{email.MaskedMessageId}";
        }
        else
        {
            ScopeTitle = $"{emails.Count} email đã chọn";
            ScopeSubtitle = "Mỗi email sẽ được gửi lại riêng, giữ nguyên tiêu đề và các file theo trạng thái đã chọn.";
            ScopeDetail = $"Từ {emails.Min(email => email.ReceivedAt):dd/MM/yyyy HH:mm}\nđến {emails.Max(email => email.ReceivedAt):dd/MM/yyyy HH:mm}";
        }
        AttachmentLabel = $"{totalAttachments} file trong phạm vi đã chọn";

        Add("FAILED", "File xử lý thất bại; nên resend sau khi đã sửa lỗi.", defaultSelected: true);
        Add("QUARANTINED", "File bị cách ly; nên resend sau khi đã bổ sung format hoặc dữ liệu.", defaultSelected: true);
        Add("SAVED", "File đã lưu thành công; dữ liệu cũ sẽ được dọn trước lần ingest mới.");
        Add("SKIPPED", "File từng bị bỏ qua do trùng hoặc không đủ điều kiện.");
        Add("PROCESSING", "File vẫn đang được worker xử lý nên chưa thể resend.", selectable: false);
    }

    public IReadOnlyList<EmailGroupRow> Emails { get; }
    public string ScopeTitle { get; }
    public string ScopeSubtitle { get; }
    public string ScopeDetail { get; }
    public string AttachmentLabel { get; }
    public ObservableCollection<StatusSelectionOption> StatusOptions { get; } = [];

    private void Add(string status, string description, bool selectable = true, bool defaultSelected = false)
    {
        var count = status == "PROCESSING"
            ? Emails.Sum(email => email.ProcessingCount)
            : Emails.Sum(email => email.CountStatus(status));
        var emailCount = status == "PROCESSING"
            ? Emails.Count(email => email.ProcessingCount > 0)
            : Emails.Count(email => email.CountStatus(status) > 0);
        if (count > 0)
        {
            StatusOptions.Add(new StatusSelectionOption(
                status,
                description,
                count,
                emailCount,
                selectable,
                selectable && defaultSelected));
        }
    }
}

public sealed class StatusSelectionOption : ObservableObject
{
    private bool isSelected;

    public StatusSelectionOption(
        string status,
        string description,
        int count,
        int emailCount,
        bool isSelectable,
        bool isSelected)
    {
        Status = status;
        Description = description;
        Count = count;
        EmailCount = emailCount;
        IsSelectable = isSelectable;
        this.isSelected = isSelected;
    }

    public string Status { get; }
    public string Description { get; }
    public int Count { get; }
    public int EmailCount { get; }
    public string CountLabel => $"{EmailCount} email · {Count} file";
    public bool IsSelectable { get; }

    public bool IsSelected
    {
        get => isSelected;
        set => SetProperty(ref isSelected, value && IsSelectable);
    }
}
