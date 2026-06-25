using System.Collections.ObjectModel;
using AeroSync.UI.Models;
using AeroSync.UI.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace AeroSync.UI.ViewModels;

public sealed partial class ConfigViewModel : ObservableObject
{
    private readonly AeroSyncApiClient apiClient;

    private RuntimeConfigModel config = new();
    private string statusMessage = "Ready";
    private string newSender = "";
    private bool isBusy;

    public ObservableCollection<string> BlacklistSenders { get; } = [];

    public ConfigViewModel(AeroSyncApiClient apiClient)
    {
        this.apiClient = apiClient;
    }

    public RuntimeConfigModel Config
    {
        get => config;
        set => SetProperty(ref config, value);
    }

    public string StatusMessage
    {
        get => statusMessage;
        set => SetProperty(ref statusMessage, value);
    }

    public string NewSender
    {
        get => newSender;
        set => SetProperty(ref newSender, value);
    }

    public bool IsBusy
    {
        get => isBusy;
        set => SetProperty(ref isBusy, value);
    }

    [RelayCommand]
    public async Task LoadAsync()
    {
        try
        {
            IsBusy = true;
            Config = await apiClient.GetConfigAsync();
            ReplaceSenders(Config.BlacklistSenders);
            StatusMessage = "Configuration loaded.";
        }
        catch (Exception ex)
        {
            StatusMessage = $"Unable to load config: {ex.Message}";
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    public async Task SaveAsync()
    {
        try
        {
            IsBusy = true;
            Config.BlacklistSenders = BlacklistSenders.ToList();
            Config = await apiClient.SaveConfigAsync(Config);
            ReplaceSenders(Config.BlacklistSenders);
            StatusMessage = "Configuration saved.";
        }
        catch (Exception ex)
        {
            StatusMessage = $"Save failed: {ex.Message}";
        }
        finally
        {
            IsBusy = false;
        }
    }

    [RelayCommand]
    public void AddSender()
    {
        if (string.IsNullOrWhiteSpace(NewSender))
        {
            return;
        }

        BlacklistSenders.Add(NewSender.Trim());
        NewSender = "";
    }

    [RelayCommand]
    public void RemoveSender(string sender)
    {
        BlacklistSenders.Remove(sender);
    }

    [RelayCommand]
    public void ResetDefaults()
    {
        Config = new RuntimeConfigModel
        {
            IncomingDir = "C:/vatm-storage/incoming",
            ProcessedDir = "C:/vatm-storage/processed",
            ErrorDir = "C:/vatm-storage/error",
            EmailHost = "mail.vatm.vn",
            EmailPort = 993,
            EmailProtocol = "IMAP SSL/TLS",
            EmailUser = "system_slb@vatm.vn",
            RetryMode = "Exponential",
            MaxFilesPerCycle = 100,
            MaxSizePerFileMb = 10,
            SchedulerFixedDelayMs = 300_000,
            AutoQuarantine = true,
            SkipDuplicateIdempotency = true
        };
        ReplaceSenders(["ops@vatm.local"]);
        StatusMessage = "Defaults restored locally. Click Save to persist.";
    }

    private void ReplaceSenders(IEnumerable<string> senders)
    {
        BlacklistSenders.Clear();
        foreach (var sender in senders)
        {
            BlacklistSenders.Add(sender);
        }
    }
}
