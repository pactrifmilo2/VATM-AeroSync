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
    private string statusMessage = "Sẵn sàng";
    private string newSender = "";
    private bool isBusy;

    public ObservableCollection<string> AllowedSenders { get; } = [];

    public ConfigViewModel(AeroSyncApiClient apiClient)
    {
        this.apiClient = apiClient;
    }

    public RuntimeConfigModel Config
    {
        get => config;
        set
        {
            if (SetProperty(ref config, value))
            {
                OnPropertyChanged(nameof(IsEmailMode));
                OnPropertyChanged(nameof(IsFolderMode));
                OnPropertyChanged(nameof(IsBothMode));
            }
        }
    }

    public bool IsEmailMode
    {
        get => string.Equals(Config.IngestionMode, "EMAIL", StringComparison.OrdinalIgnoreCase);
        set
        {
            if (!value)
            {
                OnPropertyChanged();
                return;
            }
            Config.IngestionMode = "EMAIL";
            OnPropertyChanged();
            OnPropertyChanged(nameof(IsFolderMode));
            OnPropertyChanged(nameof(IsBothMode));
        }
    }

    public bool IsFolderMode
    {
        get => string.Equals(Config.IngestionMode, "FOLDER", StringComparison.OrdinalIgnoreCase);
        set
        {
            if (!value)
            {
                OnPropertyChanged();
                return;
            }
            Config.IngestionMode = "FOLDER";
            OnPropertyChanged();
            OnPropertyChanged(nameof(IsEmailMode));
            OnPropertyChanged(nameof(IsBothMode));
        }
    }

    public bool IsBothMode
    {
        get => string.Equals(Config.IngestionMode, "BOTH", StringComparison.OrdinalIgnoreCase);
        set
        {
            if (!value)
            {
                OnPropertyChanged();
                return;
            }
            Config.IngestionMode = "BOTH";
            OnPropertyChanged();
            OnPropertyChanged(nameof(IsEmailMode));
            OnPropertyChanged(nameof(IsFolderMode));
        }
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
            ReplaceSenders(Config.AllowedSenders);
            StatusMessage = "Đã tải cấu hình.";
        }
        catch (Exception ex)
        {
            StatusMessage = VietnameseErrorMessage.FromException(ex, "Tải cấu hình");
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
            Config.AllowedSenders = AllowedSenders.ToList();
            Config = await apiClient.SaveConfigAsync(Config);
            ReplaceSenders(Config.AllowedSenders);
            StatusMessage = "Đã lưu cấu hình.";
        }
        catch (Exception ex)
        {
            StatusMessage = VietnameseErrorMessage.FromException(ex, "Lưu cấu hình");
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

        var sender = NewSender.Trim().ToLowerInvariant();
        if (!AllowedSenders.Any(existing => string.Equals(existing, sender, StringComparison.OrdinalIgnoreCase)))
        {
            AllowedSenders.Add(sender);
        }
        NewSender = "";
    }

    [RelayCommand]
    public void RemoveSender(string sender)
    {
        AllowedSenders.Remove(sender);
    }

    [RelayCommand]
    public void ResetDefaults()
    {
        Config = new RuntimeConfigModel
        {
            IncomingDir = "D:/vatm-storage/incoming",
            ProcessedDir = "D:/vatm-storage/processed",
            ErrorDir = "D:/vatm-storage/error",
            EmailHost = "mail.vatm.vn",
            EmailPort = 993,
            EmailProtocol = "IMAP SSL/TLS",
            EmailUser = "system_slb@vatm.vn",
            RetryMode = "Exponential",
            MaxFilesPerCycle = 100,
            MaxSizePerFileMb = 10,
            SchedulerFixedDelayMs = 300_000,
            IngestionMode = "EMAIL",
            FolderPollingIntervalMs = 60_000,
            AutoQuarantine = true,
            SkipDuplicateIdempotency = true
        };
        ReplaceSenders(new[]
        {
            "hiepdongbay@vatm.vn", "atd@caa.gov.vn", "dangbm@caa.gov.vn",
            "thaopt@caa.gov.vn", "nhithao169@mail.com", "dungdn@caa.gov.vn",
            "anhkd@caa.gov.vn", "giangpth@caa.gov.vn", "sontt@caa.gov.vn",
            "haanhlv@caa.gov.vn", "haanhlv@gmail.com", "thanhntm@caa.gov.vn",
            "anhld@caa.gov.vn", "duongvtt@caa.gov.vn", "lynk@caa.gov.vn",
            "tupt@caa.gov.vn"
        });
        StatusMessage = "Đã khôi phục cấu hình mặc định trên giao diện. Hãy bấm Lưu để áp dụng.";
    }

    private void ReplaceSenders(IEnumerable<string> senders)
    {
        AllowedSenders.Clear();
        foreach (var sender in senders)
        {
            AllowedSenders.Add(sender);
        }
    }
}
