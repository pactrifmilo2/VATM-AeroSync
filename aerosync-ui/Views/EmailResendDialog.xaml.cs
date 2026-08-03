using System.Collections.ObjectModel;
using AeroSync.UI.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace AeroSync.UI.Views;

public sealed partial class EmailResendDialog : ContentDialog
{
    public EmailResendDialogModel ViewModel { get; }

    public EmailResendDialog(EmailGroupRow email)
    {
        ViewModel = new EmailResendDialogModel(email);
        DataContext = ViewModel;
        InitializeComponent();
        Loaded += (_, _) => UpdateSelectionState();
    }

    public IReadOnlySet<string> SelectedStatuses => ViewModel.StatusOptions
        .Where(option => option.IsSelected && option.IsSelectable)
        .Select(option => option.Status)
        .ToHashSet(StringComparer.OrdinalIgnoreCase);

    private void StatusCheckBox_Changed(object sender, RoutedEventArgs e) => UpdateSelectionState();

    private void UpdateSelectionState()
    {
        var selected = ViewModel.StatusOptions.Where(option => option.IsSelected && option.IsSelectable).ToList();
        var count = selected.Sum(option => option.Count);
        IsPrimaryButtonEnabled = selected.Count > 0;
        SelectionSummaryText.Text = selected.Count == 0
            ? "Chưa chọn trạng thái nào."
            : $"Sẽ gửi {count}/{ViewModel.Email.TotalAttachments} file qua Gmail thuộc {selected.Count} trạng thái: {string.Join(", ", selected.Select(option => option.Status))}.";
    }
}

public sealed class EmailResendDialogModel
{
    public EmailResendDialogModel(EmailGroupRow email)
    {
        Email = email;
        AttachmentLabel = $"{email.TotalAttachments} attachment";
        Add("FAILED", "Đính kèm file lỗi vào email Gmail mới.");
        Add("QUARANTINED", "Đính kèm file cách ly vào email Gmail mới.");
        Add("SAVED", "Đính kèm lại file đã lưu; không xóa dữ liệu ATFM hiện có.");
        Add("SKIPPED", "Đính kèm lại file đã bị bỏ qua; SHA-256 vẫn được kiểm tra khi ingest.");
        Add("PROCESSING", "Worker đang xử lý file này nên tạm thời không cho gửi lại.", false);
    }

    public EmailGroupRow Email { get; }
    public string AttachmentLabel { get; }
    public ObservableCollection<StatusSelectionOption> StatusOptions { get; } = [];

    private void Add(string status, string description, bool selectable = true)
    {
        var count = status == "PROCESSING" ? Email.ProcessingCount : Email.CountStatus(status);
        if (count > 0) StatusOptions.Add(new StatusSelectionOption(status, description, count, selectable));
    }
}

public sealed class StatusSelectionOption(string status, string description, int count, bool selectable)
{
    public string Status { get; } = status;
    public string Description { get; } = description;
    public int Count { get; } = count;
    public bool IsSelectable { get; } = selectable;
    public bool IsSelected { get; set; }
}
