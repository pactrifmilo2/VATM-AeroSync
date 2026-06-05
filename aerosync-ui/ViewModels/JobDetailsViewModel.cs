using AeroSync.UI.Models;
using AeroSync.UI.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

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
        set => SetProperty(ref job, value);
    }

    public string StatusMessage
    {
        get => statusMessage;
        set => SetProperty(ref statusMessage, value);
    }

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
}
