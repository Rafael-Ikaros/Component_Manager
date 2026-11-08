using System.Globalization;
using ComponentVault.WinUI.Models;
using Microsoft.Data.Sqlite;

namespace ComponentVault.WinUI.Services;

// Read-only inventory queries share the caller's connection for transactional reads.
internal sealed class InventoryQueries
{
    private readonly Func<SqliteConnection> _openConnection;
    internal InventoryQueries(Func<SqliteConnection> openConnection) => _openConnection = openConnection;
    private SqliteConnection OpenConnection() => _openConnection();

    internal DashboardSnapshot GetDashboardSnapshot()
    {
        using var connection = OpenConnection();

        var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT
                COUNT(*) AS component_count,
                COALESCE(SUM(quantity), 0) AS total_units,
                COALESCE(SUM(CASE WHEN quantity <= min_stock THEN 1 ELSE 0 END), 0) AS low_stock_count
            FROM components
            WHERE deleted = 0
            """;

        using var reader = command.ExecuteReader();
        reader.Read();

        return new DashboardSnapshot
        {
            ComponentCount = reader.GetInt32(0),
            TotalUnits = reader.GetInt32(1),
            LowStockCount = reader.GetInt32(2),
            MovementCount = GetMovementCount(connection),
        };
    }

    internal IReadOnlyList<ComponentRecord> GetComponents()
    {
        using var connection = OpenConnection();
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
                COALESCE(description, '') AS description,
                quantity,
                min_stock,
                updated_at,
                deleted,
                base_updated_at, inventory_unit_cost
            FROM components
            WHERE deleted = 0
            ORDER BY updated_at DESC, name COLLATE NOCASE ASC
            """;

        using var reader = command.ExecuteReader();
        var components = ReadComponents(reader);
        reader.Close();
        return AttachAllocations(connection, components);
    }

    internal IReadOnlyList<ComponentRecord> GetLowStockComponents()
    {
        using var connection = OpenConnection();
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
                COALESCE(description, '') AS description,
                quantity,
                min_stock,
                updated_at,
                deleted,
                base_updated_at, inventory_unit_cost
            FROM components
            WHERE deleted = 0 AND quantity <= min_stock
            ORDER BY quantity ASC, updated_at DESC
            """;

        using var reader = command.ExecuteReader();
        var components = ReadComponents(reader);
        reader.Close();
        return AttachAllocations(connection, components);
    }

    internal IReadOnlyList<ComponentRecord> GetActiveComponentsForSelection()
    {
        return GetComponents();
    }

    internal ComponentRecord? FindActiveComponentBySku(string sku)
    {
        if (string.IsNullOrWhiteSpace(sku)) return null;
        using var connection = OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT id FROM components WHERE UPPER(sku)=UPPER($sku) AND deleted=0 LIMIT 1";
        command.Parameters.AddWithValue("$sku", sku.Trim());
        var id = command.ExecuteScalar() as string;
        return id is null ? null : GetComponentById(connection, id);
    }

    internal IReadOnlyList<StorageLocationRecord> GetStorageLocations(bool includeDeleted = false)
    {
        using var connection = OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT id,name,updated_at,deleted FROM storage_locations " +
            (includeDeleted ? string.Empty : "WHERE deleted=0 ") + "ORDER BY id COLLATE NOCASE";
        using var reader = command.ExecuteReader();
        var result = new List<StorageLocationRecord>();
        while (reader.Read()) result.Add(new(reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetInt32(3) != 0));
        return result;
    }

    internal IReadOnlyList<ComponentAllocationRecord> GetAllocations(string componentId)
    {
        using var connection = OpenConnection();
        return GetAllocations(connection, componentId);
    }

    internal IReadOnlyList<StockMovementRecord> GetMovements(int limit = 200)
    {
        using var connection = OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT
                m.id,
                m.component_id,
                COALESCE(c.sku, m.component_id) AS component_sku,
                COALESCE(c.name, m.component_id) AS component_name,
                m.movement_type,
                m.quantity,
                m.reason,
                COALESCE(m.note, '') AS note,
                m.happened_at,
                m.updated_at,
                m.deleted,
                m.location_id,
                m.destination_location_id, m.purchase_amount, m.cost_amount
            FROM stock_movements m
            LEFT JOIN components c ON c.id = m.component_id
            WHERE m.deleted = 0
            ORDER BY m.happened_at DESC, m.updated_at DESC
            LIMIT $limit
            """;
        command.Parameters.AddWithValue("$limit", limit);

        using var reader = command.ExecuteReader();
        return ReadMovements(reader);
    }

