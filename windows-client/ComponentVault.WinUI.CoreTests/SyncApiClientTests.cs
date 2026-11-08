using System.Net;
using System.Text;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class SyncApiClientTests
{
    [Fact]
    public async Task RunSync_PrimaryAvailable_UsesPrimaryForWholeRun()
    {
        var handler = new RecordingHandler((request, _) => Task.FromResult(SuccessFor(request)));

        var result = await CreateClient(handler).RunSyncAsync(Settings(), EmptyPush(), cursor: 7);

        Assert.True(result.IsSuccess);
        Assert.Equal("https://primary.example", result.UsedServerBaseUrl);
        Assert.Equal(
            [
                "POST https://primary.example/auth/ping",
                "GET https://primary.example/auth/me",
                "POST https://primary.example/sync/push",
                "GET https://primary.example/sync/pull?cursor=7",
            ],
            handler.Requests
        );
    }

    [Fact]
    public async Task RunSync_PrimaryOffline_SelectsFallbackForWholeRun()
    {
        var handler = new RecordingHandler((request, _) =>
        {
            if (request.RequestUri!.Host == "primary.example")
                throw new HttpRequestException("offline");
            return Task.FromResult(SuccessFor(request));
        });

        var identityChecked = false;
        var result = await CreateClient(handler).RunSyncAsync(
            Settings(), EmptyPush(), cursor: 7,
            validateAndBindIdentity: identity =>
            {
                identityChecked = identity.ServerId == "server-1" && identity.AccountId == "account-1";
                return identityChecked ? null : "unexpected identity";
            }
        );

        Assert.True(result.IsSuccess);
        Assert.True(identityChecked);
        Assert.Equal("https://fallback.example", result.UsedServerBaseUrl);
        Assert.Equal(
            [
                "POST https://primary.example/auth/ping",
                "POST https://fallback.example/auth/ping",
                "GET https://fallback.example/auth/me",
                "POST https://fallback.example/sync/push",
                "GET https://fallback.example/sync/pull?cursor=7",
            ],
            handler.Requests
        );
    }

    [Fact]
    public async Task RunSync_BothEndpointsOffline_ReportsFailureAfterBothPings()
    {
        var handler = new RecordingHandler((_, _) => throw new HttpRequestException("offline"));

        var result = await CreateClient(handler).RunSyncAsync(Settings(), EmptyPush(), cursor: null);

        Assert.False(result.IsSuccess);
        Assert.Equal(
            [
                "POST https://primary.example/auth/ping",
                "POST https://fallback.example/auth/ping",
            ],
            handler.Requests
        );
    }

    [Fact]
    public async Task RunSync_PrimaryUnauthorized_DoesNotTryFallback()
    {
        var handler = new RecordingHandler((_, _) => Task.FromResult(
            Json(HttpStatusCode.Unauthorized, "{\"detail\":\"bad token\"}")));

        var result = await CreateClient(handler).RunSyncAsync(Settings(), EmptyPush(), cursor: null);

        Assert.False(result.IsSuccess);
        Assert.Contains("401", result.Message);
        Assert.Equal(["POST https://primary.example/auth/ping"], handler.Requests);
    }

    [Fact]
    public async Task RunSync_IdentityMismatch_StopsBeforePushOrPull()
    {
        var handler = new RecordingHandler((request, _) => Task.FromResult(SuccessFor(request)));
        var result = await CreateClient(handler).RunSyncAsync(
            Settings(), EmptyPush(), cursor: 7,
            validateAndBindIdentity: identity => identity.AccountId == "other"
                ? null : "本地库存已绑定其他账户。"
        );

        Assert.False(result.IsSuccess);
        Assert.Equal([
            "POST https://primary.example/auth/ping",
            "GET https://primary.example/auth/me",
        ], handler.Requests);
    }

    [Fact]
    public async Task RunSync_SendsAccountHeaderToBothSyncRequests()
    {
        var headers = new List<string>();
        var handler = new RecordingHandler((request, _) =>
        {
            if (request.RequestUri!.AbsolutePath.StartsWith("/sync/"))
                headers.Add(request.Headers.GetValues("X-Component-Vault-Account-Id").Single());
            return Task.FromResult(SuccessFor(request));
        });

        Assert.True((await CreateClient(handler).RunSyncAsync(Settings(), EmptyPush(), cursor: null)).IsSuccess);
        Assert.Equal(["account-1", "account-1"], headers);
    }

    [Fact]
    public async Task RunSync_UserCancellation_DoesNotTryFallback()
    {
        using var cancellation = new CancellationTokenSource();
        var handler = new RecordingHandler(async (_, token) =>
        {
            cancellation.Cancel();
            await Task.Delay(Timeout.InfiniteTimeSpan, token);
            throw new InvalidOperationException("unreachable");
        });

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => CreateClient(handler).RunSyncAsync(
            Settings(), EmptyPush(), cursor: null, cancellation.Token));
        Assert.Equal(["POST https://primary.example/auth/ping"], handler.Requests);
    }

    [Fact]
    public async Task RunSync_PushTransportFailure_DoesNotSwitchMidRun()
    {
        var handler = new RecordingHandler((request, _) =>
        {
            if (request.RequestUri!.AbsolutePath == "/sync/push")
                throw new HttpRequestException("push interrupted");
            return Task.FromResult(SuccessFor(request));
        });

        var result = await CreateClient(handler).RunSyncAsync(Settings(), EmptyPush(), cursor: null);

        Assert.False(result.IsSuccess);
        Assert.Equal(
            [
                "POST https://primary.example/auth/ping",
                "GET https://primary.example/auth/me",
                "POST https://primary.example/sync/push",
            ],
            handler.Requests
        );
    }

    [Fact]
    public async Task RunSync_AfterFallbackSuccess_NextRunRetriesPrimary()
    {
        var primaryPingAttempts = 0;
        var handler = new RecordingHandler((request, _) =>
        {
            if (request.RequestUri!.Host == "primary.example"
                && request.RequestUri.AbsolutePath == "/auth/ping"
                && ++primaryPingAttempts == 1)
                throw new HttpRequestException("offline once");
            return Task.FromResult(SuccessFor(request));
        });
        var client = CreateClient(handler);

        Assert.True((await client.RunSyncAsync(Settings(), EmptyPush(), cursor: null)).IsSuccess);
        Assert.True((await client.RunSyncAsync(Settings(), EmptyPush(), cursor: null)).IsSuccess);

        Assert.Equal(2, handler.Requests.Count(request =>
            request == "POST https://primary.example/auth/ping"));
        Assert.Equal("POST https://primary.example/sync/push", handler.Requests[^2]);
        Assert.Equal("GET https://primary.example/sync/pull", handler.Requests[^1]);
    }

    [Theory]
    [InlineData("")]
    [InlineData("https://primary.example/")]
    [InlineData("HTTPS://PRIMARY.EXAMPLE")]
    public async Task TestConnection_MissingOrDuplicateFallback_DoesNotRepeatPrimary(string fallback)
    {
        var handler = new RecordingHandler((_, _) => throw new HttpRequestException("offline"));

        var result = await CreateClient(handler).TestConnectionAsync(Settings(fallback));

        Assert.False(result.IsSuccess);
        Assert.Equal(["POST https://primary.example/auth/ping"], handler.Requests);
    }

    [Fact]
    public async Task TestConnection_VerifiesIdentityWithoutSyncingOrBinding()
    {
        var handler = new RecordingHandler((request, _) => Task.FromResult(SuccessFor(request)));

        var result = await CreateClient(handler).TestConnectionAsync(Settings());

        Assert.True(result.IsSuccess);
        Assert.Contains("Admin", result.Message);
        Assert.Equal([
            "POST https://primary.example/auth/ping",
            "GET https://primary.example/auth/me",
        ], handler.Requests);
    }

    [Fact]
    public async Task TestConnection_Identity404_ReportsRouteAndDoesNotSync()
    {
        var handler = new RecordingHandler((request, _) => Task.FromResult(
            request.RequestUri!.AbsolutePath == "/auth/me"
                ? Json(HttpStatusCode.NotFound, "{}") : SuccessFor(request)));

        var result = await CreateClient(handler).TestConnectionAsync(Settings());

        Assert.False(result.IsSuccess);
        Assert.Contains("/auth/me", result.Message);
        Assert.Contains("404", result.Message);
        Assert.Equal(2, handler.Requests.Count);
    }

    [Theory]
    [InlineData("{\"server_id\":null,\"account_id\":\"account-1\",\"name\":\"Admin\",\"role\":\"admin\"}")]
    [InlineData("{\"account_id\":\"account-1\",\"name\":\"Admin\",\"role\":\"admin\"}")]
    public async Task TestConnection_MissingIdentity_ReportsFailure(string body)
    {
        var handler = new RecordingHandler((request, _) => Task.FromResult(
            request.RequestUri!.AbsolutePath == "/auth/me"
                ? Json(HttpStatusCode.OK, body) : SuccessFor(request)));

        var result = await CreateClient(handler).TestConnectionAsync(Settings());

        Assert.False(result.IsSuccess);
        Assert.Contains("身份", result.Message);
        Assert.Equal(2, handler.Requests.Count);
    }

    [Fact]
    public async Task TestConnection_HtmlIdentityPage_ReportsProxyRoute()
    {
        var handler = new RecordingHandler((request, _) => Task.FromResult(
            request.RequestUri!.AbsolutePath == "/auth/me"
                ? new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("<html>proxy</html>", Encoding.UTF8, "text/html"),
                }
                : SuccessFor(request)));

        var result = await CreateClient(handler).TestConnectionAsync(Settings());

        Assert.False(result.IsSuccess);
        Assert.Contains("HTML", result.Message);
        Assert.Equal(2, handler.Requests.Count);
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public async Task PresetExchange_UsesBoundAccountAndPreservesInventorySuccess(bool capability, bool endpoint)
    {
        var handler = new RecordingHandler(async (request, _) =>
        {
            if (request.RequestUri!.AbsolutePath == "/auth/ping" && capability)
                return Json(HttpStatusCode.OK, "{\"status\":\"ok\",\"server_time\":\"2026-10-22T00:00:00Z\",\"inventory_protocol\":1,\"cost_protocol\":1,\"bom_preset_protocol\":1}");
            if (request.RequestUri.AbsolutePath == "/sync/bom-presets")
            {
                Assert.Equal("account-1", Assert.Single(request.Headers.GetValues("X-Component-Vault-Account-Id")));
                Assert.Contains("\"device_id\":\"test-device\"", await request.Content!.ReadAsStringAsync());
                return endpoint ? Json(HttpStatusCode.OK, "{\"server_time\":\"2026-10-22T00:00:00Z\",\"bom_preset_protocol\":1,\"presets\":[]}") : Json(HttpStatusCode.NotFound, "{\"detail\":\"not found\"}");
            }
            return SuccessFor(request);
        });
        var client = CreateClient(handler);
        var result = await client.RunSyncAsync(Settings(), EmptyPush(), null,
            validateAndBindIdentity: identity => identity.AccountId == "account-1" ? null : "identity mismatch");
        Assert.True(result.IsSuccess);
        Assert.NotNull(result.PullResponse);
        var presets = new ComponentVault.WinUI.Services.Bom.BomPresetSyncRequest("test-device", []);
        if (capability && endpoint) Assert.Empty(await client.RunPresetSyncAsync(Settings(), result, presets));
        else await Assert.ThrowsAsync<InvalidOperationException>(() => client.RunPresetSyncAsync(Settings(), result, presets));
        Assert.Equal(capability ? 5 : 4, handler.Requests.Count);
    }
    [Theory]
    [InlineData("")]
    [InlineData(",\"cost_protocol\":2")]
    public async Task CostCapability_MissingOrUnsupported_StopsBeforeIdentityAndWrites(string costField)
    {
        var handler = new RecordingHandler((_, _) => Task.FromResult(Json(HttpStatusCode.OK,
            "{\"status\":\"ok\",\"server_time\":\"2026-09-16T00:00:00Z\",\"inventory_protocol\":1" + costField + "}")));
        var client = CreateClient(handler);
        var connection = await client.TestConnectionAsync(Settings());
        Assert.False(connection.IsSuccess);
        Assert.Contains("cost_protocol=1", connection.Message);
        Assert.Equal(["POST https://primary.example/auth/ping"], handler.Requests);
        handler.Requests.Clear();
        var sync = await client.RunSyncAsync(Settings(), EmptyPush(), null);
        Assert.False(sync.IsSuccess);
        Assert.Contains("cost_protocol=1", sync.Message);
        Assert.Equal(["POST https://primary.example/auth/ping"], handler.Requests);
    }

    [Theory]
    [InlineData("")]
    [InlineData(",\"cost_protocol\":2")]
    public async Task RunSync_PullMissingOrUnsupportedCostProtocol_RejectsPulledData(string costField)
    {
        var handler = new RecordingHandler((request, _) => Task.FromResult(
            request.RequestUri!.AbsolutePath == "/sync/pull"
                ? Json(HttpStatusCode.OK,"{\"inventory_protocol\":1,\"server_time\":\"2026-09-16T00:00:00Z\",\"components\":[],\"stock_movements\":[]" + costField + "}")
                : SuccessFor(request)));
        var result = await CreateClient(handler).RunSyncAsync(Settings(),EmptyPush(),null);
        Assert.False(result.IsSuccess);
        Assert.Contains("cost_protocol=1",result.Message);
        Assert.Null(result.PullResponse);
        Assert.Equal([
            "POST https://primary.example/auth/ping",
            "GET https://primary.example/auth/me",
            "POST https://primary.example/sync/push",
            "GET https://primary.example/sync/pull",
        ],handler.Requests);
    }

    private static SyncApiClient CreateClient(HttpMessageHandler handler) =>
        new(new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(15) });

    private static SyncConfiguration Settings(string fallback = "https://fallback.example") =>
        new()
        {
            DeviceId = "test-device",
            ServerBaseUrl = "https://primary.example",
            FallbackServerBaseUrl = fallback.Trim().TrimEnd('/'),
            ApiToken = "token",
            AutoSyncEnabled = false,
            LastSyncedAt = "从未同步",
            LastSyncMessage = "尚未同步。",
        };

    private static SyncPushRequest EmptyPush() => new() { DeviceId = "test-device" };

    private static HttpResponseMessage SuccessFor(HttpRequestMessage request) =>
        request.RequestUri!.AbsolutePath switch
        {
            "/auth/ping" => Json(HttpStatusCode.OK, "{\"status\":\"ok\",\"server_time\":\"2026-09-16T00:00:00Z\",\"inventory_protocol\":1,\"cost_protocol\":1}"),
            "/auth/me" => Json(HttpStatusCode.OK, "{\"server_id\":\"server-1\",\"account_id\":\"account-1\",\"name\":\"Admin\",\"role\":\"admin\"}"),
            "/sync/push" => Json(HttpStatusCode.OK, "{\"accepted_components\":0,\"accepted_stock_movements\":0,\"server_time\":\"2026-09-16T00:00:00Z\"}"),
            "/sync/pull" => Json(HttpStatusCode.OK, "{\"inventory_protocol\":1,\"cost_protocol\":1,\"server_time\":\"2026-09-16T00:00:00Z\",\"sync_cursor\":8,\"components\":[],\"stock_movements\":[],\"storage_locations\":[]}"),
            _ => throw new InvalidOperationException($"Unexpected request: {request.RequestUri}"),
        };

    private static HttpResponseMessage Json(HttpStatusCode statusCode, string json) =>
        new(statusCode) { Content = new StringContent(json, Encoding.UTF8, "application/json") };

    private sealed class RecordingHandler(
        Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> responseFactory
    ) : HttpMessageHandler
    {
        public List<string> Requests { get; } = [];

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken
        )
        {
            Requests.Add($"{request.Method} {request.RequestUri}");
            return responseFactory(request, cancellationToken);
        }
    }
}
