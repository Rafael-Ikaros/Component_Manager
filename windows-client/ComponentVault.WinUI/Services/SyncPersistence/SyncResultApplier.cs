using ComponentVault.WinUI.Models;
using Microsoft.Data.Sqlite;
using static ComponentVault.WinUI.Services.InventoryWrites.InventoryWriteSql;

namespace ComponentVault.WinUI.Services.SyncPersistence;

internal sealed class SyncResultApplier(InventoryDatabase database)
{
    private readonly InventoryDatabase _database = database;

    public bool ApplySyncResult(
        SyncRunResult result,
        IReadOnlyList<SyncEntityReference> pushedEntities,
        string expectedServerBaseUrl,
        SyncConfiguration? expectedSettings = null
    )
    {
        if (result.PullResponse is null)
        {
            throw new InvalidOperationException("同步成功结果必须包含拉取数据。");
        }

        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();

        if (!SyncSettingsRepository.StoredServerMatches(connection, expectedServerBaseUrl)
            || expectedSettings is not null && !SyncSettingsRepository.StoredSyncConfigurationMatches(connection, expectedSettings))
        {
            transaction.Rollback();
            return false;
        }

        foreach (var location in result.PullResponse.StorageLocations)
        {
            if (HasPostSnapshotEdit(connection, pushedEntities, "storage_location", location.Id)) continue;
            UpsertRemoteStorageLocation(connection, location);
            RemoveQueuedIfSuperseded(connection, "storage_location", location.Id, location.UpdatedAt);
        }

        foreach (var component in result.PullResponse.Components)
        {
            if (HasPostSnapshotEdit(connection, pushedEntities, "component", component.Id))
            {
                var pushed = pushedEntities.FirstOrDefault(item => item.EntityType == "component" && item.EntityId == component.Id);
                if (pushed is not null && InstantsEqual(component.UpdatedAt, pushed.EntityUpdatedAt))
                    UpdateComponentBaseline(connection, component.Id, pushed.EntityUpdatedAt);
                continue;
            }
            UpsertRemoteComponent(connection, component);
            RemoveQueuedIfSuperseded(connection, "component", component.Id, component.UpdatedAt);
        }

        foreach (var movement in result.PullResponse.StockMovements)
        {
            if (HasPostSnapshotEdit(connection, pushedEntities, "stock_movement", movement.Id)) continue;
            UpsertRemoteMovement(connection, movement);
            RemoveQueuedIfSuperseded(
                connection,
                "stock_movement",
                movement.Id,
                movement.UpdatedAt
            );
        }

        foreach (var pushedEntity in pushedEntities)
        {
            if (pushedEntity.EntityType == "component"
                && !result.PullResponse.Components.Any(item => item.Id == pushedEntity.EntityId))
                UpdateComponentBaseline(connection, pushedEntity.EntityId, pushedEntity.EntityUpdatedAt);
            RemoveQueuedEntityIfSnapshotMatches(connection, pushedEntity);
        }

        SyncSettingsRepository.UpdateStoredSyncCursor(connection, result.PullResponse.SyncCursor);

        SyncSettingsRepository.UpdateStoredSyncStatus(
            connection,
            result.PullResponse.ServerTime,
            $"库存同步完成；BOM 预设与照片待完成{(string.IsNullOrWhiteSpace(result.UsedServerBaseUrl) ? string.Empty : $"（{result.UsedServerBaseUrl}）")}。上传 元器件:{result.AcceptedComponents} 变动:{result.AcceptedStockMovements}；下载 元器件:{result.PullResponse.Components.Count} 变动:{result.PullResponse.StockMovements.Count}。",
            preserveTimestamp: false
        );

        transaction.Commit();
        return true;
    }

    private static void RemoveQueuedIfSuperseded(
        SqliteConnection connection,
        string entityType,
        string entityId,
        string remoteUpdatedAt
    )
    {
        var queuedUpdatedAt = GetQueuedEntityUpdatedAt(connection, entityType, entityId);
        if (
            queuedUpdatedAt is not null
            && InventoryStore.TryCompareInstants(queuedUpdatedAt, remoteUpdatedAt, out var comparison)
            && comparison <= 0
        )
        {
            RemoveQueuedEntity(connection, entityType, entityId, queuedUpdatedAt);
        }
    }

