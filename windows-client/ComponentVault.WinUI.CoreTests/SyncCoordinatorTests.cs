using System.Net;
using System.Text;
using ComponentVault.WinUI.Services;
using Microsoft.Data.Sqlite;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class SyncCoordinatorTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "component-vault-sync-coordinator-" + Guid.NewGuid());

    [Fact]
    public async Task ManualSyncAndConnectionTestShareOneGate()
    {
        var store = CreateStore();
        Assert.True(store.SaveSyncConfiguration("https://sync.example", "", "token", false).IsSuccess);
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var requests = new List<string>();
        var handler = new Handler(async (request, _) =>
        {
            lock (requests) requests.Add(request.RequestUri!.AbsolutePath);
            if (request.RequestUri!.AbsolutePath == "/auth/ping" && !entered.Task.IsCompleted)
            {
                entered.SetResult();
                await release.Task;
            }
            return SuccessFor(request);
        });
        var api = new SyncApiClient(new HttpClient(handler));
        using var coordinator = new SyncCoordinator(store, new InventorySyncService(store, api), () => { });

        var sync = coordinator.RunSyncAsync();
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        var test = coordinator.TestConnectionAsync("https://sync.example", "", "token", false);
        Assert.True(coordinator.IsBusy);
        Assert.False(test.IsCompleted);
        lock (requests) Assert.Single(requests);
        release.SetResult();

        Assert.True((await sync).IsSuccess);
        Assert.True((await test).IsSuccess);
        Assert.False(coordinator.IsBusy);
        lock (requests) Assert.Equal(2, requests.Count(path => path == "/auth/ping"));
    }

    [Fact]
    public async Task RepeatedSchedulingCoalescesAndDisablingCancelsPendingSync()
    {
        var store = CreateStore();
        Assert.True(store.SaveSyncConfiguration("https://sync.example", "", "token", true).IsSuccess);
        var calls = 0;
        var handler = new Handler((request, _) =>
        {
            if (request.RequestUri!.AbsolutePath == "/auth/ping") Interlocked.Increment(ref calls);
            return Task.FromResult(SuccessFor(request));
        });
        var api = new SyncApiClient(new HttpClient(handler));
        var completed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        SyncCoordinator? coordinator = null;
        coordinator = new SyncCoordinator(store, new InventorySyncService(store, api),
            () => { coordinator!.Refresh(); completed.TrySetResult(); });
        using var owned = coordinator;

        coordinator.ScheduleAutoSync();
        coordinator.ScheduleAutoSync();
        await completed.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(1, Volatile.Read(ref calls));

        coordinator.ScheduleAutoSync();
        Assert.True(coordinator.SaveConfiguration("https://sync.example", "", "token", false).IsSuccess);
        await Task.Delay(900);
        Assert.Equal(1, Volatile.Read(ref calls));
    }

    private InventoryStore CreateStore()
    {
        Directory.CreateDirectory(_root);
        var store = new InventoryStore(Path.Combine(_root, "inventory.db"));
        store.Initialize();
        return store;
    }

    private static HttpResponseMessage SuccessFor(HttpRequestMessage request) =>
        request.RequestUri!.AbsolutePath switch
        {
            "/auth/ping" => Json("{\"status\":\"ok\",\"server_time\":\"2026-09-16T00:00:00Z\",\"inventory_protocol\":1,\"cost_protocol\":1}"),
            "/auth/me" => Json("{\"server_id\":\"server-1\",\"account_id\":\"account-1\",\"name\":\"Admin\",\"role\":\"admin\"}"),
            "/sync/push" => Json("{\"accepted_components\":0,\"accepted_stock_movements\":0,\"server_time\":\"2026-09-16T00:00:00Z\"}"),
            "/sync/pull" => Json("{\"inventory_protocol\":1,\"cost_protocol\":1,\"server_time\":\"2026-09-16T00:00:00Z\",\"sync_cursor\":8,\"components\":[],\"stock_movements\":[],\"storage_locations\":[]}"),
            _ => throw new InvalidOperationException($"Unexpected request: {request.RequestUri}"),
        };

    private static HttpResponseMessage Json(string body) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private sealed class Handler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> respond)
        : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,
            CancellationToken cancellationToken) => respond(request, cancellationToken);
    }

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }
}
