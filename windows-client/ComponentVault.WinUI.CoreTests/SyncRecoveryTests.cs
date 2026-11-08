using System.Net;
using System.Text;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using ComponentVault.WinUI.Services.SyncPersistence;
using Microsoft.Data.Sqlite;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class SyncRecoveryTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "sync-recovery-" + Guid.NewGuid());
    private InventoryStore CreateStore()
    {
        Directory.CreateDirectory(_root);
        var store = new InventoryStore(Path.Combine(_root, "inventory.db")); store.Initialize();
        store.SaveSyncConfiguration("https://sync.test", "", "token", false); return store;
    }
    [Fact]
    public void StartupRecoversRunningAndKeepsSuccessfulStageTimes()
    {
        var store = CreateStore(); var states = new SyncRunStateStore(store.DatabasePath);
        states.Save(new(State: "Running", Inventory: "Complete", InventorySuccess: "inventory-time", ServerUrl: "https://sync.test"));
        var service = new InventorySyncService(store, new SyncApiClient());
        using var coordinator = new SyncCoordinator(store, service, () => { });
        Assert.Equal("Interrupted", states.Read().State);
        Assert.Contains("中断", coordinator.ShellSyncSummary);
        Assert.Contains("inventory-time", service.ScopeSummary);
        states.Save(states.Read() with { State = "Running" });
        Assert.Equal("Running", states.Read().State); // Ordinary reads never manufacture an interruption.
    }
    [Theory]
    [InlineData("old-server", "account")]
    [InlineData("server", "old-account")]
    public void SameUrlWithDifferentBindingDoesNotReuseSuccessfulState(string oldServer, string oldAccount)
    {
        var store = CreateStore();
        Assert.Null(store.ValidateAndBindSyncIdentity(new SyncAccountIdentity
            { ServerId = "server", AccountId = "account", Name = "Admin", Role = "admin" }, store.GetSyncConfiguration()));
        new SyncRunStateStore(store.DatabasePath).Save(new(State: "Complete", Inventory: "Complete", Presets: "Complete",
            InventorySuccess: "old-inventory-time", PresetSuccess: "old-preset-time", CompleteSuccess: "old-complete-time",
            ServerUrl: "https://sync.test", ServerId: oldServer, AccountId: oldAccount));
        var service = new InventorySyncService(store, new SyncApiClient());
        Assert.DoesNotContain("上轮同步完成", service.StatusSummary);
        Assert.DoesNotContain("old-", service.ScopeSummary);
    }
    [Fact]
    public void OfflineWriteIsShownAsPendingAfterPreviouslyCompleteRun()
    {
        var store = CreateStore(); var states = new SyncRunStateStore(store.DatabasePath);
        states.Save(new(State: "Complete", ServerUrl: "https://sync.test"));
        store.SaveComponent(new ComponentDraft { Sku = "LOCAL", Name = "Local", Category = "test", PackageName = "0603", Location = "A", Quantity = 1, MinStock = 0 });
        Assert.True(store.GetPendingSyncCount() > 0);
        Assert.Contains("库存待同步", new InventorySyncService(store, new SyncApiClient()).StatusSummary);
        Assert.Equal(1, Assert.Single(store.GetComponents()).Quantity);
    }
    [Fact]
    public async Task CancelDuringPresetExchangePreservesAppliedInventoryAndAllowsRetry()
    {
        var store = CreateStore();
        store.SaveComponent(new ComponentDraft { Sku = "LOCAL", Name = "Local", Category = "test", PackageName = "0603", Location = "A", Quantity = 1, MinStock = 0 });
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var pause = true;
        var api = new SyncApiClient(new HttpClient(new Handler(async (request, cancellation) =>
        {
            if (request.RequestUri!.AbsolutePath == "/sync/bom-presets" && pause)
            {
                Assert.Equal(0, store.GetPendingSyncCount());
                Assert.Contains("库存已完成", store.GetSyncConfiguration().LastSyncMessage);
                entered.SetResult();
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellation);
            }
            return Json(request.RequestUri.AbsolutePath switch
            {
                "/auth/ping" => "{\"status\":\"ok\",\"server_time\":\"2026-10-22T00:00:00Z\",\"inventory_protocol\":1,\"cost_protocol\":1,\"bom_preset_protocol\":1,\"photo_protocol\":1}",
                "/auth/me" => "{\"server_id\":\"server\",\"account_id\":\"account\",\"name\":\"Admin\",\"role\":\"admin\"}",
                "/sync/push" => "{\"accepted_components\":1,\"accepted_stock_movements\":0,\"server_time\":\"2026-10-22T00:00:00Z\"}",
                "/sync/pull" => "{\"inventory_protocol\":1,\"cost_protocol\":1,\"server_time\":\"2026-10-22T00:00:00Z\",\"sync_cursor\":8,\"components\":[],\"stock_movements\":[],\"storage_locations\":[]}",
                "/sync/bom-presets" => "{\"bom_preset_protocol\":1,\"presets\":[]}",
                "/sync/photos" => "{\"photo_protocol\":1,\"photos\":[]}",
                _ => throw new InvalidOperationException("Unexpected request")
            });
        })));
        var service = new InventorySyncService(store, api);
        using var coordinator = new SyncCoordinator(store, service, () => { });
        var run = coordinator.RunSyncAsync(); await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.True(coordinator.CanCancelSync); coordinator.CancelSync();
        var cancelled = await run;
        Assert.True(cancelled.IsPartial); Assert.True(cancelled.IsSuccess);
        var state = new SyncRunStateStore(store.DatabasePath).Read();
        Assert.Equal("Cancelled", state.State); Assert.Equal("Complete", state.Inventory);
        Assert.Equal(0, store.GetPendingSyncCount()); Assert.Single(store.GetComponents());
        pause = false;
        Assert.False((await coordinator.RunSyncAsync()).IsPartial);
        var finished = new SyncRunStateStore(store.DatabasePath).Read();
        Assert.Equal("Complete", finished.State);
        Assert.DoesNotContain("待完成", finished.Detail);
        Assert.Contains("照片：完成", finished.Detail);
    }
    private static HttpResponseMessage Json(string body) => new(HttpStatusCode.OK) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
    private sealed class Handler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send) : HttpMessageHandler
    { protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => send(request, cancellationToken); }
    public void Dispose() { SqliteConnection.ClearAllPools(); if (Directory.Exists(_root)) Directory.Delete(_root, true); }
}