    private static void RemoveQueuedEntityIfSnapshotMatches(
        SqliteConnection connection,
        SyncEntityReference snapshot
    )
    {
        var queuedUpdatedAt = GetQueuedEntityUpdatedAt(
            connection,
            snapshot.EntityType,
            snapshot.EntityId
        );
        if (
            queuedUpdatedAt is not null
            && InventoryStore.TryCompareInstants(queuedUpdatedAt, snapshot.EntityUpdatedAt, out var comparison)
            && comparison == 0
        )
        {
            RemoveQueuedEntity(
                connection,
                snapshot.EntityType,
                snapshot.EntityId,
                queuedUpdatedAt
            );
        }
    }

    private static string? GetQueuedEntityUpdatedAt(
        SqliteConnection connection,
        string entityType,
        string entityId
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            "SELECT entity_updated_at FROM sync_queue WHERE entity_type = $entity_type AND entity_id = $entity_id";
        command.Parameters.AddWithValue("$entity_type", entityType);
        command.Parameters.AddWithValue("$entity_id", entityId);
        return command.ExecuteScalar() as string;
    }

    private static bool HasPostSnapshotEdit(SqliteConnection connection, IReadOnlyList<SyncEntityReference> pushed, string type, string id)
    {
        var current = GetQueuedEntityUpdatedAt(connection, type, id);
        if (current is null) return false;
        var snapshot = pushed.FirstOrDefault(item => item.EntityType == type && item.EntityId == id);
        return snapshot is null || !string.Equals(current, snapshot.EntityUpdatedAt, StringComparison.Ordinal);
    }

