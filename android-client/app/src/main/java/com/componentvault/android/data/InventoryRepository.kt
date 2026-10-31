package com.componentvault.android.data

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.provider.Settings
import androidx.annotation.StringRes
import com.componentvault.android.R
import com.componentvault.android.model.AppPreferences
import com.componentvault.android.model.AppLanguage
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentImportResolution
import com.componentvault.android.model.ComponentOfficialLookupResult
import com.componentvault.android.model.ComponentOfficialMetadata
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.DashboardSnapshot
import com.componentvault.android.model.ImportLearningSummary
import com.componentvault.android.model.MovementEntryDraft
import com.componentvault.android.model.OperationResult
import com.componentvault.android.model.OcrEngineMode
import com.componentvault.android.model.MovementScanMatchStatus
import com.componentvault.android.model.MovementScanResolutionUiState
import com.componentvault.android.model.StockMovementRecord
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.model.StorageLocationSaveIntent
import com.componentvault.android.model.ComponentAllocationRecord
import com.componentvault.android.model.SyncConfiguration
import com.componentvault.android.model.parseImportDescription
import com.componentvault.android.model.officialParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class ServerOfficialDetails(val description: String?, val parameters: Map<String, String>)

internal fun parseServerOfficialDetails(response: JSONObject): ServerOfficialDetails = ServerOfficialDetails(
    description = response.optString("description").trim()
        .takeUnless { response.isNull("description") || it.isBlank() },
    parameters = response.optJSONObject("parameters")?.let { values ->
        buildMap {
            values.keys().forEach { key ->
                val value = values.optString(key).trim()
                if (key.isNotBlank() && value.isNotBlank()) put(key.trim(), value)
            }
        }
    }.orEmpty(),
)

