using System.Globalization;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services;
using Microsoft.Data.Sqlite;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class InventoryStoreSyncTests : IDisposable
{
    private readonly string _testRoot = Path.Combine(
        Path.GetTempPath(),
        $"component-vault-tests-{Guid.NewGuid():N}"
    );

    [Fact]
    public void PulledOrderSourcePreventsReceivingTheSameOrderAgain()
    {
        var store = CreateStore();
        var digest = new string('a', 64);
        var receipt = new Guid(Convert.FromHexString(digest[..32])).ToString();
        var raw = "lcsc-order:order-" + digest;
        var at = "2026-09-15T00:00:00Z";
        var result = new SyncRunResult { IsSuccess = true, Message = "ok", PullResponse = new SyncPullResponse
        {
            ServerTime = at, SyncCursor = 1,
            StorageLocations = [new() { Id = "A", Name = "Shelf A", UpdatedAt = at, Deleted = false }],
            Components = [new() { Id = "part", Sku = "C12345", Name = "Part", Category = "IC", PackageName = "SMD",
                Location = "A", Quantity = 10, MinStock = 0, UpdatedAt = at, Deleted = false, InventoryUnitCost = "3",
                Allocations = [new() { LocationId = "A", Quantity = 10 }] }],
            StockMovements = [new() { Id = "remote-order", ComponentId = "part", MovementType = "inbound", Quantity = 10,
                Reason = "Order", HappenedAt = at, UpdatedAt = at, Deleted = false, LocationId = "A",
                PurchaseAmount = "30", CostAmount = "30", SourceRef = "lcsc-order:v1:" + digest }],
        } };
        store.ApplySyncResult(result, [], string.Empty);
        Assert.True(store.IsBatchReceiptCommitted(receipt, raw));
        Assert.False(store.IsBatchReceiptCommitted(Guid.NewGuid().ToString(), raw));
        var duplicate = new ComponentVault.WinUI.Services.BatchInbound.BatchInboundLine(receipt, raw, "C12345", 10, "A", "Part", "IC", "SMD", "", "30");
        Assert.True(store.CommitBatchInbound(new([duplicate])).IsSuccess);
        Assert.Empty(store.CreateSyncEnvelope().QueuedEntities);
    }

    [Fact]
    public void ApplySyncResult_PreservesQueueEntryEditedAfterSnapshot()
    {
        var store = CreateStore();
        var component = store.SaveComponent(CreateDraft("first"));
        var snapshot = store.CreateSyncEnvelope();

        store.SaveComponent(CreateDraft("second", component.Id));
        var pushed = Assert.Single(snapshot.PushRequest.Components);
        var success = new SyncRunResult
        {
            IsSuccess = true, Message = "ok", PullResponse = new SyncPullResponse
            {
                ServerTime = "2026-09-15T00:00:00Z", SyncCursor = 7,
                Components = [new SyncComponentDto
                {
                    Id = pushed.Id, Sku = pushed.Sku, Name = pushed.Name, Category = pushed.Category,
                    PackageName = pushed.PackageName, Location = pushed.Location, Description = pushed.Description,
                    Quantity = pushed.Quantity, MinStock = pushed.MinStock, UpdatedAt = pushed.UpdatedAt,
                    Deleted = pushed.Deleted, Allocations = pushed.Allocations,
                }],
            },
        };
        store.ApplySyncResult(success, snapshot.QueuedEntities, string.Empty);

        var remaining = store.CreateSyncEnvelope();
        var queued = Assert.Single(remaining.QueuedEntities);
        Assert.NotEqual(snapshot.QueuedEntities[0].EntityUpdatedAt, queued.EntityUpdatedAt);
        Assert.Equal("second", Assert.Single(remaining.PushRequest.Components).Description);
        Assert.Equal(snapshot.QueuedEntities[0].EntityUpdatedAt, Assert.Single(remaining.PushRequest.Components).BaseUpdatedAt);
        Assert.Equal(7, remaining.Cursor);
    }

    [Fact]
    public void ApplySyncResult_AcknowledgesEquivalentTimestampFormats()
    {
        var store = CreateStore();
        store.SaveComponent(CreateDraft("first"));
        var snapshot = Assert.Single(store.CreateSyncEnvelope().QueuedEntities);
        var instant = DateTimeOffset.Parse(snapshot.EntityUpdatedAt, CultureInfo.InvariantCulture);
        var equivalentSnapshot = new SyncEntityReference
        {
            EntityType = snapshot.EntityType,
            EntityId = snapshot.EntityId,
            EntityUpdatedAt = instant.ToOffset(TimeSpan.FromHours(8)).ToString("O"),
        };

        store.ApplySyncResult(CreateSuccess(syncCursor: 1), [equivalentSnapshot], string.Empty);

        Assert.Empty(store.CreateSyncEnvelope().QueuedEntities);
        Assert.Contains("https://used.example", store.GetSyncConfiguration().LastSyncMessage);
    }

    [Fact]
    public void RapidEdits_GetDistinctMonotonicVersionsAndAbsentPullAdvancesUploadedBase()
    {
        var store=CreateStore(); var component=store.SaveComponent(CreateDraft("first")); var snapshot=store.CreateSyncEnvelope();
        var edited=store.SaveComponent(CreateDraft("second",component.Id));
        Assert.True(DateTimeOffset.Parse(edited.UpdatedAt)>DateTimeOffset.Parse(component.UpdatedAt));
        store.ApplySyncResult(CreateSuccess(2),snapshot.QueuedEntities,string.Empty);
        var pushed=Assert.Single(store.CreateSyncEnvelope().PushRequest.Components);
        Assert.Equal(snapshot.QueuedEntities.Single(x=>x.EntityType=="component").EntityUpdatedAt,pushed.BaseUpdatedAt);
        Assert.Equal("second",pushed.Description);
    }

    [Fact]
    public void ConcurrentRemoteVersion_DoesNotBecomeBaselineForPostSnapshotLocalEdit()
    {
        var store=CreateStore(); var component=store.SaveComponent(CreateDraft("first")); var snapshot=store.CreateSyncEnvelope();
        store.SaveComponent(CreateDraft("second",component.Id)); var pushed=Assert.Single(snapshot.PushRequest.Components);
        var remoteAt=DateTimeOffset.Parse(pushed.UpdatedAt).AddSeconds(1).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");
        var result=new SyncRunResult { IsSuccess=true,Message="ok",PullResponse=new SyncPullResponse { ServerTime=remoteAt,SyncCursor=3,Components=[new SyncComponentDto { Id=pushed.Id,Sku=pushed.Sku,Name=pushed.Name,Category=pushed.Category,PackageName=pushed.PackageName,Location=pushed.Location,Description="remote",Quantity=pushed.Quantity,MinStock=pushed.MinStock,UpdatedAt=remoteAt,Deleted=false,Allocations=pushed.Allocations }] } };
        store.ApplySyncResult(result,snapshot.QueuedEntities,string.Empty);
        var next=Assert.Single(store.CreateSyncEnvelope().PushRequest.Components);
        Assert.Equal("second",next.Description); Assert.Null(next.BaseUpdatedAt);
    }

    [Fact]
    public void SyncCursor_IsIndependentFromDisplayTimeAndPreservedUntilIdentityCheck()
    {
        var store = CreateStore();
        Assert.Null(store.CreateSyncEnvelope().Cursor);

        store.ApplySyncResult(CreateSuccess(syncCursor: 12), [], string.Empty);
        Assert.Equal(12, store.CreateSyncEnvelope().Cursor);

        store.ApplySyncResult(CreateSuccess(syncCursor: null), [], string.Empty);
        Assert.Null(store.CreateSyncEnvelope().Cursor);

        store.ApplySyncResult(CreateSuccess(syncCursor: 13), [], string.Empty);

        store.SaveSyncConfiguration(string.Empty, "https://fallback.example/", "token", true);
        Assert.Equal(13, store.CreateSyncEnvelope().Cursor);

        store.SaveSyncConfiguration("https://new-server.example/", "", "token", true);
        Assert.Equal(13, store.CreateSyncEnvelope().Cursor);
    }

    [Fact]
    public void ApplySyncResult_RejectsResponseAfterServerChanges()
    {
        var store = CreateStore();
        store.SaveSyncConfiguration("https://old.example/", "", "token", true);
        store.SaveComponent(CreateDraft("queued"));
        var snapshot = store.CreateSyncEnvelope();

        store.SaveSyncConfiguration("https://new.example/", "", "token", true);
        var applied = store.ApplySyncResult(
            CreateSuccess(syncCursor: 99),
            snapshot.QueuedEntities,
            snapshot.Settings.ServerBaseUrl
        );

        Assert.False(applied);
        var current = store.CreateSyncEnvelope();
        Assert.Null(current.Cursor);
        Assert.Single(current.QueuedEntities);
    }

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(_testRoot))
        {
            Directory.Delete(_testRoot, recursive: true);
        }
    }

    [Fact]
    public void ConnectionDraft_DoesNotPersistOrReplaceSavedConfiguration()
    {
        var store = CreateStore();
        store.SaveSyncConfiguration("https://saved.example/", "https://fallback.example/", "saved-token", true);
        var saved = store.GetSyncConfiguration();

        var draft = saved.WithDraftConnection(" https://draft.example/ ", " https://draft-fallback.example/ ", " draft-token ", false);

        Assert.Equal("https://draft.example", draft.ServerBaseUrl);
        Assert.Equal("https://draft-fallback.example", draft.FallbackServerBaseUrl);
        Assert.Equal("draft-token", draft.ApiToken);
        Assert.False(draft.AutoSyncEnabled);
        Assert.Equal(saved.DeviceId, draft.DeviceId);
        Assert.False(saved.MatchesConnectionDraft("https://draft.example/", "https://draft-fallback.example/", "draft-token", false));
        Assert.True(saved.MatchesConnectionDraft(" https://saved.example/ ", " https://fallback.example/ ", " saved-token ", true));

        var persisted = store.GetSyncConfiguration();
        Assert.Equal("https://saved.example", persisted.ServerBaseUrl);
        Assert.Equal("https://fallback.example", persisted.FallbackServerBaseUrl);
        Assert.Equal("saved-token", persisted.ApiToken);
        Assert.True(persisted.AutoSyncEnabled);
    }

    [Fact]
    public void Initialize_OldSettingsWithoutFallback_AddsEmptyFallbackAndPreservesValues()
    {
        Directory.CreateDirectory(_testRoot);
        var databasePath = Path.Combine(_testRoot, "legacy-settings.db");
        using (var connection = new SqliteConnection($"Data Source={databasePath}"))
        {
            connection.Open();
            using var command = connection.CreateCommand();
            command.CommandText = """
                CREATE TABLE sync_settings (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    device_id TEXT NOT NULL,
                    server_base_url TEXT NOT NULL DEFAULT '',
                    api_token TEXT NOT NULL DEFAULT '',
                    auto_sync_enabled INTEGER NOT NULL DEFAULT 0,
                    last_sync_cursor INTEGER,
                    last_synced_at TEXT,
                    last_sync_message TEXT NOT NULL DEFAULT ''
                );
                INSERT INTO sync_settings VALUES (
                    1, 'legacy-device', 'https://legacy.example', 'legacy-token', 1, 42,
                    '2026-09-01T00:00:00Z', 'legacy status'
                );
                """;
            command.ExecuteNonQuery();
        }

        var store = new InventoryStore(databasePath);
        store.Initialize();

        var settings = store.GetSyncConfiguration();
        Assert.Equal("legacy-device", settings.DeviceId);
        Assert.Equal("https://legacy.example", settings.ServerBaseUrl);
        Assert.Equal(string.Empty, settings.FallbackServerBaseUrl);
        Assert.Equal("legacy-token", settings.ApiToken);
        Assert.True(settings.AutoSyncEnabled);
        Assert.Equal("legacy status", settings.LastSyncMessage);
        Assert.Equal(42, store.CreateSyncEnvelope().Cursor);
        Assert.Contains("管理员", store.ValidateAndBindSyncIdentity(
            Identity("server-1", "ordinary-1", "user"), settings)!);
        Assert.Equal(42, store.CreateSyncEnvelope().Cursor);
    }

    [Fact]
    public void BoundIdentity_RejectsOtherAccountAndServerWithoutChangingQueueOrCursor()
    {
        var store = CreateStore();
        store.SaveSyncConfiguration("https://internal.example", "https://external.example", "first-key", true);
        store.SaveComponent(CreateDraft("local"));
        var settings = store.GetSyncConfiguration();
        Assert.Null(store.ValidateAndBindSyncIdentity(Identity("server-1", "account-1"), settings));

        store.SaveSyncConfiguration("https://external.example", "https://internal.example", "rotated-key", true);
        settings = store.GetSyncConfiguration();
        Assert.Null(store.ValidateAndBindSyncIdentity(Identity("server-1", "account-1"), settings));
        Assert.Contains("已绑定", store.ValidateAndBindSyncIdentity(Identity("server-1", "account-2"), settings)!);
        Assert.Contains("已绑定", store.ValidateAndBindSyncIdentity(Identity("server-2", "account-1"), settings)!);
        Assert.Single(store.CreateSyncEnvelope().QueuedEntities, item => item.EntityType == "component");
        Assert.Null(store.CreateSyncEnvelope().Cursor);
    }

    [Fact]
    public void PreviouslySyncedUnboundWorkspace_RequiresAdminIdentity()
    {
        var store = CreateStore();
        store.ApplySyncResult(CreateSuccess(24), [], string.Empty);
        var settings = store.GetSyncConfiguration();
        Assert.Contains("管理员", store.ValidateAndBindSyncIdentity(Identity("server-1", "user-1", "user"), settings)!);
        Assert.Equal(24, store.CreateSyncEnvelope().Cursor);
        Assert.Null(store.ValidateAndBindSyncIdentity(Identity("server-1", "admin-1"), settings));
    }

    [Fact]
    public void ChangedKeyDuringIdentityLookup_CannotBindOrApplyOldSyncResult()
    {
        var store = CreateStore();
        store.SaveSyncConfiguration("https://server.example", "", "old-key", true);
        var snapshot = store.CreateSyncEnvelope();
        store.SaveSyncConfiguration("https://server.example", "", "new-key", true);

        Assert.Contains("配置已变化", store.ValidateAndBindSyncIdentity(
            Identity("server-1", "account-1"), snapshot.Settings)!);
        Assert.False(store.ApplySyncResult(
            CreateSuccess(99), snapshot.QueuedEntities,
            snapshot.Settings.ServerBaseUrl, snapshot.Settings));
        Assert.Null(store.CreateSyncEnvelope().Cursor);
    }

    private static SyncAccountIdentity Identity(string server, string account, string role = "admin") =>
        new() { ServerId = server, AccountId = account, Name = "test", Role = role };

    private InventoryStore CreateStore()
    {
        var store = new InventoryStore(Path.Combine(_testRoot, "inventory.db"));
        store.Initialize();
        return store;
    }

    private static ComponentDraft CreateDraft(string description, string? id = null) =>
        new()
        {
            Id = id,
            Sku = "TEST-1",
            Name = "Test component",
            Category = "Test",
            PackageName = "DIP",
            Location = "A1",
            Description = description,
            Quantity = 1,
            MinStock = 0,
        };

    private static SyncRunResult CreateSuccess(long? syncCursor) =>
        new()
        {
            IsSuccess = true,
            Message = "ok",
            UsedServerBaseUrl = "https://used.example",
            PullResponse = new SyncPullResponse
            {
                ServerTime = "2026-09-15T00:00:00Z",
                SyncCursor = syncCursor,
            },
        };
}