    private static void UpdateComponentBaseline(SqliteConnection connection, string id, string serverUpdatedAt)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE components SET base_updated_at=$base WHERE id=$id";
        command.Parameters.AddWithValue("$base", serverUpdatedAt);
        command.Parameters.AddWithValue("$id", id);
        command.ExecuteNonQuery();
    }

    private static bool InstantsEqual(string left, string right) => InventoryStore.TryCompareInstants(left, right, out var comparison) && comparison == 0;

    private static void RemoveQueuedEntity(
        SqliteConnection connection,
        string entityType,
        string entityId,
        string expectedUpdatedAt
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            DELETE FROM sync_queue
            WHERE entity_type = $entity_type
              AND entity_id = $entity_id
              AND entity_updated_at = $entity_updated_at
            """;
        command.Parameters.AddWithValue("$entity_type", entityType);
        command.Parameters.AddWithValue("$entity_id", entityId);
        command.Parameters.AddWithValue("$entity_updated_at", expectedUpdatedAt);
        command.ExecuteNonQuery();
    }

    private static void UpsertRemoteComponent(
        SqliteConnection connection,
        SyncComponentDto component
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            INSERT INTO components (
                id,
                sku,
                name,
                category,
                package_name,
                location,
                description,
                quantity,
                min_stock,
                updated_at,
                deleted,
                base_updated_at
            ) VALUES (
                $id,
                $sku,
                $name,
                $category,
                $package_name,
                $location,
                $description,
                $quantity,
                $min_stock,
                $updated_at,
                $deleted,
                $updated_at
            )
            ON CONFLICT(id) DO UPDATE SET
                sku = excluded.sku,
                name = excluded.name,
                category = excluded.category,
                package_name = excluded.package_name,
                location = excluded.location,
                description = excluded.description,
                quantity = excluded.quantity,
                min_stock = excluded.min_stock,
                updated_at = excluded.updated_at,
                deleted = excluded.deleted,
                base_updated_at = excluded.updated_at
            """;
        command.Parameters.AddWithValue("$id", component.Id);
        command.Parameters.AddWithValue("$sku", component.Sku);
        command.Parameters.AddWithValue("$name", component.Name);
        command.Parameters.AddWithValue("$category", component.Category);
        command.Parameters.AddWithValue("$package_name", component.PackageName);
        command.Parameters.AddWithValue("$location", component.Location);
        command.Parameters.AddWithValue("$description", (object?)component.Description ?? DBNull.Value);
        command.Parameters.AddWithValue("$quantity", component.Quantity);
        command.Parameters.AddWithValue("$min_stock", component.MinStock);
        command.Parameters.AddWithValue("$updated_at", component.UpdatedAt);
        command.Parameters.AddWithValue("$deleted", component.Deleted ? 1 : 0);
        command.ExecuteNonQuery();
        SetCost(connection, component.Id, component.InventoryUnitCost);
        var remoteAllocations = component.Allocations ??
            [new SyncAllocationDto { LocationId = NormalizeLocationId(component.Location), Quantity = component.Quantity }];
        if (remoteAllocations.Count == 0 || remoteAllocations.Sum(item => (long)item.Quantity) != component.Quantity)
            throw new InvalidDataException($"远端元器件 {component.Id} 的库位分配与总库存不一致。");
        using var clear = connection.CreateCommand();
        clear.CommandText = "DELETE FROM component_allocations WHERE component_id=$id";
        clear.Parameters.AddWithValue("$id", component.Id);
        clear.ExecuteNonQuery();
        foreach (var allocation in remoteAllocations)
            SetAllocationQuantity(connection, component.Id, allocation.LocationId, allocation.Quantity,
                reviveLocation: !component.Deleted);
    }

    private static void UpsertRemoteStorageLocation(SqliteConnection connection, SyncStorageLocationDto location)
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            "INSERT INTO storage_locations(id,name,updated_at,deleted) VALUES($id,$name,$updated_at,$deleted) " +
            "ON CONFLICT(id) DO UPDATE SET name=excluded.name,updated_at=excluded.updated_at,deleted=excluded.deleted " +
            "WHERE excluded.updated_at>=storage_locations.updated_at";
        command.Parameters.AddWithValue("$id", location.Id);
        command.Parameters.AddWithValue("$name", location.Name);
        command.Parameters.AddWithValue("$updated_at", location.UpdatedAt);
        command.Parameters.AddWithValue("$deleted", location.Deleted ? 1 : 0);
        command.ExecuteNonQuery();
    }

    private static void UpsertRemoteMovement(
        SqliteConnection connection,
        SyncStockMovementDto movement
    )
    {
        using var check = connection.CreateCommand();
        check.CommandText =
            "SELECT updated_at FROM stock_movements WHERE id = $id";
        check.Parameters.AddWithValue("$id", movement.Id);
        var existingUpdatedAt = check.ExecuteScalar() as string;
        if (
            existingUpdatedAt is not null
            && string.CompareOrdinal(existingUpdatedAt, movement.UpdatedAt) > 0
        )
        {
            return;
        }

        using var command = connection.CreateCommand();
        command.CommandText =
            """
            INSERT INTO stock_movements (
                id,
                component_id,
                movement_type,
                quantity,
                reason,
                note,
                happened_at,
                updated_at,
                deleted,
                location_id,
                destination_location_id
            ) VALUES (
                $id,
                $component_id,
                $movement_type,
                $quantity,
                $reason,
                $note,
                $happened_at,
                $updated_at,
                $deleted,
                $location_id,
                $destination_location_id
            )
            ON CONFLICT(id) DO UPDATE SET
                component_id = excluded.component_id,
                movement_type = excluded.movement_type,
                quantity = excluded.quantity,
                reason = excluded.reason,
                note = excluded.note,
                happened_at = excluded.happened_at,
                updated_at = excluded.updated_at,
                deleted = excluded.deleted,
                location_id = excluded.location_id,
                destination_location_id = excluded.destination_location_id
            """;
        command.Parameters.AddWithValue("$id", movement.Id);
        command.Parameters.AddWithValue("$component_id", movement.ComponentId);
        command.Parameters.AddWithValue("$movement_type", movement.MovementType);
        command.Parameters.AddWithValue("$quantity", movement.Quantity);
        command.Parameters.AddWithValue("$reason", movement.Reason);
        command.Parameters.AddWithValue(
            "$note",
            movement.Note is null ? DBNull.Value : movement.Note
        );
        command.Parameters.AddWithValue("$happened_at", movement.HappenedAt);
        command.Parameters.AddWithValue("$updated_at", movement.UpdatedAt);
        command.Parameters.AddWithValue("$deleted", movement.Deleted ? 1 : 0);
        command.Parameters.AddWithValue("$location_id", (object?)movement.LocationId ?? DBNull.Value);
        command.Parameters.AddWithValue("$destination_location_id", (object?)movement.DestinationLocationId ?? DBNull.Value);
        command.ExecuteNonQuery();
        using var costs = connection.CreateCommand();
        costs.CommandText = "UPDATE stock_movements SET purchase_amount=$purchase,cost_amount=$cost WHERE id=$id";
        costs.Parameters.AddWithValue("$purchase", (object?)movement.PurchaseAmount ?? DBNull.Value);
        costs.Parameters.AddWithValue("$cost", (object?)movement.CostAmount ?? DBNull.Value);
        costs.Parameters.AddWithValue("$id", movement.Id);
        costs.ExecuteNonQuery();
    }}