class InventoryRepository(
    context: Context,
) {
    internal data class WorkbookPreview(val workbook:InventoryWorkbook,val newComponentCount:Int,val skippedComponentCount:Int,val newLocationCount:Int,val warnings:List<String>)
    private val appContext = context.applicationContext
    private val databaseHelper = InventoryDatabaseHelper(appContext)
    private val inventoryImageStore = InventoryImageStore(appContext)
    private val workbookService = InventoryWorkbookService(databaseHelper, inventoryImageStore)
    private val bomReleaseCommitter = BomReleaseCommitter(databaseHelper)
    private val componentHubImporter = ComponentHubImporter(databaseHelper)
    internal fun searchPublicCatalog(keyword: String, language: AppLanguage) = catalogService.searchPublicCatalog(keyword, language)
    private val preferences: SharedPreferences = appContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    private val syncConfigurationLock = Any()
    private val syncService = InventorySyncService(appContext, databaseHelper, preferences, syncConfigurationLock)
    private val catalogService = InventoryImportCatalog(appContext, databaseHelper, preferences, syncService)
    private val writeService = InventoryWriteService(appContext, databaseHelper, catalogService)

    init {
        ensureDefaultSettings()
        syncService.recoverInterruptedRun()
        catalogService.cleanupLookupCache()
        if (!preferences.getBoolean(InventoryPreferenceKeys.INVENTORY_PROTOCOL_MIGRATED, false)) {
            databaseHelper.writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    db.rawQuery("SELECT id, updated_at FROM components", null).use { cursor ->
                        while (cursor.moveToNext()) enqueueEntity(db, "component", cursor.getString(0), cursor.getString(1))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            preferences.edit()
                .remove(InventoryPreferenceKeys.SYNC_CURSOR)
                .putBoolean(InventoryPreferenceKeys.INVENTORY_PROTOCOL_MIGRATED, true)
                .commit()
        }
    }

    suspend fun loadDashboardSnapshot(): DashboardSnapshot = withContext(Dispatchers.IO) {
        databaseHelper.readableDatabase.use { db ->
            db.rawQuery(
                """
                SELECT
                    COUNT(*) AS component_count,
                    COALESCE(SUM(quantity), 0) AS total_units,
                    COALESCE(SUM(CASE WHEN quantity <= min_stock THEN 1 ELSE 0 END), 0) AS low_stock_count
                FROM components
                WHERE deleted = 0
                """.trimIndent(),
                null,
            ).use { cursor ->
                cursor.moveToFirst()
                DashboardSnapshot(
                    componentCount = cursor.getInt(cursor.getColumnIndexOrThrow("component_count")),
                    totalUnits = cursor.getInt(cursor.getColumnIndexOrThrow("total_units")),
                    lowStockCount = cursor.getInt(cursor.getColumnIndexOrThrow("low_stock_count")),
                    movementCount = countMovements(db),
                )
            }
        }
    }

    suspend fun loadComponents(): List<ComponentRecord> = withContext(Dispatchers.IO) {
        databaseHelper.readableDatabase.use { db ->
            db.rawQuery(
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
                    deleted
                FROM components
                WHERE deleted = 0
                ORDER BY updated_at DESC, name COLLATE NOCASE ASC
                """.trimIndent(),
                null,
            ).use(::readComponents)
        }
    }

    suspend fun loadLowStockComponents(): List<ComponentRecord> = withContext(Dispatchers.IO) {
        databaseHelper.readableDatabase.use { db ->
            db.rawQuery(
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
                    deleted
                FROM components
                WHERE deleted = 0 AND quantity <= min_stock
                ORDER BY quantity ASC, updated_at DESC
                """.trimIndent(),
                null,
            ).use(::readComponents)
        }
    }

    suspend fun loadMovements(limit: Int = 200): List<StockMovementRecord> = withContext(Dispatchers.IO) {
        databaseHelper.readableDatabase.use { db ->
            db.rawQuery(
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
                    m.destination_location_id
                FROM stock_movements m
                LEFT JOIN components c ON c.id = m.component_id
                WHERE m.deleted = 0
                ORDER BY m.happened_at DESC, m.updated_at DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(limit.toString()),
            ).use(::readMovements)
        }
    }

    suspend fun resolveComponentByScannedLabel(
        rawValue: String,
    ): MovementScanResolutionUiState = withContext(Dispatchers.IO) {
        val candidate = ComponentLabelCodec.parseScannedPayload(rawValue)
            ?: runCatching { ComponentImportParser.parseScannedQr(rawValue) }.getOrNull()
            ?: return@withContext MovementScanResolutionUiState(
                matchStatus = MovementScanMatchStatus.InvalidLabel,
                rawValue = rawValue.trim(),
            )

        databaseHelper.readableDatabase.use { db ->
            val matches = findActiveComponentsBySku(
                db = db,
                sku = candidate.sku,
            )

            when {
                matches.isEmpty() -> MovementScanResolutionUiState(
                    matchStatus = MovementScanMatchStatus.NotFound,
                    rawValue = rawValue.trim(),
                    parsedSku = candidate.sku,
                    parsedName = candidate.name,
                    parsedPackageName = candidate.packageName,
                    parsedLocation = extractWarehouseLocation(candidate),
                )

                matches.size == 1 -> MovementScanResolutionUiState(
                    matchStatus = MovementScanMatchStatus.Matched,
                    rawValue = rawValue.trim(),
                    parsedSku = candidate.sku,
                    parsedName = candidate.name,
                    parsedPackageName = candidate.packageName,
                    parsedLocation = extractWarehouseLocation(candidate),
                    matchedComponent = matches.single(),
                )

                else -> MovementScanResolutionUiState(
                    matchStatus = MovementScanMatchStatus.Ambiguous,
                    rawValue = rawValue.trim(),
                    parsedSku = candidate.sku,
                    parsedName = candidate.name,
                    parsedPackageName = candidate.packageName,
                    parsedLocation = extractWarehouseLocation(candidate),
                )
            }
        }
    }

    suspend fun loadImportLearningSummary(): ImportLearningSummary =
        catalogService.loadImportLearningSummary()

    suspend fun enrichImportCandidate(
        candidate: ComponentImportCandidate,
        appPreferences: AppPreferences,
        syncConfiguration: SyncConfiguration,
    ): ComponentImportResolution = catalogService.enrichImportCandidate(candidate, appPreferences, syncConfiguration)

    suspend fun lookupPartMetadata(
        syncConfiguration: SyncConfiguration,
        sku: String?,
        mpn: String?,
        name: String?,
        brand: String?,
        packageHint: String?,
        sourceType: String?,
    ): ComponentOfficialLookupResult = catalogService.lookupPartMetadata(
        syncConfiguration, sku, mpn, name, brand, packageHint, sourceType,
    )

    suspend fun clearImportLearningMappings(): OperationResult = catalogService.clearImportLearningMappings()

    internal fun readCachedLookup(sku: String, mpn: String, cacheScope: String? = null): ComponentOfficialMetadata? =
        catalogService.readCachedLookup(sku, mpn, cacheScope)

    internal fun cacheLookup(metadata: ComponentOfficialMetadata, cacheScope: String? = null) =
        catalogService.cacheLookup(metadata, cacheScope)


    internal fun clearLookupCache() = catalogService.clearLookupCache()

    fun loadSyncConfiguration(): SyncConfiguration {
        ensureDefaultSettings()
        val generation = preferences.getInt(InventoryPreferenceKeys.SYNC_CONFIG_GENERATION, 0)
        val currentRun = preferences.getInt(InventoryPreferenceKeys.SYNC_RUN_GENERATION, 0) == generation
        val pending = syncService.pendingSummary()
        return SyncConfiguration(
            syncState = if (currentRun) preferences.getString(InventoryPreferenceKeys.SYNC_RUN_STATE, "").orEmpty() else "",
            syncPhase = if (currentRun) preferences.getString(InventoryPreferenceKeys.SYNC_RUN_PHASE, "").orEmpty() else "",
            syncSummary = syncService.summary(currentRun, pending),
            bomLastSyncedAt = if (preferences.getInt(InventoryPreferenceKeys.BOM_SUCCESS_GENERATION, 0) == generation)
                preferences.getString(InventoryPreferenceKeys.BOM_LAST_SYNCED_AT, "").orEmpty() else "",
            pendingInventory = pending.first,
            pendingBomPresets = pending.second,
            deviceId = preferences.getString(InventoryPreferenceKeys.DEVICE_ID, "") ?: "",
            serverBaseUrl = preferences.getString(InventoryPreferenceKeys.SERVER_BASE_URL, "") ?: "",
            externalServerBaseUrl = preferences.getString(InventoryPreferenceKeys.EXTERNAL_SERVER_BASE_URL, "") ?: "",
            apiToken = preferences.getString(InventoryPreferenceKeys.API_TOKEN, "") ?: "",
            autoSyncEnabled = preferences.getBoolean(InventoryPreferenceKeys.AUTO_SYNC_ENABLED, false),
            lastSyncedAt = if (preferences.getInt(InventoryPreferenceKeys.INVENTORY_SUCCESS_GENERATION, 0) == generation)
                preferences.getString(InventoryPreferenceKeys.LAST_SYNCED_AT, text(R.string.sync_never)).orEmpty()
                else text(R.string.sync_never),
            lastSyncMessage = if (currentRun) preferences.getString(InventoryPreferenceKeys.LAST_SYNC_MESSAGE, text(R.string.sync_no_sync_yet)).orEmpty()
                else text(R.string.sync_no_sync_yet),
        )
    }

    fun loadAppPreferences(): AppPreferences {
        ensureDefaultSettings()
        return AppPreferences(
            defaultImportLocation = preferences.getString(KEY_DEFAULT_IMPORT_LOCATION, "") ?: "",
            lastImportLocation = preferences.getString(KEY_LAST_IMPORT_LOCATION, "") ?: "",
            defaultImportMinStock = preferences.getInt(KEY_DEFAULT_IMPORT_MIN_STOCK, 0),
            rememberLastImportLocation = preferences.getBoolean(KEY_REMEMBER_LAST_IMPORT_LOCATION, true),
            syncAfterLocalChanges = preferences.getBoolean(
                KEY_SYNC_AFTER_LOCAL_CHANGES,
                preferences.getBoolean(InventoryPreferenceKeys.AUTO_SYNC_ENABLED, false),
            ),
            enableLocalAutoRecognition = preferences.getBoolean(KEY_ENABLE_LOCAL_AUTO_RECOGNITION, true),
            preferAggressiveAutoRecognition = preferences.getBoolean(
                KEY_PREFER_AGGRESSIVE_AUTO_RECOGNITION,
                true,
            ),
            enableLocalImportLearning = preferences.getBoolean(KEY_ENABLE_LOCAL_IMPORT_LEARNING, true),
            enableServerJlcLookup = false,
            enablePublicJlcLookup = preferences.getBoolean("enable_public_jlc_lookup", true),
            ocrEngineMode = OcrEngineMode.fromStorageValue(
                preferences.getString(KEY_OCR_ENGINE_MODE, OcrEngineMode.Auto.storageValue),
            ),
            appLanguage = AppLanguage.fromStorageValue(
                preferences.getString(InventoryPreferenceKeys.APP_LANGUAGE, AppLanguage.ZhCn.storageValue),
            ),
        )
    }

    fun saveSyncConfiguration(
        serverBaseUrl: String,
        apiToken: String,
        autoSyncEnabled: Boolean,
        externalServerBaseUrl: String = "",
    ): OperationResult {
        ensureDefaultSettings()
        val normalizedServerUrl = serverBaseUrl.trim().trimEnd('/')
        synchronized(syncConfigurationLock) {
            val changed = normalizedServerUrl != preferences.getString(InventoryPreferenceKeys.SERVER_BASE_URL, "") ||
                apiToken.trim() != preferences.getString(InventoryPreferenceKeys.API_TOKEN, "") ||
                externalServerBaseUrl.trim().trimEnd('/') != preferences.getString(InventoryPreferenceKeys.EXTERNAL_SERVER_BASE_URL, "")
            preferences.edit().apply {
                if (changed) putInt(InventoryPreferenceKeys.SYNC_CONFIG_GENERATION,
                    preferences.getInt(InventoryPreferenceKeys.SYNC_CONFIG_GENERATION, 0) + 1)
            }
                .putString(InventoryPreferenceKeys.SERVER_BASE_URL, normalizedServerUrl)
                .putString(InventoryPreferenceKeys.EXTERNAL_SERVER_BASE_URL, externalServerBaseUrl.trim().trimEnd('/'))
                .putString(InventoryPreferenceKeys.API_TOKEN, apiToken.trim())
                .putBoolean(InventoryPreferenceKeys.AUTO_SYNC_ENABLED, autoSyncEnabled)
                .commit()
        }

        return OperationResult(
            isSuccess = true,
            message = text(R.string.sync_settings_saved_local),
        )
    }

    fun saveAppPreferences(
        preferencesState: AppPreferences,
    ): OperationResult {
        ensureDefaultSettings()
        val languageChanged = loadAppPreferences().appLanguage != preferencesState.appLanguage
        if (languageChanged) clearLookupCache()
        preferences.edit()
            .putString(KEY_DEFAULT_IMPORT_LOCATION, preferencesState.defaultImportLocation.trim())
            .putInt(KEY_DEFAULT_IMPORT_MIN_STOCK, preferencesState.defaultImportMinStock.coerceAtLeast(0))
            .putBoolean(KEY_REMEMBER_LAST_IMPORT_LOCATION, preferencesState.rememberLastImportLocation)
            .putBoolean(KEY_SYNC_AFTER_LOCAL_CHANGES, preferencesState.syncAfterLocalChanges)
            .putBoolean(KEY_ENABLE_LOCAL_AUTO_RECOGNITION, preferencesState.enableLocalAutoRecognition)
            .putBoolean(
                KEY_PREFER_AGGRESSIVE_AUTO_RECOGNITION,
                preferencesState.preferAggressiveAutoRecognition,
            )
            .putBoolean(KEY_ENABLE_LOCAL_IMPORT_LEARNING, preferencesState.enableLocalImportLearning)
            .putBoolean(KEY_ENABLE_SERVER_JLC_LOOKUP, preferencesState.enableServerJlcLookup)
            .putBoolean("enable_public_jlc_lookup", preferencesState.enablePublicJlcLookup)
            .putBoolean(KEY_AUTO_ENRICH_JLC_IMPORTS, preferencesState.enableServerJlcLookup)
            .putString(KEY_OCR_ENGINE_MODE, preferencesState.ocrEngineMode.storageValue)
            .putString(InventoryPreferenceKeys.APP_LANGUAGE, preferencesState.appLanguage.storageValue)
            .apply()

        return OperationResult(
            isSuccess = true,
            message = text(R.string.sync_settings_saved_local),
        )
    }

    fun rememberLastImportLocation(location: String) {
        ensureDefaultSettings()
        preferences.edit()
            .putString(KEY_LAST_IMPORT_LOCATION, location.trim())
            .apply()
    }

    suspend fun saveComponent(draft: ComponentDraft): OperationResult = writeService.saveComponent(draft)

    suspend fun saveImportedComponent(
        draft: ComponentDraft,
        sourceCandidate: ComponentImportCandidate?,
        appPreferences: AppPreferences,
    ): OperationResult = writeService.saveImportedComponent(draft, sourceCandidate, appPreferences)

    suspend fun softDeleteComponent(componentId: String): OperationResult = writeService.softDeleteComponent(componentId)

    suspend fun recordMovement(draft: MovementEntryDraft): OperationResult = writeService.recordMovement(draft)

    internal suspend fun appendImportedStock(
        target: ExistingImportTarget, quantity: Int, locationId: String,
    ): OperationResult = writeService.appendImportedStock(target, quantity, locationId)

    internal suspend fun loadBatchJlcReceipts(sessionId: String): Set<String> =
        writeService.loadBatchJlcReceipts(sessionId)

    internal suspend fun commitBatchJlc(sessionId: String, rows: List<BatchJlcRow>): OperationResult =
        writeService.commitBatchJlc(sessionId, rows)

    suspend fun saveStorageLocation(
        id: String, name: String, intent: StorageLocationSaveIntent,
    ): OperationResult = writeService.saveStorageLocation(id, name, intent)

    suspend fun deleteStorageLocation(id: String): OperationResult = writeService.deleteStorageLocation(id)

    suspend fun recordMovementsBatch(drafts: List<MovementEntryDraft>): OperationResult =
        writeService.recordMovementsBatch(drafts)

    internal suspend fun transferComponentsBatch(
        sourceLocationId: String, destinationLocationId: String, lines: List<BatchTransferLine>,
    ): OperationResult = writeService.transferComponentsBatch(sourceLocationId, destinationLocationId, lines)

    suspend fun loadStorageLocations(): List<StorageLocationRecord> = withContext(Dispatchers.IO) {
        val locations = databaseHelper.readableDatabase.use { db ->
            db.rawQuery(
                "SELECT id, name, updated_at, deleted FROM storage_locations WHERE deleted = 0 ORDER BY name, id",
                null,
            ).use { cursor -> buildList {
                while (cursor.moveToNext()) add(StorageLocationRecord(
                    id = cursor.getString(0), name = cursor.getString(1),
                    updatedAt = cursor.getString(2), deleted = cursor.getInt(3) == 1,
                ))
            } }
        }
        // Imported defaults can outlive a locally deleted or synced-away location.
        // Clear only the stale remembered choice; keep the user's configured default.
        val last = preferences.getString(KEY_LAST_IMPORT_LOCATION, "").orEmpty()
        if (last.isNotBlank() && locations.none { it.id == last }) {
            preferences.edit().remove(KEY_LAST_IMPORT_LOCATION).apply()
        }
        locations
    }

    internal suspend fun findExistingImportTarget(sku: String): ExistingImportTarget? = withContext(Dispatchers.IO) {
        val normalized = LcscPublicCatalog.normalizeSku(sku) ?: return@withContext null
        databaseHelper.readableDatabase.use { db ->
            db.rawQuery(
                "SELECT id,sku,quantity,updated_at,location FROM components WHERE UPPER(sku)=? AND deleted=0 LIMIT 2",
                arrayOf(normalized),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val target = ExistingImportTarget(
                    cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getString(3), cursor.getString(4),
                )
                check(!cursor.moveToNext()) { "存在多个大小写不同但料号相同的活跃元器件，请先合并冲突记录。" }
                target
            }
        }
    }

    internal suspend fun exportInventoryWorkbook(): ByteArray = workbookService.exportInventoryWorkbook()

    internal suspend fun previewInventoryWorkbook(bytes: ByteArray): WorkbookPreview =
        workbookService.previewInventoryWorkbook(bytes)

    internal suspend fun restoreInventoryWorkbook(preview: WorkbookPreview): OperationResult =
        workbookService.restoreInventoryWorkbook(preview)

    suspend fun loadAllocations(componentId: String? = null): List<ComponentAllocationRecord> = withContext(Dispatchers.IO) {
        databaseHelper.readableDatabase.use { db ->
            db.rawQuery(
                """SELECT a.component_id, a.location_id, COALESCE(l.name, a.location_id), a.quantity
                   FROM component_allocations a LEFT JOIN storage_locations l ON l.id = a.location_id
                   WHERE (? = '' OR a.component_id = ?) ORDER BY a.quantity DESC, a.location_id""",
                arrayOf(componentId.orEmpty(), componentId.orEmpty()),
            ).use { cursor -> buildList {
                while (cursor.moveToNext()) add(ComponentAllocationRecord(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3),
                ))
            } }
        }
    }

    suspend fun loadIssuedQuantities(): Map<String, Long> = withContext(Dispatchers.IO) {
        databaseHelper.readableDatabase.use { db ->
            db.rawQuery(
                """
                SELECT component_id, COALESCE(SUM(ABS(CAST(quantity AS INTEGER))), 0) AS issued_quantity
                FROM stock_movements
                WHERE deleted = 0
                  AND LOWER(TRIM(movement_type)) = 'outbound'
                GROUP BY component_id
                """.trimIndent(),
                null,
            ).use { cursor ->
                buildMap {
                    val componentIdIndex = cursor.getColumnIndexOrThrow("component_id")
                    val issuedQuantityIndex = cursor.getColumnIndexOrThrow("issued_quantity")
                    while (cursor.moveToNext()) {
                        put(cursor.getString(componentIdIndex), cursor.getLong(issuedQuantityIndex))
                    }
                }
            }
        }
    }

    suspend fun testConnection(
        serverBaseUrl: String,
        apiToken: String,
        externalServerBaseUrl: String = "",
    ): OperationResult = syncService.testConnection(loadSyncConfiguration().copy(
        serverBaseUrl = serverBaseUrl.trim().trimEnd('/'),
        externalServerBaseUrl = externalServerBaseUrl.trim().trimEnd('/'),
        apiToken = apiToken.trim(),
    ))

    fun observeSyncStatus(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == InventoryPreferenceKeys.SYNC_RUN_STATE || key == InventoryPreferenceKeys.SYNC_RUN_PHASE) onChange()
        }
        val photoPreferences = appContext.getSharedPreferences("photo-sync", Context.MODE_PRIVATE)
        val photoListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "state" || key == "ack" || key == "binding" || key == "configuration_generation") onChange()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        photoPreferences.registerOnSharedPreferenceChangeListener(photoListener)
        // The returned unsubscribe closure retains both listeners until ViewModel.onCleared.
        return {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            photoPreferences.unregisterOnSharedPreferenceChangeListener(photoListener)
        }
    }

    fun cancelSyncRequests() = syncService.cancelActiveRequests()

    suspend fun runSync(): OperationResult = syncService.runSync(::loadSyncConfiguration)

    suspend fun previewBomRelease(
        parsed: com.componentvault.android.data.bom.BomParseResult,
        selections: Map<String, String> = emptyMap(),
        searchQueries: Map<String, String> = emptyMap(),
        excludedKeys: Set<String> = emptySet(),
    ): com.componentvault.android.data.bom.BomReleasePreview = withContext(Dispatchers.IO) {
        val components = loadComponents()
        val inventory = components.map { component ->
            val parsedDescription = parseImportDescription(component.description)
            com.componentvault.android.data.bom.InventoryMatchCandidate(
                inventoryId = component.id,
                sku = component.sku,
                model = parsedDescription.model,
                packageName = component.packageName,
                displayName = component.name,
                active = !component.deleted,
                category = component.category,
                parameters = component.officialParameters(),
            )
        }
        val matches = com.componentvault.android.data.bom.BomInventoryMatcher.match(
            parsed.requirements.filterNot { it.identity.canonicalKey in excludedKeys },
            inventory.filter { it.active },
        )
        val componentsById = components.associateBy { it.id }
        val rawLines = matches.map { match ->
            val requirementKey = match.requirement.identity.canonicalKey
            val alternatives = if (match.kind == com.componentvault.android.data.bom.BomMatchKind.NONE) {
                com.componentvault.android.data.bom.BomElectricalAlternativeMatcher.suggest(match.requirement, inventory)
            } else null
            val selectedId = selections[requirementKey]
                ?: match.selectedInventoryId
            val component = selectedId?.let(componentsById::get)
                ?.takeUnless { it.deleted }
            val searchedCandidates = searchQueries[requirementKey]
                ?.let { query ->
                    com.componentvault.android.data.bom.BomInventoryMatcher.search(inventory, query)
                }.orEmpty()
            val selectedCandidate = component?.let {
                inventory.firstOrNull { candidate -> candidate.inventoryId == it.id }
            }
            com.componentvault.android.data.bom.BomReleaseLine(
                requirement = match.requirement,
                componentId = component?.id,
                componentSku = component?.sku,
                availableQuantity = component?.quantity ?: 0,
                expectedUpdatedAt = component?.updatedAt,
                candidates = (match.candidates + alternatives?.candidates.orEmpty() + searchedCandidates + listOfNotNull(selectedCandidate))
                    .distinctBy { it.inventoryId },
                candidateExplanation = alternatives?.explanation,
            )
        }
        val aggregated = com.componentvault.android.data.bom.BomReleaseAggregator.aggregate(rawLines)
        val allocations = loadAllocations().groupBy { it.componentId }
        com.componentvault.android.data.bom.BomReleasePreview(
            parsed = parsed,
            lines = aggregated.map { line ->
                val componentId = line.componentId ?: return@map line
                line.copy(allocationPlan = runCatching {
                    com.componentvault.android.data.bom.BomAllocationPlanner.plan(
                        line.requirement.requiredQuantity,
                        allocations[componentId].orEmpty().map { it.locationId to it.quantity },
                    )
                }.getOrDefault(emptyList()))
            },
            matchingLines = rawLines,
        )
    }

    suspend fun commitBomRelease(
        preview: com.componentvault.android.data.bom.BomReleasePreview,
        releaseId: String,
        batchId: String,
    ): com.componentvault.android.data.bom.BomReleaseResult =
        bomReleaseCommitter.commitBomRelease(preview, releaseId, batchId)

    suspend fun previewComponentHub(
        bytes: ByteArray,
        duplicatePolicy: com.componentvault.android.data.bom.ComponentHubDuplicatePolicy,
    ): com.componentvault.android.data.bom.ComponentHubParseResult = withContext(Dispatchers.IO) {
        val existingSkus = loadComponents().asSequence()
            .filterNot { it.deleted }
            .map { it.sku }
            .toSet()
        com.componentvault.android.data.bom.ComponentHubParser.parse(
            bytes = bytes,
            duplicatePolicy = duplicatePolicy,
            existingSkus = existingSkus,
        )
    }

    suspend fun importComponentHub(
        preview: com.componentvault.android.data.bom.ComponentHubParseResult,
    ): com.componentvault.android.data.bom.ComponentHubImportResult =
        componentHubImporter.importComponentHub(preview)

    private fun countMovements(db: SQLiteDatabase): Int {
        db.rawQuery(
            "SELECT COUNT(*) AS movement_count FROM stock_movements WHERE deleted = 0",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(cursor.getColumnIndexOrThrow("movement_count"))
        }
    }

    private fun readComponents(cursor: Cursor): List<ComponentRecord> {
        val items = mutableListOf<ComponentRecord>()
        while (cursor.moveToNext()) {
            items += ComponentRecord(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                sku = cursor.getString(cursor.getColumnIndexOrThrow("sku")),
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                location = cursor.getString(cursor.getColumnIndexOrThrow("location")),
                description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                quantity = cursor.getInt(cursor.getColumnIndexOrThrow("quantity")),
                minStock = cursor.getInt(cursor.getColumnIndexOrThrow("min_stock")),
                updatedAt = cursor.getString(cursor.getColumnIndexOrThrow("updated_at")),
                deleted = cursor.getInt(cursor.getColumnIndexOrThrow("deleted")) == 1,
            )
        }
        return items
    }

    private fun readMovements(cursor: Cursor): List<StockMovementRecord> {
        val items = mutableListOf<StockMovementRecord>()
        while (cursor.moveToNext()) {
            items += StockMovementRecord(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                componentId = cursor.getString(cursor.getColumnIndexOrThrow("component_id")),
                componentSku = cursor.getString(cursor.getColumnIndexOrThrow("component_sku")),
                componentName = cursor.getString(cursor.getColumnIndexOrThrow("component_name")),
                movementType = cursor.getString(cursor.getColumnIndexOrThrow("movement_type")),
                quantity = cursor.getInt(cursor.getColumnIndexOrThrow("quantity")),
                reason = cursor.getString(cursor.getColumnIndexOrThrow("reason")),
                note = cursor.getString(cursor.getColumnIndexOrThrow("note")),
                happenedAt = cursor.getString(cursor.getColumnIndexOrThrow("happened_at")),
                updatedAt = cursor.getString(cursor.getColumnIndexOrThrow("updated_at")),
                deleted = cursor.getInt(cursor.getColumnIndexOrThrow("deleted")) == 1,
                locationId = cursor.getString(cursor.getColumnIndexOrThrow("location_id")),
                destinationLocationId = cursor.getString(cursor.getColumnIndexOrThrow("destination_location_id")),
            )
        }
        return items
    }

    private fun findActiveComponentsBySku(
        db: SQLiteDatabase,
        sku: String,
    ): List<ComponentRecord> {
        return db.rawQuery(
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
                deleted
            FROM components
            WHERE deleted = 0 AND sku = ?
            ORDER BY updated_at DESC, name COLLATE NOCASE ASC
            """.trimIndent(),
            arrayOf(sku.trim()),
        ).use(::readComponents)
    }

    private fun extractWarehouseLocation(candidate: ComponentImportCandidate): String {
        return candidate.notes.firstOrNull {
            it.startsWith("Warehouse location: ") || it.startsWith("仓位：")
        }
            ?.removePrefix("Warehouse location: ")
            ?.removePrefix("仓位：")
            ?.trim()
            .orEmpty()
    }

    private fun ensureDefaultSettings() {
        val editor = preferences.edit()
        var changed = false

        if (!preferences.contains(InventoryPreferenceKeys.DEVICE_ID)) {
            editor.putString(InventoryPreferenceKeys.DEVICE_ID, defaultDeviceId())
            changed = true
        }
        if (!preferences.contains(InventoryPreferenceKeys.LAST_SYNCED_AT)) {
            editor.putString(InventoryPreferenceKeys.LAST_SYNCED_AT, text(R.string.sync_never))
            changed = true
        }
        if (!preferences.contains(InventoryPreferenceKeys.LAST_SYNC_MESSAGE)) {
            editor.putString(InventoryPreferenceKeys.LAST_SYNC_MESSAGE, text(R.string.sync_no_sync_yet))
            changed = true
        }
        if (!preferences.contains(KEY_DEFAULT_IMPORT_LOCATION)) {
            editor.putString(KEY_DEFAULT_IMPORT_LOCATION, "")
            changed = true
        }
        if (!preferences.contains(KEY_LAST_IMPORT_LOCATION)) {
            editor.putString(KEY_LAST_IMPORT_LOCATION, "")
            changed = true
        }
        if (!preferences.contains(KEY_DEFAULT_IMPORT_MIN_STOCK)) {
            editor.putInt(KEY_DEFAULT_IMPORT_MIN_STOCK, 0)
            changed = true
        }
        if (!preferences.contains(KEY_REMEMBER_LAST_IMPORT_LOCATION)) {
            editor.putBoolean(KEY_REMEMBER_LAST_IMPORT_LOCATION, true)
            changed = true
        }
        if (!preferences.contains(KEY_SYNC_AFTER_LOCAL_CHANGES)) {
            editor.putBoolean(
                KEY_SYNC_AFTER_LOCAL_CHANGES,
                preferences.getBoolean(InventoryPreferenceKeys.AUTO_SYNC_ENABLED, false),
            )
            changed = true
        }
        if (!preferences.contains(KEY_ENABLE_LOCAL_AUTO_RECOGNITION)) {
            editor.putBoolean(KEY_ENABLE_LOCAL_AUTO_RECOGNITION, true)
            changed = true
        }
        if (!preferences.contains(KEY_PREFER_AGGRESSIVE_AUTO_RECOGNITION)) {
            editor.putBoolean(KEY_PREFER_AGGRESSIVE_AUTO_RECOGNITION, true)
            changed = true
        }
        if (!preferences.contains(KEY_ENABLE_LOCAL_IMPORT_LEARNING)) {
            editor.putBoolean(KEY_ENABLE_LOCAL_IMPORT_LEARNING, true)
            changed = true
        }
        if (!preferences.contains(KEY_ENABLE_SERVER_JLC_LOOKUP)) {
            editor.putBoolean(
                KEY_ENABLE_SERVER_JLC_LOOKUP,
                if (preferences.contains(KEY_AUTO_ENRICH_JLC_IMPORTS)) {
                    preferences.getBoolean(KEY_AUTO_ENRICH_JLC_IMPORTS, false)
                } else {
                    false
                },
            )
            changed = true
        }
        if (!preferences.contains(KEY_AUTO_ENRICH_JLC_IMPORTS)) {
            editor.putBoolean(KEY_AUTO_ENRICH_JLC_IMPORTS, false)
            changed = true
        }
        if (!preferences.contains(KEY_OCR_ENGINE_MODE)) {
            editor.putString(KEY_OCR_ENGINE_MODE, OcrEngineMode.Auto.storageValue)
            changed = true
        }
        if (!preferences.contains(InventoryPreferenceKeys.APP_LANGUAGE)) {
            editor.putString(InventoryPreferenceKeys.APP_LANGUAGE, AppLanguage.ZhCn.storageValue)
            changed = true
        }
        if (changed) {
            editor.apply()
        }
    }

    private fun defaultDeviceId(): String {
        val androidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID,
        )
        return "android-${androidId?.takeLast(8) ?: "device"}"
    }

    private fun text(
        @StringRes resId: Int,
        vararg args: Any,
    ): String = appContext.getString(resId, *args)

    private fun String.isLikelyModelLike(
        sku: String,
        model: String?,
    ): Boolean {
        val normalized = trim()
        if (normalized.isBlank()) {
            return false
        }
        if (normalized.equals(sku.trim(), ignoreCase = true)) {
            return true
        }
        if (!model.isNullOrBlank() && normalized.equals(model.trim(), ignoreCase = true)) {
            return true
        }
        if (normalized.any { it.code in 0x4E00..0x9FFF } || normalized.any(Char::isWhitespace)) {
            return false
        }
        val alphaNumericCount = normalized.count(Char::isLetterOrDigit)
        val hasSeparator = normalized.any { it == '-' || it == '_' || it == '/' || it == '.' }
        return alphaNumericCount >= 5 && (hasSeparator || normalized.any(Char::isDigit))
    }

    private companion object {
        const val PREFS_NAME = "component_vault_sync"
        const val KEY_DEFAULT_IMPORT_LOCATION = "default_import_location"
        const val KEY_LAST_IMPORT_LOCATION = "last_import_location"
        const val KEY_DEFAULT_IMPORT_MIN_STOCK = "default_import_min_stock"
        const val KEY_REMEMBER_LAST_IMPORT_LOCATION = "remember_last_import_location"
        const val KEY_SYNC_AFTER_LOCAL_CHANGES = "sync_after_local_changes"
        const val KEY_ENABLE_LOCAL_AUTO_RECOGNITION = "enable_local_auto_recognition"
        const val KEY_PREFER_AGGRESSIVE_AUTO_RECOGNITION = "prefer_aggressive_auto_recognition"
        const val KEY_ENABLE_LOCAL_IMPORT_LEARNING = "enable_local_import_learning"
        const val KEY_ENABLE_SERVER_JLC_LOOKUP = "enable_server_jlc_lookup"
        const val KEY_AUTO_ENRICH_JLC_IMPORTS = "auto_enrich_jlc_imports"
        const val KEY_OCR_ENGINE_MODE = "ocr_engine_mode"

    }
}
