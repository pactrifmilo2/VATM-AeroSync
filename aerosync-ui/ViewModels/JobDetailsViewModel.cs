using AeroSync.UI.Models;
using AeroSync.UI.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.UI.Xaml;
using System.Diagnostics;
using Windows.ApplicationModel.DataTransfer;

namespace AeroSync.UI.ViewModels;

public sealed partial class JobDetailsViewModel : ObservableObject
{
    private readonly AeroSyncApiClient apiClient;
    private readonly long jobId;

    private SyncJobDetailResponse? job;
    private string statusMessage = "Đang tải...";

    public JobDetailsViewModel(AeroSyncApiClient apiClient, long jobId)
    {
        this.apiClient = apiClient;
        this.jobId = jobId;
    }

    public SyncJobDetailResponse? Job
    {
        get => job;
        set
        {
            SetProperty(ref job, value);
            OnPropertyChanged(nameof(FileRecords));
            OnPropertyChanged(nameof(EmailSectionVisibility));
            OnPropertyChanged(nameof(EmailSubject));
            OnPropertyChanged(nameof(EmailBody));
        }
    }

    public string StatusMessage
    {
        get => statusMessage;
        set => SetProperty(ref statusMessage, value);
    }

    public List<FileRecordResponse> FileRecords => Job?.FileRecords ?? [];

    public Visibility EmailSectionVisibility =>
        Job?.HasEmailContent == true ? Visibility.Visible : Visibility.Collapsed;

    public string EmailSubject => Job?.EmailSubject ?? "";
    public string EmailBody => Job?.EmailBody ?? "";

    [RelayCommand]
    public async Task LoadAsync()
    {
        try
        {
            Job = await apiClient.GetJobAsync(jobId);
            StatusMessage = VietnameseErrorMessage.FromServerMessage(Job.LatestLogMessage)
                ?? "Chưa có nhật ký chi tiết.";
        }
        catch (Exception ex)
        {
            StatusMessage = VietnameseErrorMessage.FromException(ex, "Tải chi tiết tác vụ");
        }
    }

    [RelayCommand]
    public async Task RetryAsync()
    {
        try
        {
            await apiClient.RetryJobAsync(jobId);
            StatusMessage = "Đã gửi yêu cầu xử lý lại.";
        }
        catch (Exception ex)
        {
            StatusMessage = VietnameseErrorMessage.FromException(ex, "Gửi yêu cầu xử lý lại");
        }
    }

    [RelayCommand]
    public void OpenFolder(string storedPath)
    {
        if (!string.IsNullOrWhiteSpace(storedPath) && System.IO.File.Exists(storedPath))
        {
            Process.Start("explorer.exe", $"/select, \"{storedPath}\"");
        }
    }

    [RelayCommand]
    public void CopyPath(string storedPath)
    {
        if (!string.IsNullOrWhiteSpace(storedPath))
        {
            var package = new DataPackage();
            package.SetText(storedPath);
            Clipboard.SetContent(package);
        }
    }
}
