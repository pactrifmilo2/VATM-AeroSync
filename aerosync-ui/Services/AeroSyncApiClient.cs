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
        var result = await httpClient.GetFromJsonAsync<SyncJobDetailResponse>(
                $"/api/jobs/{id}",
                jsonOptions,
                cancellationToken)
            ?? new SyncJobDetailResponse();
        result.LatestLogMessage = VietnameseErrorMessage.FromServerMessage(result.LatestLogMessage);
        result.PermitImportError = VietnameseErrorMessage.FromServerMessage(result.PermitImportError);
        foreach (var rowError in result.RowErrors)
        {
            rowError.Message = VietnameseErrorMessage.FromServerMessage(rowError.Message)
                ?? "Dữ liệu tại dòng này không hợp lệ.";
        }
        return result;
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
        DateTimeOffset? fromDate = null,
        DateTimeOffset? toDate = null,
        CancellationToken cancellationToken = default)
    {
        var senderQuery = string.IsNullOrWhiteSpace(sender)
            ? ""
            : $"&sender={Uri.EscapeDataString(sender!.Trim())}";
        var fromQuery = fromDate.HasValue
            ? $"&from={Uri.EscapeDataString(fromDate.Value.Date.ToString("yyyy-MM-dd'T'00:00:00"))}"
            : "";
        var toQuery = toDate.HasValue
            ? $"&to={Uri.EscapeDataString(toDate.Value.Date.ToString("yyyy-MM-dd'T'23:59:59.9999999"))}"
            : "";

        Task<PagedResponse<EmailReportRowResponse>?> FetchPageAsync(int page) =>
            httpClient.GetFromJsonAsync<PagedResponse<EmailReportRowResponse>>(
                $"/api/reports/emails?page={page}&size=100{senderQuery}{fromQuery}{toQuery}",
                jsonOptions,
                cancellationToken);

        var firstPage = await FetchPageAsync(0);
        if (firstPage is null)
        {
            return [];
        }

        var pages = new List<(int Number, PagedResponse<EmailReportRowResponse> Page)>
        {
            (0, firstPage)
        };
        if (firstPage.TotalPages > 1)
        {
            using var concurrency = new SemaphoreSlim(4);
            var pageTasks = Enumerable.Range(1, firstPage.TotalPages - 1).Select(async pageNumber =>
            {
                await concurrency.WaitAsync(cancellationToken);
                try
                {
                    return (Number: pageNumber, Page: await FetchPageAsync(pageNumber));
                }
                finally
                {
                    concurrency.Release();
                }
            });
            var remainingPages = await Task.WhenAll(pageTasks);
            pages.AddRange(remainingPages
                .Where(item => item.Page is not null)
                .Select(item => (item.Number, item.Page!)));
        }

        var rows = new List<EmailReportRowResponse>();
        foreach (var page in pages.OrderBy(item => item.Number))
        {
            foreach (var row in page.Page.Content)
            {
                row.ErrorMessage = VietnameseErrorMessage.FromServerMessage(row.ErrorMessage);
            }
            rows.AddRange(page.Page.Content);
        }

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
        var logs = await httpClient.GetFromJsonAsync<List<AuditLogResponse>>(
                "/api/audit-logs",
                jsonOptions,
                cancellationToken)
            ?? [];
        foreach (var log in logs)
        {
            log.Message = VietnameseErrorMessage.FromServerMessage(log.Message);
        }
        return logs;
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