    internal IReadOnlyDictionary<string, long> GetCumulativeOutboundQuantities()
    {
        using var connection = OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT component_id, SUM(ABS(CAST(quantity AS INTEGER)))
            FROM stock_movements
            WHERE deleted = 0 AND LOWER(TRIM(movement_type)) = 'outbound'
            GROUP BY component_id
            """;
        using var reader = command.ExecuteReader();
        var totals = new Dictionary<string, long>(StringComparer.Ordinal);
        while (reader.Read()) totals[reader.GetString(0)] = reader.GetInt64(1);
        return totals;
    }

    private static int GetMovementCount(SqliteConnection connection)
    {
        using var command = connection.CreateCommand();
        command.CommandText =
            "SELECT COUNT(*) FROM stock_movements WHERE deleted = 0";
        return Convert.ToInt32(command.ExecuteScalar(), CultureInfo.InvariantCulture);
    }

    private static IReadOnlyList<ComponentRecord> ReadComponents(SqliteDataReader reader)
    {
        var components = new List<ComponentRecord>();
        while (reader.Read())
        {
            components.Add(
                new ComponentRecord
                {
                    Id = reader.GetString(0),
                    Sku = reader.GetString(1),
                    Name = reader.GetString(2),
                    Category = reader.GetString(3),
                    PackageName = reader.GetString(4),
                    Location = reader.GetString(5),
                    Description = reader.GetString(6),
                    Quantity = reader.GetInt32(7),
                    MinStock = reader.GetInt32(8),
                    UpdatedAt = reader.GetString(9),
                    Deleted = reader.GetInt32(10) == 1,
                    BaseUpdatedAt = reader.IsDBNull(11) ? null : reader.GetString(11),
                    InventoryUnitCost = reader.IsDBNull(12) ? null : reader.GetString(12),
                }
            );
        }

        return components;
    }

    private static IReadOnlyList<StockMovementRecord> ReadMovements(SqliteDataReader reader)
    {
        var movements = new List<StockMovementRecord>();
        while (reader.Read())
        {
            movements.Add(
                new StockMovementRecord
                {
                    Id = reader.GetString(0),
                    ComponentId = reader.GetString(1),
                    ComponentSku = reader.GetString(2),
                    ComponentName = reader.GetString(3),
                    MovementType = reader.GetString(4),
                    Quantity = reader.GetInt32(5),
                    Reason = reader.GetString(6),
                    Note = reader.GetString(7),
                    HappenedAt = reader.GetString(8),
                    UpdatedAt = reader.GetString(9),
                    Deleted = reader.GetInt32(10) == 1,
                    LocationId = reader.IsDBNull(11) ? null : reader.GetString(11),
                    DestinationLocationId = reader.IsDBNull(12) ? null : reader.GetString(12),
                    PurchaseAmount = reader.IsDBNull(13) ? null : reader.GetString(13),
                    CostAmount = reader.IsDBNull(14) ? null : reader.GetString(14),
                }
            );
        }

        return movements;
    }

    internal ComponentRecord GetComponentById(string componentId)
    {
        using var connection = OpenConnection();
        var component = GetComponentById(connection, componentId);
        return component ?? throw new InvalidOperationException("保存后未找到对应元器件。");
    }

    internal static ComponentRecord? GetComponentById(SqliteConnection connection, string componentId)
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
                COALESCE(description, '') AS description,
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
        ComponentRecord? component = reader.Read()
            ? new ComponentRecord
            {
                Id = reader.GetString(0),
                Sku = reader.GetString(1),
                Name = reader.GetString(2),
                Category = reader.GetString(3),
                PackageName = reader.GetString(4),
                Location = reader.GetString(5),
                Description = reader.GetString(6),
                Quantity = reader.GetInt32(7),
                MinStock = reader.GetInt32(8),
                UpdatedAt = reader.GetString(9),
                Deleted = reader.GetInt32(10) == 1,
                BaseUpdatedAt = reader.IsDBNull(11) ? null : reader.GetString(11),
                    InventoryUnitCost = reader.IsDBNull(12) ? null : reader.GetString(12),
            }
            : null;
        reader.Close();
        return component is null ? null : CloneComponent(component, GetAllocations(connection, component.Id));
    }

    internal static bool ComponentExists(SqliteConnection connection, string componentId)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM components WHERE id = $id";
        command.Parameters.AddWithValue("$id", componentId);
        return Convert.ToInt32(command.ExecuteScalar(), CultureInfo.InvariantCulture) > 0;
    }

    internal static IReadOnlyList<ComponentAllocationRecord> GetAllocations(SqliteConnection connection, string componentId)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT component_id,location_id,quantity FROM component_allocations WHERE component_id=$id ORDER BY location_id COLLATE NOCASE";
        command.Parameters.AddWithValue("$id", componentId);
        using var reader = command.ExecuteReader();
        var result = new List<ComponentAllocationRecord>();
        while (reader.Read()) result.Add(new(reader.GetString(0), reader.GetString(1), reader.GetInt32(2)));
        return result;
    }

    private static IReadOnlyList<ComponentRecord> AttachAllocations(SqliteConnection connection, IReadOnlyList<ComponentRecord> components) =>
        components.Select(component => CloneComponent(component, GetAllocations(connection, component.Id))).ToArray();

    private static ComponentRecord CloneComponent(ComponentRecord component, IReadOnlyList<ComponentAllocationRecord> allocations) => new()
    {
        Id = component.Id, Sku = component.Sku, Name = component.Name, Category = component.Category,
        PackageName = component.PackageName, Location = component.Location, Description = component.Description,
        Quantity = component.Quantity, MinStock = component.MinStock, UpdatedAt = component.UpdatedAt,
        Deleted = component.Deleted, CumulativeOutboundQuantity = component.CumulativeOutboundQuantity,
        BaseUpdatedAt = component.BaseUpdatedAt, InventoryUnitCost = component.InventoryUnitCost, Allocations = allocations,
    };

}
