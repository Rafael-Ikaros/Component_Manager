using System.Globalization;
using ComponentVault.WinUI.Models;
using ComponentVault.WinUI.Services.Bom;
using ComponentVault.WinUI.Services.Migration;
using ComponentVault.WinUI.Services.BatchInbound;
using ComponentVault.WinUI.Services.InventoryWrites;
using ComponentVault.WinUI.Services.SyncPersistence;
using static ComponentVault.WinUI.Services.InventoryWrites.InventoryWriteSql;

namespace ComponentVault.WinUI.Services;

public sealed class InventoryStore
{
    private readonly InventoryDatabase _database;
    private readonly InventoryQueries _queries;
    private readonly InventoryLocationWriter _locations;
    private readonly InventoryBatchWriter _batch;
    private readonly InventoryComponentWriter _components;
    private readonly InventoryStockWriter _stock;
    private readonly InventoryBomImportWriter _bomImport;
    private readonly SyncSettingsRepository _syncSettings;
    private readonly SyncEnvelopeBuilder _syncEnvelope;
    private readonly SyncResultApplier _syncResults;
    internal string DatabasePath { get; }

    public InventoryStore()
        : this(
            Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "ComponentVault",
                "component-vault.db"
            )
        )
    {
    }

    internal InventoryStore(string databasePath)
    {
        DatabasePath = databasePath;
        _database = new InventoryDatabase(databasePath, UtcNow);
        _queries = new InventoryQueries(_database.OpenConnection);
        _locations = new InventoryLocationWriter(_database);
        _batch = new InventoryBatchWriter(_database);
        _components = new InventoryComponentWriter(_database);
        _stock = new InventoryStockWriter(_database);
        _bomImport = new InventoryBomImportWriter(_database);
        _syncSettings = new SyncSettingsRepository(_database);
        _syncEnvelope = new SyncEnvelopeBuilder(_database, _syncSettings);
        _syncResults = new SyncResultApplier(_database);
    }

    public void Initialize() => _database.Initialize();

    public DashboardSnapshot GetDashboardSnapshot() => _queries.GetDashboardSnapshot();
    public IReadOnlyList<ComponentRecord> GetComponents() => _queries.GetComponents();
    public IReadOnlyList<ComponentRecord> GetLowStockComponents() => _queries.GetLowStockComponents();
    public IReadOnlyList<ComponentRecord> GetActiveComponentsForSelection() => _queries.GetActiveComponentsForSelection();
    public ComponentRecord? FindActiveComponentBySku(string sku) => _queries.FindActiveComponentBySku(sku);
    public IReadOnlyList<StorageLocationRecord> GetStorageLocations(bool includeDeleted = false) => _queries.GetStorageLocations(includeDeleted);
    public IReadOnlyList<ComponentAllocationRecord> GetAllocations(string componentId) => _queries.GetAllocations(componentId);

    public OperationResult SaveStorageLocation(string id, string name) => _locations.SaveStorageLocation(id, name);
    public OperationResult CreateStorageLocation(string id, string name) => _locations.CreateStorageLocation(id, name);
    public OperationResult UpdateStorageLocation(string id, string name) => _locations.UpdateStorageLocation(id, name);
    public OperationResult DeleteStorageLocation(string id) => _locations.DeleteStorageLocation(id);

    public IReadOnlyList<StockMovementRecord> GetMovements(int limit = 200) => _queries.GetMovements(limit);

    public bool IsBatchReceiptCommitted(string receiptId) => _batch.IsBatchReceiptCommitted(receiptId);
    public static IReadOnlyList<BatchInboundSummary> SummarizeBatchInbound(IEnumerable<BatchInboundLine> lines) => InventoryBatchWriter.SummarizeBatchInbound(lines);
    public OperationResult CommitBatchInbound(BatchInboundCommitRequest request) => _batch.CommitBatchInbound(request);
    public OperationResult AppendCatalogInbound(string sku, int quantity, string locationId, string expectedUpdatedAt, string? newLocationName = null, string? purchaseAmount = null) =>
        _batch.AppendCatalogInbound(sku, quantity, locationId, expectedUpdatedAt, newLocationName, purchaseAmount);

    public IReadOnlyDictionary<string, long> GetCumulativeOutboundQuantities() => _queries.GetCumulativeOutboundQuantities();

    public SyncConfiguration GetSyncConfiguration() => _syncSettings.GetSyncConfiguration();
    public OperationResult SaveSyncConfiguration(string serverBaseUrl, string fallbackServerBaseUrl, string apiToken, bool autoSyncEnabled) =>
        _syncSettings.SaveSyncConfiguration(serverBaseUrl, fallbackServerBaseUrl, apiToken, autoSyncEnabled);

    public ComponentRecord SaveComponent(ComponentDraft draft, string? newLocationName = null) => _components.SaveComponent(draft, newLocationName);
    public OperationResult SoftDeleteComponent(string componentId) => _components.SoftDeleteComponent(componentId);

    public sealed record BatchTransferLine(string ComponentId, int Quantity, string ExpectedUpdatedAt);
    public sealed record BatchTransferRequest(string SourceLocationId, string DestinationLocationId, IReadOnlyList<BatchTransferLine> Lines);

    public OperationResult TransferBatch(BatchTransferRequest request) => _stock.TransferBatch(request);
    public OperationResult SetInventoryCost(string id, string version, string amount) => _components.SetInventoryCost(id, version, amount);
    public OperationResult RecordMovement(MovementEntryDraft draft) => _stock.RecordMovement(draft);
    public BomConfirmResult ConfirmBomConsumption(BomConfirmRequest request) => _bomImport.ConfirmBomConsumption(request);
    public OperationResult ImportComponentHub(ComponentHubImportRequest request) => _bomImport.ImportComponentHub(request);

    public (string ServerId, string AccountId) GetBoundSyncIdentity()
    {
        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT bound_server_id, bound_account_id FROM sync_settings WHERE id = 1";
        using var reader = command.ExecuteReader();
        return reader.Read() ? (reader.GetString(0), reader.GetString(1)) : ("", "");
    }

    public int GetPendingSyncCount()
    {
        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM sync_queue";
        return Convert.ToInt32(command.ExecuteScalar());
    }

    public SyncEnvelope CreateSyncEnvelope() => _syncEnvelope.CreateSyncEnvelope();
    public string? ValidateAndBindSyncIdentity(SyncAccountIdentity identity, SyncConfiguration settings) =>
        _syncSettings.ValidateAndBindSyncIdentity(identity, settings);
    public bool ApplySyncResult(SyncRunResult result, IReadOnlyList<SyncEntityReference> pushedEntities,
        string expectedServerBaseUrl, SyncConfiguration? expectedSettings = null) =>
        _syncResults.ApplySyncResult(result, pushedEntities, expectedServerBaseUrl, expectedSettings);
    public void UpdateSyncStatus(string message) => _syncSettings.UpdateSyncStatus(message);

    internal static bool TryCompareInstants(string left, string right, out int comparison)
    {
        if (
            DateTimeOffset.TryParse(
                left,
                CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal,
                out var leftInstant
            )
            && DateTimeOffset.TryParse(
                right,
                CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal,
                out var rightInstant
            )
        )
        {
            comparison = leftInstant.UtcTicks.CompareTo(rightInstant.UtcTicks);
            return true;
        }

        comparison = 0;
        return false;
    }


}

public sealed class SyncEnvelope
{
    public required SyncConfiguration Settings { get; init; }
    public required SyncPushRequest PushRequest { get; init; }
    public long? Cursor { get; init; }
    public required IReadOnlyList<SyncEntityReference> QueuedEntities { get; init; }
}

public sealed class SyncEntityReference
{
    public required string EntityType { get; init; }
    public required string EntityId { get; init; }
    public required string EntityUpdatedAt { get; init; }
}
