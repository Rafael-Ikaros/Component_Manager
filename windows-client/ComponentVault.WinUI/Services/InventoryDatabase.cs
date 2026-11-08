using System.Globalization;
using Microsoft.Data.Sqlite;

namespace ComponentVault.WinUI.Services;

// Owns the existing database path, connection settings, schema, and one-time migration.
internal sealed class InventoryDatabase
{
    private readonly string _databasePath;
    private readonly Func<string> _utcNow;

    internal InventoryDatabase(string databasePath, Func<string> utcNow)
    {
        var root = Path.GetDirectoryName(databasePath);
        if (!string.IsNullOrWhiteSpace(root)) Directory.CreateDirectory(root);
        _databasePath = databasePath;
        _utcNow = utcNow;
    }

    internal void Initialize()
    {
        using var connection = OpenConnection();
        CreatePreUpgradeBackupIfNeeded(connection);
        using var transaction = connection.BeginTransaction();
        ExecuteSchema(connection);
        EnsureDefaultSettings(connection);
        transaction.Commit();
    }

    internal SqliteConnection OpenConnection()
    {
        var connection = new SqliteConnection($"Data Source={_databasePath}");
        connection.Open();

        using var pragma = connection.CreateCommand();
        pragma.CommandText = "PRAGMA foreign_keys = ON";
        pragma.ExecuteNonQuery();

        return connection;
    }

    private void CreatePreUpgradeBackupIfNeeded(SqliteConnection connection)
    {
        using var check = connection.CreateCommand();
        check.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='components'";
        if (Convert.ToInt32(check.ExecuteScalar(), CultureInfo.InvariantCulture) == 0) return;
        check.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='component_allocations'";
        if (Convert.ToInt32(check.ExecuteScalar(), CultureInfo.InvariantCulture) != 0) return;
        var directory = Path.GetDirectoryName(_databasePath)!;
        var backupPath = Path.Combine(directory, $"pre-inventory-protocol1-{DateTimeOffset.UtcNow:yyyyMMdd-HHmmss}.db");
        using var destination = new SqliteConnection($"Data Source={backupPath}");
        destination.Open();
        connection.BackupDatabase(destination);
    }

    private void ExecuteSchema(SqliteConnection connection)
    {
        var needsLegacyMigration = TableExists(connection, "components") && !TableExists(connection, "component_allocations");
        var statements = new[]
        {
            """
            CREATE TABLE IF NOT EXISTS components (
                id TEXT PRIMARY KEY,
                sku TEXT NOT NULL,
                name TEXT NOT NULL,
                category TEXT NOT NULL,
                package_name TEXT NOT NULL,
                location TEXT NOT NULL,
                description TEXT,
                quantity INTEGER NOT NULL CHECK (quantity >= 0),
                min_stock INTEGER NOT NULL CHECK (min_stock >= 0),
                updated_at TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_components_sku_active
            ON components(sku)
            WHERE deleted = 0
            """,
            """
            CREATE TABLE IF NOT EXISTS stock_movements (
                id TEXT PRIMARY KEY,
                component_id TEXT NOT NULL,
                movement_type TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                reason TEXT NOT NULL,
                note TEXT,
                happened_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (component_id) REFERENCES components(id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS storage_locations (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS component_allocations (
                component_id TEXT NOT NULL,
                location_id TEXT NOT NULL,
                quantity INTEGER NOT NULL CHECK(quantity >= 0),
                PRIMARY KEY(component_id, location_id),
                FOREIGN KEY(component_id) REFERENCES components(id),
                FOREIGN KEY(location_id) REFERENCES storage_locations(id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS batch_inbound_receipts (
                receipt_id TEXT PRIMARY KEY,
                committed_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS sync_queue (
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                entity_updated_at TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (entity_type, entity_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS sync_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                device_id TEXT NOT NULL,
                server_base_url TEXT NOT NULL DEFAULT '',
                fallback_server_base_url TEXT NOT NULL DEFAULT '',
                api_token TEXT NOT NULL DEFAULT '',
                auto_sync_enabled INTEGER NOT NULL DEFAULT 0,
                last_sync_cursor INTEGER,
                bound_server_id TEXT NOT NULL DEFAULT '',
                bound_account_id TEXT NOT NULL DEFAULT '',
                last_synced_at TEXT,
                last_sync_message TEXT NOT NULL DEFAULT '尚未同步。'
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS bom_consumption_batches (
                batch_id TEXT PRIMARY KEY,
                release_id TEXT NOT NULL UNIQUE,
                project_name TEXT NOT NULL,
                batch_quantity INTEGER NOT NULL CHECK (batch_quantity > 0),
                source_fingerprint TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS component_hub_imports (
                fingerprint TEXT PRIMARY KEY,
                imported_count INTEGER NOT NULL CHECK (imported_count >= 0),
                created_at TEXT NOT NULL
            )
            """,
        };

        foreach (var statement in statements)
        {
            using var command = connection.CreateCommand();
            command.CommandText = statement;
            command.ExecuteNonQuery();
        }

        EnsureColumnExists(connection, "sync_settings", "last_sync_cursor", "INTEGER");
        EnsureColumnExists(connection, "sync_settings", "fallback_server_base_url", "TEXT NOT NULL DEFAULT ''");
        EnsureColumnExists(connection, "sync_settings", "bound_server_id", "TEXT NOT NULL DEFAULT ''");
        EnsureColumnExists(connection, "sync_settings", "bound_account_id", "TEXT NOT NULL DEFAULT ''");
        EnsureColumnExists(connection, "components", "base_updated_at", "TEXT");
        EnsureColumnExists(connection, "stock_movements", "location_id", "TEXT");
        EnsureColumnExists(connection, "stock_movements", "destination_location_id", "TEXT");
        EnsureColumnExists(connection, "components", "inventory_unit_cost", "TEXT");
        EnsureColumnExists(connection, "stock_movements", "purchase_amount", "TEXT");
        EnsureColumnExists(connection, "stock_movements", "cost_amount", "TEXT");
        if (needsLegacyMigration) MigrateLegacyAllocations(connection);
    }

