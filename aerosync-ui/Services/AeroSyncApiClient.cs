using System.Net.Http.Json;
using System.Text.Json;
using AeroSync.UI.Models;

namespace AeroSync.UI.Services;

public sealed class AeroSyncApiClient
{
    private readonly HttpClient httpClient;
    private readonly JsonSerializerOptions jsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = true
    };

    public AeroSyncApiClient(HttpClient httpClient)
    {
        this.httpClient = httpClient;
    }

    public async Task<DashboardStatsResponse> GetDashboardStatsAsync(CancellationToken cancellationToken = default)
    {
        return await httpClient.GetFromJsonAsync<DashboardStatsResponse>(
                "/api/dashboard/stats",
                jsonOptions,
                cancellationToken)
            ?? new DashboardStatsResponse();
    }

    public async Task<List<SyncJobSummaryResponse>> GetJobsAsync(CancellationToken cancellationToken = default)
    {
        return await httpClient.GetFromJsonAsync<List<SyncJobSummaryResponse>>(
                "/api/jobs",
                jsonOptions,
                cancellationToken)
            ?? [];
    }

    public async Task<SyncJobDetailResponse> GetJobAsync(long id, CancellationToken cancellationToken = default)
    {
        return await httpClient.GetFromJsonAsync<SyncJobDetailResponse>(
                $"/api/jobs/{id}",
                jsonOptions,
                cancellationToken)
            ?? new SyncJobDetailResponse();
    }

    public async Task RetryJobAsync(long id, CancellationToken cancellationToken = default)
    {
        using var response = await httpClient.PostAsync($"/api/jobs/{id}/retry", null, cancellationToken);
        await VietnameseErrorMessage.ThrowIfFailedAsync(
            response,
            $"Thử xử lý lại file (job {id})",
            cancellationToken);
    }

    public async Task<List<EmailReportRowResponse>> GetEmailReportsAsync(
        string? sender,
        CancellationToken cancellationToken = default)
    {
        var rows = new List<EmailReportRowResponse>();
        var page = 0;
        PagedResponse<EmailReportRowResponse>? response;
        var hasSenderFilter = !string.IsNullOrWhiteSpace(sender);
        var senderQuery = !hasSenderFilter
            ? ""
            : $"&sender={Uri.EscapeDataString(sender!.Trim())}";

        do
        {
            response = await httpClient.GetFromJsonAsync<PagedResponse<EmailReportRowResponse>>(
                $"/api/reports/emails?page={page}&size=100{senderQuery}",
                jsonOptions,
                cancellationToken);
            if (response is null)
            {
                break;
            }
            rows.AddRange(response.Content);
            page++;
        } while (response.HasNext && (hasSenderFilter || page < 1));

        return rows;
    }

    public async Task<TestReplayResponse> ReplayJobAsync(
        long id,
        string normalizedPermitId,
        CancellationToken cancellationToken = default)
    {
        using var response = await httpClient.PostAsJsonAsync(
            $"/api/testing/jobs/{id}/replay",
            new TestReplayRequest { ConfirmPermitId = normalizedPermitId },
            jsonOptions,
            cancellationToken);
        await VietnameseErrorMessage.ThrowIfFailedAsync(
            response,
            $"Phát lại file (job {id})",
            cancellationToken);
        return await response.Content.ReadFromJsonAsync<TestReplayResponse>(jsonOptions, cancellationToken)
            ?? new TestReplayResponse { JobId = id, NormalizedPermitId = normalizedPermitId };
    }

    public async Task<EmailResendResponseModel> ResendEmailViaGmailAsync(
        string messageId,
        IReadOnlySet<string> statuses,
        CancellationToken cancellationToken = default)
    {
        using var response = await httpClient.PostAsJsonAsync(
            "/api/reports/emails/resend",
            new EmailResendRequestModel { MessageId = messageId, Statuses = statuses },
            jsonOptions,
            cancellationToken);
        await VietnameseErrorMessage.ThrowIfFailedAsync(
            response,
            "Gửi lại email qua Gmail",
            cancellationToken);
        return await response.Content.ReadFromJsonAsync<EmailResendResponseModel>(jsonOptions, cancellationToken)
            ?? throw new InvalidOperationException("Máy chủ không trả về kết quả gửi email.");
    }

    public async Task<List<AuditLogResponse>> GetAuditLogsAsync(CancellationToken cancellationToken = default)
    {
        return await httpClient.GetFromJsonAsync<List<AuditLogResponse>>(
                "/api/audit-logs",
                jsonOptions,
                cancellationToken)
            ?? [];
    }

    public async Task<RuntimeConfigModel> GetConfigAsync(CancellationToken cancellationToken = default)
    {
        return await httpClient.GetFromJsonAsync<RuntimeConfigModel>(
                "/api/config",
                jsonOptions,
                cancellationToken)
            ?? new RuntimeConfigModel();
    }

    public async Task<RuntimeConfigModel> SaveConfigAsync(RuntimeConfigModel config, CancellationToken cancellationToken = default)
    {
        using var response = await httpClient.PutAsJsonAsync("/api/config", config, jsonOptions, cancellationToken);
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<RuntimeConfigModel>(jsonOptions, cancellationToken)
            ?? config;
    }
}
