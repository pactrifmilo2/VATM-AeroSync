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
        response.EnsureSuccessStatusCode();
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