    private static bool TableExists(SqliteConnection connection, string name)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=$name";
        command.Parameters.AddWithValue("$name", name);
        return Convert.ToInt32(command.ExecuteScalar(), CultureInfo.InvariantCulture) != 0;
    }

    private void MigrateLegacyAllocations(SqliteConnection connection)
    {
        var now = _utcNow();
        using var locations = connection.CreateCommand();
        locations.CommandText =
            "INSERT OR IGNORE INTO storage_locations(id,name,updated_at,deleted) " +
            "SELECT CASE WHEN TRIM(location)='' THEN '__unassigned__' ELSE TRIM(location) END, " +
            "CASE WHEN TRIM(location)='' THEN '未分配' ELSE TRIM(location) END, $now, 0 FROM components";
        locations.Parameters.AddWithValue("$now", now);
        locations.ExecuteNonQuery();
        using var allocations = connection.CreateCommand();
        allocations.CommandText =
            "INSERT OR IGNORE INTO component_allocations(component_id,location_id,quantity) " +
            "SELECT id, CASE WHEN TRIM(location)='' THEN '__unassigned__' ELSE TRIM(location) END, quantity FROM components";
        allocations.ExecuteNonQuery();
        using var baselines = connection.CreateCommand();
        baselines.CommandText = "UPDATE components SET base_updated_at=updated_at WHERE base_updated_at IS NULL";
        baselines.ExecuteNonQuery();
        using var queueComponents = connection.CreateCommand();
        queueComponents.CommandText =
            "INSERT OR IGNORE INTO sync_queue(entity_type,entity_id,entity_updated_at,created_at) SELECT 'component',id,updated_at,$now FROM components";
        queueComponents.Parameters.AddWithValue("$now", now);
        queueComponents.ExecuteNonQuery();
        using var queueLocations = connection.CreateCommand();
        queueLocations.CommandText =
            "INSERT OR IGNORE INTO sync_queue(entity_type,entity_id,entity_updated_at,created_at) SELECT 'storage_location',id,updated_at,$now FROM storage_locations";
        queueLocations.Parameters.AddWithValue("$now", now);
        queueLocations.ExecuteNonQuery();
    }

    private static void EnsureColumnExists(
        SqliteConnection connection,
        string tableName,
        string columnName,
        string columnType
    )
    {
        using var inspect = connection.CreateCommand();
        inspect.CommandText = $"PRAGMA table_info({tableName})";
        using var reader = inspect.ExecuteReader();
        while (reader.Read())
        {
            if (string.Equals(reader.GetString(1), columnName, StringComparison.OrdinalIgnoreCase))
            {
                return;
            }
        }

        reader.Close();
        using var alter = connection.CreateCommand();
        alter.CommandText = $"ALTER TABLE {tableName} ADD COLUMN {columnName} {columnType}";
        alter.ExecuteNonQuery();
    }

    internal static void EnsureDefaultSettings(SqliteConnection connection)
    {
        using var insert = connection.CreateCommand();
        insert.CommandText =
            """
            INSERT INTO sync_settings (
                id,
                device_id,
                server_base_url,
                fallback_server_base_url,
                api_token,
                auto_sync_enabled,
                last_synced_at,
                last_sync_message
            )
            VALUES (
                1,
                $device_id,
                '',
                '',
                '',
                0,
                NULL,
                '尚未同步。'
            )
            ON CONFLICT(id) DO NOTHING
            """;
        insert.Parameters.AddWithValue(
            "$device_id",
            $"windows-{Environment.MachineName.ToLowerInvariant()}"
        );
        insert.ExecuteNonQuery();
    }

}
