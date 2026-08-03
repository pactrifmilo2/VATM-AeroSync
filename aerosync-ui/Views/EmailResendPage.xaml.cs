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

        try
        {
            var dialog = new EmailResendDialog(email) { XamlRoot = XamlRoot };
            if (await dialog.ShowAsync() != ContentDialogResult.Primary)
            {
                return;
            }

            ViewModel.IsLoading = true;
            ViewModel.StatusMessage = $"Đang gửi lại email của {email.Sender} qua Gmail...";
            var result = await ViewModel.ResendAsync(email, dialog.SelectedStatuses);

            await ShowMessageSafelyAsync(
                "Đã gửi lại email qua Gmail",
                $"Người nhận: {result.Recipient}\nFile đã đính kèm: {result.AttachmentsSent}\nFile không còn trong kho lưu trữ: {result.AttachmentsSkipped}\nMessage-ID mới: {result.SentMessageId}\n\nEmail mới sẽ được ingest kiểm tra theo lịch quét hộp thư.");
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
