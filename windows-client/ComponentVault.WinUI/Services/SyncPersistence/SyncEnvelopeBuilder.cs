using ComponentVault.WinUI.Models;
using Microsoft.Data.Sqlite;

namespace ComponentVault.WinUI.Services.SyncPersistence;

internal sealed class SyncEnvelopeBuilder(InventoryDatabase database, SyncSettingsRepository settings)
{
    private readonly InventoryDatabase _database = database;
    private readonly SyncSettingsRepository _settings = settings;

    public SyncEnvelope CreateSyncEnvelope()
    {
        using var connection = _database.OpenConnection();
        var settings = _settings.GetSyncConfiguration();
        var queuedEntities = GetQueuedEntities(connection);

        var components = new List<SyncComponentDto>();
        var stockMovements = new List<SyncStockMovementDto>();
        var storageLocations = GetAllStorageLocationDtos(connection).ToList();

        foreach (var entity in queuedEntities)
        {
            if (entity.EntityType == "component")
            {
                var component = GetComponentDtoById(connection, entity.EntityId);
                if (component is not null)
                {
                    components.Add(component);
                }
            }
            else if (entity.EntityType == "stock_movement")
            {
                var movement = GetMovementDtoById(connection, entity.EntityId);
                if (movement is not null)
                {
                    stockMovements.Add(movement);
                }
            }
            else if (entity.EntityType == "storage_location")
            {
                var location = GetStorageLocationDtoById(connection, entity.EntityId);
                if (location is not null && storageLocations.All(item => item.Id != location.Id)) storageLocations.Add(location);
            }
        }

        return new SyncEnvelope
        {
            Settings = settings,
            Cursor = SyncSettingsRepository.GetStoredSyncCursor(connection),
            QueuedEntities = queuedEntities,
            PushRequest = new SyncPushRequest
            {
                DeviceId = settings.DeviceId,
                Components = components,
                StockMovements = stockMovements,
                StorageLocations = storageLocations,
            },
        };
    }

    private static IReadOnlyList<SyncEntityReference> GetQueuedEntities(SqliteConnection connection)
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT entity_type, entity_id, entity_updated_at
            FROM sync_queue
            ORDER BY created_at ASC
            """;

        using var reader = command.ExecuteReader();
        var entities = new List<SyncEntityReference>();
        while (reader.Read())
        {
            entities.Add(
                new SyncEntityReference
                {
                    EntityType = reader.GetString(0),
                    EntityId = reader.GetString(1),
                    EntityUpdatedAt = reader.GetString(2),
                }
            );
        }

        return entities;
    }

    private static SyncComponentDto? GetComponentDtoById(
        SqliteConnection connection,
        string componentId
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT
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
                base_updated_at, inventory_unit_cost
            FROM components
            WHERE id = $id
            """;
        command.Parameters.AddWithValue("$id", componentId);

        using var reader = command.ExecuteReader();
        if (!reader.Read())
        {
            return null;
        }

        var dto = new SyncComponentDto
        {
            Id = reader.GetString(0),
            Sku = reader.GetString(1),
            Name = reader.GetString(2),
            Category = reader.GetString(3),
            PackageName = reader.GetString(4),
            Location = reader.GetString(5),
            Description = reader.IsDBNull(6) ? null : reader.GetString(6),
            Quantity = reader.GetInt32(7),
            MinStock = reader.GetInt32(8),
            UpdatedAt = reader.GetString(9),
            Deleted = reader.GetInt32(10) == 1,
            BaseUpdatedAt = reader.IsDBNull(11) ? null : reader.GetString(11),
            InventoryUnitCost = reader.IsDBNull(12) ? null : reader.GetString(12),
        };
        reader.Close();
        return new SyncComponentDto
        {
            Id = dto.Id, Sku = dto.Sku, Name = dto.Name, Category = dto.Category,
            PackageName = dto.PackageName, Location = dto.Location, Description = dto.Description,
            Quantity = dto.Quantity, MinStock = dto.MinStock, UpdatedAt = dto.UpdatedAt,
            Deleted = dto.Deleted, BaseUpdatedAt = dto.BaseUpdatedAt, InventoryUnitCost = dto.InventoryUnitCost,
            Allocations = InventoryQueries.GetAllocations(connection, componentId).Select(item => new SyncAllocationDto
            {
                LocationId = item.LocationId, Quantity = item.Quantity,
            }).ToArray(),
        };
    }

    private static SyncStorageLocationDto? GetStorageLocationDtoById(SqliteConnection connection, string id)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT id,name,updated_at,deleted FROM storage_locations WHERE id=$id";
        command.Parameters.AddWithValue("$id", id);
        using var reader = command.ExecuteReader();
        return reader.Read() ? new SyncStorageLocationDto
        {
            Id = reader.GetString(0), Name = reader.GetString(1), UpdatedAt = reader.GetString(2), Deleted = reader.GetInt32(3) != 0,
        } : null;
    }

    private static IReadOnlyList<SyncStorageLocationDto> GetAllStorageLocationDtos(SqliteConnection connection)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT id,name,updated_at,deleted FROM storage_locations ORDER BY id";
        using var reader = command.ExecuteReader();
        var result = new List<SyncStorageLocationDto>();
        while (reader.Read()) result.Add(new SyncStorageLocationDto
        {
            Id = reader.GetString(0), Name = reader.GetString(1), UpdatedAt = reader.GetString(2), Deleted = reader.GetInt32(3) != 0,
        });
        return result;
    }

    private static SyncStockMovementDto? GetMovementDtoById(
        SqliteConnection connection,
        string movementId
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT
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
                destination_location_id, purchase_amount, cost_amount
            FROM stock_movements
            WHERE id = $id
            """;
        command.Parameters.AddWithValue("$id", movementId);

        using var reader = command.ExecuteReader();
        if (!reader.Read())
        {
            return null;
        }

        return new SyncStockMovementDto
        {
            Id = reader.GetString(0),
            ComponentId = reader.GetString(1),
            MovementType = reader.GetString(2),
            Quantity = reader.GetInt32(3),
            Reason = reader.GetString(4),
            Note = reader.IsDBNull(5) ? null : reader.GetString(5),
            HappenedAt = reader.GetString(6),
            UpdatedAt = reader.GetString(7),
            Deleted = reader.GetInt32(8) == 1,
            LocationId = reader.IsDBNull(9) ? null : reader.GetString(9),
            DestinationLocationId = reader.IsDBNull(10) ? null : reader.GetString(10),
            PurchaseAmount = reader.IsDBNull(11) ? null : reader.GetString(11),
            CostAmount = reader.IsDBNull(12) ? null : reader.GetString(12),
        };
    }

}
