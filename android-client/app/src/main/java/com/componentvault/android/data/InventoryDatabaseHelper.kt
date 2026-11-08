package com.componentvault.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

class InventoryDatabaseHelper(private val appContext: Context) : SQLiteOpenHelper(
    appContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    init {
        createPreUpgradeSnapshotIfNeeded()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE components (
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
                deleted INTEGER NOT NULL DEFAULT 0,
                base_updated_at TEXT,
                inventory_unit_cost TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX idx_components_sku_active
            ON components(sku)
            WHERE deleted = 0
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE stock_movements (
                id TEXT PRIMARY KEY,
                component_id TEXT NOT NULL,
                movement_type TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                reason TEXT NOT NULL,
                note TEXT,
                happened_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                location_id TEXT,
                destination_location_id TEXT,
                purchase_amount TEXT,
                cost_amount TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sync_queue (
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                entity_updated_at TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY (entity_type, entity_id)
            )
            """.trimIndent(),
        )
        createImportLearningTables(db)
        createBatchOperationTables(db)
        createMultiLocationTables(db)
        createBackupTables(db)
        createBatchJlcTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createImportLearningTables(db)
        }
        if (oldVersion < 3) {
            createBatchOperationTables(db)
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE components ADD COLUMN base_updated_at TEXT")
            db.execSQL("ALTER TABLE stock_movements ADD COLUMN location_id TEXT")
            db.execSQL("ALTER TABLE stock_movements ADD COLUMN destination_location_id TEXT")
            createMultiLocationTables(db)
            db.execSQL(
                """
                INSERT OR IGNORE INTO storage_locations(id, name, updated_at, deleted)
                SELECT CASE WHEN TRIM(location) = '' THEN '待整理' ELSE TRIM(location) END,
                       CASE WHEN TRIM(location) = '' THEN '待整理' ELSE TRIM(location) END,
                       MAX(updated_at), 0
                FROM components
                GROUP BY CASE WHEN TRIM(location) = '' THEN '待整理' ELSE TRIM(location) END
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO component_allocations(component_id, location_id, quantity)
                SELECT id, CASE WHEN TRIM(location) = '' THEN '待整理' ELSE TRIM(location) END, quantity
                FROM components
                """.trimIndent(),
            )
            db.execSQL("UPDATE components SET base_updated_at = updated_at")
        }
        createBackupTables(db)
        if (oldVersion < 5) createBatchJlcTables(db)
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE components ADD COLUMN inventory_unit_cost TEXT")
            db.execSQL("ALTER TABLE stock_movements ADD COLUMN purchase_amount TEXT")
            db.execSQL("ALTER TABLE stock_movements ADD COLUMN cost_amount TEXT")
        }
    }

    private fun createImportLearningTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS import_learning_mappings (
                id TEXT PRIMARY KEY,
                source_type TEXT NOT NULL,
                source_sku TEXT,
                source_mpn TEXT,
                resolved_name TEXT,
                resolved_category TEXT,
                resolved_package_name TEXT,
                resolved_model TEXT,
                resolved_brand TEXT,
                resolved_description TEXT,
                confidence INTEGER NOT NULL DEFAULT 100,
                last_used_at TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_import_learning_source_sku
            ON import_learning_mappings(source_sku)
            WHERE source_sku IS NOT NULL AND TRIM(source_sku) != ''
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_import_learning_source_mpn
            ON import_learning_mappings(source_mpn)
            WHERE source_mpn IS NOT NULL AND TRIM(source_mpn) != ''
            """.trimIndent(),
        )
    }

    private fun createBatchOperationTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bom_releases (
                release_id TEXT PRIMARY KEY,
                batch_id TEXT NOT NULL,
                project_name TEXT NOT NULL,
                production_runs INTEGER NOT NULL CHECK (production_runs > 0),
                source_fingerprint TEXT NOT NULL,
                source_sheet TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_bom_releases_batch_id
            ON bom_releases(batch_id)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS component_hub_imports (
                source_fingerprint TEXT PRIMARY KEY,
                imported_count INTEGER NOT NULL CHECK (imported_count >= 0),
                skipped_count INTEGER NOT NULL CHECK (skipped_count >= 0),
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createMultiLocationTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS storage_locations (
                id TEXT PRIMARY KEY CHECK (LENGTH(id) BETWEEN 1 AND 120),
                name TEXT NOT NULL CHECK (LENGTH(name) BETWEEN 1 AND 200),
                updated_at TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1))
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS component_allocations (
                component_id TEXT NOT NULL,
                location_id TEXT NOT NULL,
                quantity INTEGER NOT NULL CHECK (quantity >= 0),
                PRIMARY KEY (component_id, location_id),
                FOREIGN KEY (component_id) REFERENCES components(id),
                FOREIGN KEY (location_id) REFERENCES storage_locations(id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_allocations_location ON component_allocations(location_id)")
    }

    private fun createBackupTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS inventory_backup_imports (fingerprint TEXT PRIMARY KEY, source TEXT NOT NULL, imported_at TEXT NOT NULL)")
    }

    private fun createBatchJlcTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS batch_jlc_receipts (
            row_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, component_id TEXT NOT NULL,
            movement_id TEXT NOT NULL, committed_at TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_batch_jlc_receipts_session ON batch_jlc_receipts(session_id)")
    }

    private fun createPreUpgradeSnapshotIfNeeded() {
        val databaseFile = appContext.getDatabasePath(DATABASE_NAME)
        if (!databaseFile.isFile) return
        val currentVersion = runCatching {
            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        }.getOrNull() ?: return
        if (currentVersion >= DATABASE_VERSION) return
        val backupDirectory = File(appContext.noBackupFilesDir, "database-preupgrade").apply { mkdirs() }
        val prefix = "component-vault-v$currentVersion-${System.currentTimeMillis()}"
        val database = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            database.rawQuery("PRAGMA busy_timeout=10000", null).use { cursor ->
                check(cursor.moveToFirst()) { "Unable to configure the pre-upgrade backup lock timeout." }
            }
            database.execSQL("BEGIN EXCLUSIVE")
            try {
                // EXCLUSIVE prevents writers and checkpoints while the coherent DB/WAL set is copied.
                listOf("", "-wal", "-shm").forEach { suffix ->
                    val source = File(databaseFile.path + suffix)
                    if (source.isFile) source.copyTo(
                        File(backupDirectory, prefix + ".db" + suffix),
                        overwrite = false,
                    )
                }
            } finally {
                database.execSQL("ROLLBACK")
            }
        } catch (error: Exception) {
            backupDirectory.listFiles { file -> file.name.startsWith(prefix) }
                ?.forEach(File::delete)
            throw IllegalStateException("Unable to create a consistent pre-upgrade inventory backup.", error)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DATABASE_NAME = "component-vault.db"
        const val DATABASE_VERSION = 6
    }
}
