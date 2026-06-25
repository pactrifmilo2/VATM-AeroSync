using AeroSync.UI.Models;
using AeroSync.UI.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using System.Diagnostics;
using Windows.ApplicationModel.DataTransfer;

namespace AeroSync.UI.ViewModels;

public sealed partial class JobDetailsViewModel : ObservableObject
{
    private readonly AeroSyncApiClient apiClient;
    private readonly long jobId;

    private SyncJobDetailResponse? job;
    private string statusMessage = "Loading...";

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
        }
    }

    public string StatusMessage
    {
        get => statusMessage;
        set => SetProperty(ref statusMessage, value);
    }

    public List<FileRecordResponse> FileRecords => Job?.FileRecords ?? [];

    [RelayCommand]
    public async Task LoadAsync()
    {
        try
        {
            Job = await apiClient.GetJobAsync(jobId);
            StatusMessage = Job.LatestLogMessage ?? "No detail log available.";
        }
        catch (Exception ex)
        {
            StatusMessage = $"Unable to load job details: {ex.Message}";
        }
    }

    [RelayCommand]
    public async Task RetryAsync()
    {
        try
        {
            await apiClient.RetryJobAsync(jobId);
            StatusMessage = "Retry request sent.";
        }
        catch (Exception ex)
        {
            StatusMessage = $"Retry failed: {ex.Message}";
        }
    }

    [RelayCommand]
    public void OpenFolder(string storedPath)
    {
        if (!string.IsNullOrWhiteSpace(storedPath) && System.IO.File.Exists(storedPath))
        {
            Process.Start("explorer.exe", $"/select,\"{storedPath}\"");
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
