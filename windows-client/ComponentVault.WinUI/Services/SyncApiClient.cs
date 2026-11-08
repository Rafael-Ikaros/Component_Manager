using ComponentVault.WinUI.Services.Bom;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using ComponentVault.WinUI.Models;

namespace ComponentVault.WinUI.Services;

public sealed partial class SyncApiClient
{
    private readonly HttpClient _httpClient;
    private readonly JsonSerializerOptions _jsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        PropertyNameCaseInsensitive = true,
        WriteIndented = false,
    };

    public SyncApiClient()
        : this(
            new HttpClient
            {
                Timeout = TimeSpan.FromSeconds(15),
            }
        )
    {
    }

    internal SyncApiClient(HttpClient httpClient)
    {
        _httpClient = httpClient ?? throw new ArgumentNullException(nameof(httpClient));
    }

    public async Task<OperationResult> TestConnectionAsync(
        SyncConfiguration settings,
        CancellationToken cancellationToken = default
    )
    {
        if (string.IsNullOrWhiteSpace(settings.ServerBaseUrl))
        {
            return OperationResult.Failure("请先填写服务器地址。");
        }

        if (string.IsNullOrWhiteSpace(settings.ApiToken))
        {
            return OperationResult.Failure("请先填写 API 令牌。");
        }

        try
        {
            var (serverBaseUrl, payload) = await ResolveServerAsync(settings, cancellationToken);
            if (payload.CostProtocol != 1) return OperationResult.Failure("请升级服务器以支持成本协议 cost_protocol=1；本地数据已保留。");
        if (payload.InventoryProtocol != 1)
                return OperationResult.Failure("服务器不支持多库位库存协议 inventory_protocol=1；本地待同步内容已保留。");
            SyncAccountIdentity identity;
            try
            {
                identity = await GetIdentityAsync(serverBaseUrl, settings.ApiToken, cancellationToken);
            }
            catch (JsonException)
            {
                return OperationResult.Failure("连接失败：账户身份响应的 JSON 无效或缺少必要字段；请检查服务器容器和代理路由。");
            }
            if (string.IsNullOrWhiteSpace(identity.ServerId) || string.IsNullOrWhiteSpace(identity.AccountId))
                return OperationResult.Failure("账户身份响应缺少 server_id 或 account_id；请检查服务器容器和代理路由。");
            return OperationResult.Success($"连接成功（{serverBaseUrl}，账户：{identity.Name}）。服务器时间：{payload.ServerTime}");
        }
        catch (Exception exception)
        {
            return OperationResult.Failure($"连接失败：{exception.Message}");
        }
    }

    public async Task<SyncRunResult> RunSyncAsync(
        SyncConfiguration settings,
        SyncPushRequest pushRequest,
        long? cursor,
        CancellationToken cancellationToken = default,
        Func<SyncAccountIdentity, string?>? validateAndBindIdentity = null
    )
    {
        if (string.IsNullOrWhiteSpace(settings.ServerBaseUrl))
        {
            return SyncRunResult.Failure("同步前请先填写服务器地址。");
        }

        if (string.IsNullOrWhiteSpace(settings.ApiToken))
        {
            return SyncRunResult.Failure("同步前请先填写 API 令牌。");
        }

        try
        {
            var (serverBaseUrl, capability) = await ResolveServerAsync(settings, cancellationToken);
            if (capability.CostProtocol != 1) return SyncRunResult.Failure("请升级服务器以支持成本协议 cost_protocol=1；本地数据已保留。");
            if (capability.InventoryProtocol != 1)
                return SyncRunResult.Failure("服务器不支持多库位库存协议 inventory_protocol=1；同步已停止，本地待同步内容已保留。");
            var identity = await GetIdentityAsync(serverBaseUrl, settings.ApiToken, cancellationToken);
            if (string.IsNullOrWhiteSpace(identity.ServerId) || string.IsNullOrWhiteSpace(identity.AccountId))
                return SyncRunResult.Failure("服务器无法确认账户身份。请先升级服务器；本地库存和同步进度已保留。");
            var identityError = validateAndBindIdentity?.Invoke(identity);
            if (identityError is not null) return SyncRunResult.Failure(identityError);
            var pushResponse = await PushAsync(settings, serverBaseUrl, pushRequest, identity.AccountId, cancellationToken);
            if (pushResponse.Result is not null)
            {
                return pushResponse.Result;
            }

            var pullResponse = await PullAsync(settings, serverBaseUrl, cursor, identity.AccountId, cancellationToken);
            if (pullResponse.Result is not null)
            {
                return pullResponse.Result;
            }

            return new SyncRunResult
            {
                IsSuccess = true,
                Message = "同步已成功完成。",
                UsedServerBaseUrl = serverBaseUrl,
                PhotoProtocol = capability.PhotoProtocol,
                BomPresetProtocol = capability.BomPresetProtocol,
                Identity = identity,
                AcceptedComponents = pushResponse.Payload!.AcceptedComponents,
                AcceptedStockMovements = pushResponse.Payload.AcceptedStockMovements,
                PullResponse = pullResponse.Payload!,
            };
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
        catch (Exception exception)
        {
            return SyncRunResult.Failure($"同步失败：{exception.Message}");
        }
    }

    public async Task<IReadOnlyList<BomPresetRecord>> RunPresetSyncAsync(
        SyncConfiguration settings, SyncRunResult inventory, BomPresetSyncRequest presets,
        CancellationToken cancellationToken = default)
    {
        if (inventory.BomPresetProtocol != 1)
            throw new InvalidOperationException("服务器暂不支持 BOM 预设同步，本地预设已保留。");
        using var request = new HttpRequestMessage(HttpMethod.Post, BuildUri(inventory.UsedServerBaseUrl, "/sync/bom-presets"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.ApiToken);
        request.Headers.Add("X-Component-Vault-Account-Id", inventory.Identity!.AccountId);
        request.Content = new StringContent(JsonSerializer.Serialize(presets, _jsonOptions), Encoding.UTF8, "application/json");
        using var response = await _httpClient.SendAsync(request, cancellationToken);
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException(await BuildErrorMessageAsync(response, cancellationToken));
        var payload = await DeserializeAsync<BomPresetSyncResponse>(response, cancellationToken);
        if (payload.BomPresetProtocol != 1 || payload.Presets is null) throw new InvalidDataException("预设同步响应协议无效。");
        foreach (var record in payload.Presets) BomPresetCodec.Validate(record);
        return payload.Presets;
    }

    private async Task<(string ServerBaseUrl, SyncTokenStatusResponse Capability)> ResolveServerAsync(
        SyncConfiguration settings,
        CancellationToken cancellationToken
    )
    {
        var hasDistinctFallback = !string.IsNullOrWhiteSpace(settings.FallbackServerBaseUrl)
            && !string.Equals(
                settings.ServerBaseUrl,
                settings.FallbackServerBaseUrl,
                StringComparison.OrdinalIgnoreCase
            );
        if (!hasDistinctFallback)
        {
            return (
                settings.ServerBaseUrl,
                await PingAsync(settings.ServerBaseUrl, settings.ApiToken, cancellationToken)
            );
        }

        try
        {
            using var primaryDeadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            primaryDeadline.CancelAfter(TimeSpan.FromSeconds(3));
            return (
                settings.ServerBaseUrl,
                await PingAsync(settings.ServerBaseUrl, settings.ApiToken, primaryDeadline.Token)
            );
        }
        catch (Exception exception) when (
            !cancellationToken.IsCancellationRequested
            && IsConnectionFailure(exception)
        )
        {
            return (
                settings.FallbackServerBaseUrl,
                await PingAsync(settings.FallbackServerBaseUrl, settings.ApiToken, cancellationToken)
            );
        }
    }

    private static bool IsConnectionFailure(Exception exception) =>
        exception is HttpRequestException or IOException or OperationCanceledException;

    private async Task<SyncTokenStatusResponse> PingAsync(
        string serverBaseUrl,
        string apiToken,
        CancellationToken cancellationToken
    )
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, BuildUri(serverBaseUrl, "/auth/ping"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", apiToken);
        using var response = await _httpClient.SendAsync(request, cancellationToken);
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException(await BuildErrorMessageAsync(response, cancellationToken));
        return await DeserializeAsync<SyncTokenStatusResponse>(response, cancellationToken);
    }

    private async Task<SyncAccountIdentity> GetIdentityAsync(
        string serverBaseUrl,
        string apiToken,
        CancellationToken cancellationToken
    )
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, BuildUri(serverBaseUrl, "/auth/me"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", apiToken);
        using var response = await _httpClient.SendAsync(request, cancellationToken);
        if (response.StatusCode == System.Net.HttpStatusCode.NotFound)
            throw new InvalidOperationException("账户身份接口 /auth/me 返回 404；请检查服务器容器版本和代理路由。本地库存和同步进度已保留。");
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException(await BuildErrorMessageAsync(response, cancellationToken));
        if (response.Content.Headers.ContentType?.MediaType?.Contains("html", StringComparison.OrdinalIgnoreCase) == true)
            throw new InvalidOperationException("账户身份接口返回 HTML 页面；请检查代理路由是否转发到正确的服务器。");
        return await DeserializeAsync<SyncAccountIdentity>(response, cancellationToken);
    }

    private async Task<(SyncPushResponse? Payload, SyncRunResult? Result)> PushAsync(
        SyncConfiguration settings,
        string serverBaseUrl,
        SyncPushRequest pushRequest,
        string accountId,
        CancellationToken cancellationToken
    )
    {
        var json = JsonSerializer.Serialize(pushRequest, _jsonOptions);
        using var request = new HttpRequestMessage(
            HttpMethod.Post,
            BuildUri(serverBaseUrl, "/sync/push")
        );
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.ApiToken);
        request.Headers.Add("X-Component-Vault-Account-Id", accountId);
        request.Content = new StringContent(json, Encoding.UTF8, "application/json");

        using var response = await _httpClient.SendAsync(request, cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            return (
                null,
                SyncRunResult.Failure(await BuildErrorMessageAsync(response, cancellationToken))
            );
        }

        return (await DeserializeAsync<SyncPushResponse>(response, cancellationToken), null);
    }

    private async Task<(SyncPullResponse? Payload, SyncRunResult? Result)> PullAsync(
        SyncConfiguration settings,
        string serverBaseUrl,
        long? cursor,
        string accountId,
        CancellationToken cancellationToken
    )
    {
        var path = cursor is null
            ? "/sync/pull"
            : $"/sync/pull?cursor={cursor.Value}";

        using var request = new HttpRequestMessage(
            HttpMethod.Get,
            BuildUri(serverBaseUrl, path)
        );
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", settings.ApiToken);
        request.Headers.Add("X-Component-Vault-Account-Id", accountId);
        request.Headers.Add("X-Component-Vault-Device-Id", settings.DeviceId);

        using var response = await _httpClient.SendAsync(request, cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            return (
                null,
                SyncRunResult.Failure(await BuildErrorMessageAsync(response, cancellationToken))
            );
        }

        var payload = await DeserializeAsync<SyncPullResponse>(response, cancellationToken);
        if (payload.CostProtocol != 1) throw new InvalidOperationException("请升级服务器以支持成本协议 cost_protocol=1。");
        if (payload.InventoryProtocol != 1)
            return (null, SyncRunResult.Failure("服务端拉取响应缺少 inventory_protocol=1；本地待同步内容已保留。"));
        if (payload.SyncCursor is < 0)
        {
            return (null, SyncRunResult.Failure("同步服务端返回了无效的同步游标。"));
        }

        return (payload, null);
    }

    private async Task<T> DeserializeAsync<T>(
        HttpResponseMessage response,
        CancellationToken cancellationToken
    )
    {
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        var payload = await JsonSerializer.DeserializeAsync<T>(
            stream,
            _jsonOptions,
            cancellationToken
        );
        return payload ?? throw new InvalidOperationException("服务端返回了空数据。");
    }

    private async Task<string> BuildErrorMessageAsync(
        HttpResponseMessage response,
        CancellationToken cancellationToken
    )
    {
        var body = await response.Content.ReadAsStringAsync(cancellationToken);
        try
        {
            var payload = JsonSerializer.Deserialize<ApiErrorResponse>(body, _jsonOptions);
            if (!string.IsNullOrWhiteSpace(payload?.Detail))
            {
                return $"同步服务端错误（{(int)response.StatusCode}）：{payload.Detail}";
            }
        }
        catch
        {
        }

        return $"同步服务端错误（{(int)response.StatusCode}）：{body}";
    }

    private static Uri BuildUri(string serverBaseUrl, string path) =>
        new($"{serverBaseUrl.TrimEnd('/')}{path}", UriKind.Absolute);
}
