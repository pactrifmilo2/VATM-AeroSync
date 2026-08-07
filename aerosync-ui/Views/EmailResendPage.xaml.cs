using AeroSync.UI.Services;
using AeroSync.UI.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace AeroSync.UI.Views;

public sealed partial class EmailResendPage : Page
{
    public EmailResendViewModel ViewModel { get; }

    public EmailResendPage(AeroSyncApiClient apiClient)
    {
        ViewModel = new EmailResendViewModel(apiClient);
        DataContext = ViewModel;
        InitializeComponent();
        Loaded += EmailResendPage_Loaded;
    }

    private async void EmailResendPage_Loaded(object sender, RoutedEventArgs e) =>
        await ViewModel.SearchAsync();

    private async void ResendButton_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.IsLoading
            || (sender as FrameworkElement)?.DataContext is not EmailGroupRow email)
        {
            return;
        }

        await ShowResendDialogAndExecuteAsync([email]);
    }

    private async void ResendSelectedButton_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.IsLoading)
        {
            return;
        }

        var selected = ViewModel.SelectedEmails;
        if (selected.Count == 0)
        {
            await ShowMessageSafelyAsync("Chưa chọn email", "Hãy chọn ít nhất một email để resend.");
            return;
        }

        await ShowResendDialogAndExecuteAsync(selected);
    }

    private async Task ShowResendDialogAndExecuteAsync(IReadOnlyList<EmailGroupRow> emails)
    {
        try
        {
            var dialog = new EmailResendDialog(emails) { XamlRoot = XamlRoot };
            if (await dialog.ShowAsync() != ContentDialogResult.Primary)
            {
                return;
            }

            ViewModel.IsLoading = true;
            var result = await ViewModel.ResendManyAsync(emails, dialog.SelectedStatuses);
            var title = result.Failed == 0
                ? "Đã hoàn tất resend email"
                : result.Succeeded > 0
                    ? "Resend email hoàn tất một phần"
                    : "Không thể resend email";
            await ShowMessageSafelyAsync(title, BuildResultMessage(result));
        }
        catch (Exception ex)
        {
            var message = VietnameseErrorMessage.FromException(ex, "Gửi lại email qua Gmail");
            ViewModel.StatusMessage = message;
            await ShowMessageSafelyAsync("Không thể gửi lại email", message);
        }
        finally
        {
            ViewModel.IsLoading = false;
        }

        await ViewModel.SearchAsync();
    }

    private static string BuildResultMessage(BulkEmailResendResult result)
    {
        var message = $"Email gửi thành công: {result.Succeeded}\n"
                      + $"Email gửi lỗi: {result.Failed}\n"
                      + $"Email bỏ qua do không có trạng thái phù hợp: {result.Skipped}\n"
                      + $"Tổng file đã gửi: {result.AttachmentsSent}\n\n"
                      + "Các email gửi thành công đã được dọn dữ liệu cũ và sẽ được ingest lại như email mới.";

        if (result.Errors.Count > 0)
        {
            message += "\n\nChi tiết lỗi:\n- "
                       + string.Join("\n- ", result.Errors.Take(8));
            if (result.Errors.Count > 8)
            {
                message += $"\n- ... và {result.Errors.Count - 8} lỗi khác.";
            }
        }
        return message;
    }

    private async Task ShowMessageSafelyAsync(string title, string content)
    {
        try
        {
            await new ContentDialog
            {
                XamlRoot = XamlRoot,
                Title = title,
                Content = content,
                CloseButtonText = "Đóng"
            }.ShowAsync();
        }
        catch
        {
            // Giữ ứng dụng hoạt động nếu WinUI không thể mở thêm ContentDialog.
            ViewModel.StatusMessage = $"{title}: {content}";
        }
    }
}
