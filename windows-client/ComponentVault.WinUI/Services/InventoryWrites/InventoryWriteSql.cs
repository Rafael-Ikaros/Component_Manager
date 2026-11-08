using System.Globalization;
using Microsoft.Data.Sqlite;

namespace ComponentVault.WinUI.Services.InventoryWrites;

internal static class InventoryWriteSql
{
    private static readonly object TimestampLock = new();
    private static long _lastTimestampMilliseconds;

    internal static string? GetCost(SqliteConnection connection, string componentId)
    {
        using var command = connection.CreateCommand();
        command.CommandText="SELECT inventory_unit_cost FROM components WHERE id=$id";
        command.Parameters.AddWithValue("$id",componentId);
        return command.ExecuteScalar() as string;
    }
    internal static void SetCost(SqliteConnection connection, string componentId, string? average, string? movementId = null, string? purchaseAmount = null, string? costAmount = null)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE components SET inventory_unit_cost=$average WHERE id=$id";
        command.Parameters.AddWithValue("$average", (object?)average ?? DBNull.Value);
        command.Parameters.AddWithValue("$id", componentId);
        command.ExecuteNonQuery();
        if (movementId is null) return;
        using var movement = connection.CreateCommand();
        movement.CommandText = "UPDATE stock_movements SET purchase_amount=$purchase,cost_amount=$cost WHERE id=$id";
        movement.Parameters.AddWithValue("$purchase", (object?)purchaseAmount ?? DBNull.Value);
        movement.Parameters.AddWithValue("$cost", (object?)costAmount ?? DBNull.Value);
        movement.Parameters.AddWithValue("$id", movementId);
        movement.ExecuteNonQuery();
    }

    internal static int GetAllocationQuantity(SqliteConnection connection, string componentId, string locationId)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT quantity FROM component_allocations WHERE component_id=$component_id AND location_id=$location_id";
        command.Parameters.AddWithValue("$component_id", componentId);
        command.Parameters.AddWithValue("$location_id", locationId);
        return command.ExecuteScalar() is { } value ? Convert.ToInt32(value, CultureInfo.InvariantCulture) : 0;
    }

    internal static void SetAllocationQuantity(
        SqliteConnection connection, string componentId, string locationId, int quantity,
        bool reviveLocation = true)
    {
        if (quantity < 0) throw new ArgumentOutOfRangeException(nameof(quantity));
        if (reviveLocation)
        {
            UpsertLocalLocation(connection, locationId, locationId, UtcNow());
        }
        else
        {
            // A deleted component's historical allocation may refer to a deleted location.
            // Supply a deleted placeholder only when the location is missing for the FK.
            using var location = connection.CreateCommand();
            location.CommandText = "INSERT INTO storage_locations(id,name,updated_at,deleted) VALUES($id,$id,$at,1) ON CONFLICT(id) DO NOTHING";
            location.Parameters.AddWithValue("$id", locationId);
            location.Parameters.AddWithValue("$at", UtcNow());
            location.ExecuteNonQuery();
        }
        using var command = connection.CreateCommand();
        command.CommandText = "INSERT INTO component_allocations(component_id,location_id,quantity) VALUES($component_id,$location_id,$quantity) " +
            "ON CONFLICT(component_id,location_id) DO UPDATE SET quantity=excluded.quantity";
        command.Parameters.AddWithValue("$component_id", componentId);
        command.Parameters.AddWithValue("$location_id", locationId);
        command.Parameters.AddWithValue("$quantity", quantity);
        command.ExecuteNonQuery();
    }

    internal static void UpsertLocalLocation(SqliteConnection connection, string id, string name, string updatedAt)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "INSERT INTO storage_locations(id,name,updated_at,deleted) VALUES($id,$name,$updated_at,0) " +
            "ON CONFLICT(id) DO UPDATE SET name=CASE WHEN storage_locations.name=storage_locations.id THEN excluded.name ELSE storage_locations.name END, deleted=0";
        command.Parameters.AddWithValue("$id", id);
        command.Parameters.AddWithValue("$name", name);
        command.Parameters.AddWithValue("$updated_at", updatedAt);
        command.ExecuteNonQuery();
    }

    internal static string NormalizeLocationId(string? value) => string.IsNullOrWhiteSpace(value) ? "__unassigned__" : value.Trim();

    internal static string UtcNow()
    {
        lock (TimestampLock)
        {
            var wallClock = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            _lastTimestampMilliseconds = Math.Max(wallClock, checked(_lastTimestampMilliseconds + 1));
            return DateTimeOffset.FromUnixTimeMilliseconds(_lastTimestampMilliseconds).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);
        }
    }

    internal static void EnqueueEntity(
        SqliteConnection connection,
        string entityType,
        string entityId,
        string updatedAt
    )
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            INSERT INTO sync_queue (
                entity_type,
                entity_id,
                entity_updated_at,
                created_at
            ) VALUES (
                $entity_type,
                $entity_id,
                $entity_updated_at,
                $created_at
            )
            ON CONFLICT(entity_type, entity_id) DO UPDATE SET
                entity_updated_at = excluded.entity_updated_at,
                created_at = excluded.created_at
            """;
        command.Parameters.AddWithValue("$entity_type", entityType);
        command.Parameters.AddWithValue("$entity_id", entityId);
        command.Parameters.AddWithValue("$entity_updated_at", updatedAt);
        command.Parameters.AddWithValue("$created_at", UtcNow());
        command.ExecuteNonQuery();
    }

}
