package com.componentvault.android.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.StringRes
import com.componentvault.android.R
import com.componentvault.android.model.OperationResult
import com.componentvault.android.model.SyncConfiguration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal class InventorySyncService(
    private val appContext: Context,
    private val databaseHelper: InventoryDatabaseHelper,
    private val preferences: SharedPreferences,
    private val syncConfigurationLock: Any,
) {
    private val syncMutex = processSyncMutex
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var activePhotoSync: InventoryPhotoSync? = null
    private var activeGeneration: Int? = null
    @Volatile private var syncContext: kotlin.coroutines.CoroutineContext? = null

    internal fun cancelActiveRequests() {
        activeConnection?.disconnect()
        activePhotoSync?.cancelActiveRequest()
    }

    internal fun recoverInterruptedRun() {
        if (!startupRecovered.compareAndSet(false, true)) return
        if (preferences.getString(InventoryPreferenceKeys.SYNC_RUN_STATE, "") == "Running") {
            saveRunState("Interrupted", preferences.getString(InventoryPreferenceKeys.SYNC_RUN_PHASE, "").orEmpty(),
                text(R.string.sync_run_interrupted))
        }
    }

    private fun saveRunState(state: String, phase: String, message: String? = null) {
        preferences.edit().putString(InventoryPreferenceKeys.SYNC_RUN_STATE, state)
            .putString(InventoryPreferenceKeys.SYNC_RUN_PHASE, phase)
            .putInt(InventoryPreferenceKeys.SYNC_RUN_GENERATION,
                activeGeneration ?: preferences.getInt(InventoryPreferenceKeys.SYNC_RUN_GENERATION, 0))
            .apply { if (message != null) putString(InventoryPreferenceKeys.LAST_SYNC_MESSAGE, message) }.commit()
    }

    private fun ensureSyncActive() { syncContext?.ensureActive() }

    internal fun pendingSummary(): Pair<Int, Int> {
        val countHelper = InventoryDatabaseHelper(appContext)
        val inventory = try {
            countHelper.readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_queue", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
        } finally { countHelper.close() }
        val generation = preferences.getInt(InventoryPreferenceKeys.SYNC_CONFIG_GENERATION, 0)
        val ack = if (preferences.getInt(InventoryPreferenceKeys.BOM_SUCCESS_GENERATION, 0) == generation)
            JSONObject(preferences.getString(InventoryPreferenceKeys.BOM_ACK_VERSIONS, "{}").orEmpty()) else JSONObject()
        val bom = try {
            val records = bomPresetStore.syncSnapshot()
            (0 until records.length()).count { index ->
                val record = records.getJSONObject(index)
                val confirmed = ack.optString(record.getString("id"))
                confirmed.isBlank() || !SyncProtocol.timestampsEqual(record.getString("updated_at"), confirmed)
            }
        } catch (_: com.componentvault.android.data.bom.BomPresetStoreException) { -1 }
        return inventory to bom
    }

    internal fun summary(currentRun: Boolean, pending: Pair<Int, Int>): String {
        val configured = preferences.getString(InventoryPreferenceKeys.SERVER_BASE_URL, "").orEmpty().isNotBlank() &&
            preferences.getString(InventoryPreferenceKeys.API_TOKEN, "").orEmpty().isNotBlank()
        val status = if (!configured) text(R.string.sync_run_not_configured) else {
            val state = if (currentRun) preferences.getString(InventoryPreferenceKeys.SYNC_RUN_STATE, "").orEmpty() else ""
            text(when (state) {
                "Running" -> R.string.sync_run_running
                "Interrupted" -> R.string.sync_run_interrupted
                "Cancelled" -> R.string.sync_run_cancelled
                "Complete" -> if (pending.first != 0 || pending.second != 0 ||
                    !InventoryPhotoSync(appContext).isCurrentSyncComplete())
                    R.string.sync_run_pending else R.string.sync_run_complete
                "Partial" -> if (pending.first != 0 || pending.second != 0) R.string.sync_run_new_changes_pending else R.string.sync_run_partial
                "Failed" -> R.string.sync_run_failed
                else -> R.string.sync_run_pending
            })
        }
        val phase = if (currentRun) preferences.getString(InventoryPreferenceKeys.SYNC_RUN_PHASE, "").orEmpty() else ""
        val phaseLabel = when (phase) {
            "Connection" -> text(R.string.sync_phase_connection)
            "Inventory" -> text(R.string.sync_phase_inventory)
            "BOM" -> text(R.string.sync_phase_bom)
            "Photos" -> text(R.string.sync_phase_photos)
            else -> ""
        }
        return status + (if (phaseLabel.isBlank()) "" else " ($phaseLabel)") + " " +
            (if (pending.second < 0) text(R.string.sync_pending_bom_unreadable, pending.first)
            else text(R.string.sync_pending_counts, pending.first, pending.second))
    }
    private val bomPresetStore = com.componentvault.android.data.bom.BomPresetStore(java.io.File(appContext.filesDir, "bom-presets.json"))

    internal suspend fun testConnection(settings: SyncConfiguration): OperationResult = withContext(Dispatchers.IO) {
        if (settings.serverBaseUrl.isBlank()) {
            return@withContext OperationResult(false, text(R.string.sync_enter_server_url_first))
        }
        if (settings.apiToken.isBlank()) {
            return@withContext OperationResult(false, text(R.string.sync_enter_api_token_first))
        }

        return@withContext try {
            val endpoint = resolveSyncEndpoint(settings)
            requireSyncIdentity(endpoint)
            OperationResult(
                isSuccess = true,
                message = text(R.string.sync_connection_ok, endpoint.probe.optString("server_time")) +
                    if (endpoint.usesExternalAddress) " " + text(R.string.sync_via_external) else "",
            )
        } catch (exception: Exception) {
            if (exception is kotlinx.coroutines.CancellationException) throw exception
            OperationResult(
                isSuccess = false,
                message = exception.message ?: text(R.string.sync_connection_failed),
            )
        }
    }

    internal suspend fun runSync(configuration: () -> SyncConfiguration): OperationResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val settings = configuration()
            syncContext = currentCoroutineContext()
            activeGeneration = preferences.getInt(InventoryPreferenceKeys.SYNC_CONFIG_GENERATION, 0)
            saveRunState("Running", "Connection")
            if (settings.serverBaseUrl.isBlank()) {
                saveRunState("Failed", "Connection", text(R.string.sync_run_not_configured))
                syncContext = null
                activeGeneration = null
                return@withContext OperationResult(
                    false,
                    text(R.string.sync_run_not_configured),
                )
            }
            if (settings.apiToken.isBlank()) {
                saveRunState("Failed", "Connection", text(R.string.sync_run_not_configured))
                syncContext = null
                activeGeneration = null
                return@withContext OperationResult(
                    false,
                    text(R.string.sync_run_not_configured),
                )
            }

            var inventoryComplete = false
            try {
                val endpoint = resolveSyncEndpoint(settings)
                val identity = requireSyncIdentity(endpoint)
                val serverId = identity.optString("server_id").trim()
                val accountId = identity.optString("account_id").trim()
                synchronized(syncConfigurationLock) {
                    checkSyncConfigurationUnchanged(settings)
                    val boundServer = preferences.getString(InventoryPreferenceKeys.BOUND_SERVER_ID, "").orEmpty()
                    val boundAccount = preferences.getString(InventoryPreferenceKeys.BOUND_ACCOUNT_ID, "").orEmpty()
                    check(boundServer.isBlank() && boundAccount.isBlank() ||
                        boundServer == serverId && boundAccount == accountId) {
                        text(R.string.sync_account_mismatch)
                    }
                    if (boundServer.isBlank() && boundAccount.isBlank()) {
                        val priorSync = readStoredSyncCursor() != null ||
                            preferences.getString(InventoryPreferenceKeys.LAST_SYNCED_AT, "").orEmpty()
                                .matches(Regex("^\\d{4}-\\d{2}-\\d{2}T.*")) ||
                            databaseHelper.readableDatabase.use { db ->
                                db.rawQuery(
                                    "SELECT 1 FROM components WHERE base_updated_at IS NOT NULL LIMIT 1",
                                    null,
                                ).use { it.moveToFirst() }
                            }
                        check(!priorSync || identity.optString("role") == "admin") {
                            text(R.string.sync_legacy_admin_only)
                        }
                        check(preferences.edit()
                            .putString(InventoryPreferenceKeys.BOUND_SERVER_ID, serverId)
                            .putString(InventoryPreferenceKeys.BOUND_ACCOUNT_ID, accountId)
                            .commit()) { text(R.string.sync_identity_save_failed) }
                    }
                }
                ensureSyncActive()
                saveRunState("Running", "Inventory")
                val pushPayload = buildPushPayload(settings.deviceId)
                val pushResponse = callJson(
                    settings = endpoint.configuration,
                    method = "POST",
                    path = "/sync/push",
                    body = pushPayload.payload,
                    accountId = accountId,
                )
                val cursor = readStoredSyncCursor()
                val pullResponse = callJson(
                    settings = endpoint.configuration,
                    method = "GET",
                    path = SyncProtocol.pullPath(cursor),
                    body = null,
                    accountId = accountId,
                )
                val rawCursor = pullResponse.opt("sync_cursor")
                val cursorDecision = SyncProtocol.cursorFromResponse(
                    hasCursor = pullResponse.has("sync_cursor"),
                    cursor = when (rawCursor) {
                        is Int -> rawCursor.toLong()
                        is Long -> rawCursor
                        else -> null
                    },
                )

                synchronized(syncConfigurationLock) {
                    checkSyncConfigurationUnchanged(settings)
                    ensureSyncActive()
                    databaseHelper.writableDatabase.use { db ->
                        db.beginTransaction()
                        try {
                            applyPullResponse(
                                db = db,
                                pullResponse = pullResponse,
                                pushedEntities = pushPayload.queuedEntities,
                            )
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    }
                    saveSyncCursor(cursorDecision)
                    preferences.edit()
                        .putString(InventoryPreferenceKeys.LAST_SYNCED_AT, pullResponse.optString("server_time"))
                        .putInt(InventoryPreferenceKeys.INVENTORY_SUCCESS_GENERATION,
                            preferences.getInt(InventoryPreferenceKeys.SYNC_CONFIG_GENERATION, 0)).commit()
                }

                inventoryComplete = true
                val acceptedComponents = pushResponse.optInt("accepted_components")
                val acceptedMovements = pushResponse.optInt("accepted_stock_movements")
                val pulledComponents = pullResponse.optJSONArray("components")?.length() ?: 0
                val pulledMovements = pullResponse.optJSONArray("stock_movements")?.length() ?: 0
                val serverTime = pullResponse.optString("server_time")
                ensureSyncActive()
                saveRunState("Running", "BOM")
                val presetResult = syncBomPresets(endpoint, settings, accountId)
                ensureSyncActive()
                saveRunState("Running", "Photos")
                val photoContext = currentCoroutineContext()
                val photoSync = InventoryPhotoSync(appContext)
                activePhotoSync = photoSync
                val photoResult = photoSync.sync(endpoint.configuration, serverId, accountId,
                    endpoint.probe.optInt("photo_protocol") == 1,
                    checkBinding = { photoContext.ensureActive(); synchronized(syncConfigurationLock) {
                        checkSyncConfigurationUnchanged(settings)
                        check(preferences.getString(InventoryPreferenceKeys.BOUND_SERVER_ID, "") == serverId &&
                            preferences.getString(InventoryPreferenceKeys.BOUND_ACCOUNT_ID, "") == accountId) { text(R.string.sync_account_mismatch) }
                    } },
                    manifest = { callJson(endpoint.configuration, "GET", "/sync/photos", null, accountId = accountId) })
                val inventoryMessage = text(
                    R.string.sync_complete_summary,
                    acceptedComponents,
                    acceptedMovements,
                    pulledComponents,
                    pulledMovements,
                ) + (if (endpoint.usesExternalAddress) " " + text(R.string.sync_via_external) else "") + presetResult.second + photoResult.second
                val pending = pendingSummary()
                val localChangesRemain = pending.first != 0 || pending.second != 0 || !photoSync.isCurrentSyncComplete()
                val allComplete = presetResult.first && photoResult.first && !localChangesRemain
                val successMessage = when {
                    allComplete -> inventoryMessage
                    presetResult.first && photoResult.first -> text(R.string.sync_run_new_changes_pending) + " " + inventoryMessage
                    else -> text(R.string.sync_run_partial) + " " + inventoryMessage
                }

                synchronized(syncConfigurationLock) {
                    checkSyncConfigurationUnchanged(settings)
                    ensureSyncActive()
                    saveRunState(if (allComplete) "Complete" else "Partial", "", successMessage)
                    updateSyncStatus(
                        lastSyncedAt = serverTime,
                        message = successMessage,
                        preserveTimestamp = false,
                    )
                }

                OperationResult(
                    isSuccess = allComplete,
                    message = successMessage,
                )
            } catch (exception: Exception) {
                if (exception is kotlinx.coroutines.CancellationException || syncContext?.get(kotlinx.coroutines.Job)?.isCancelled == true) {
                    saveRunState("Cancelled", preferences.getString(InventoryPreferenceKeys.SYNC_RUN_PHASE, "").orEmpty(), text(R.string.sync_run_cancelled))
                    throw kotlinx.coroutines.CancellationException(text(R.string.sync_run_cancelled), exception)
                }
                saveRunState(if (inventoryComplete) "Partial" else "Failed", preferences.getString(InventoryPreferenceKeys.SYNC_RUN_PHASE, "").orEmpty())
                updateSyncStatus(
                    lastSyncedAt = null,
                    message = exception.message ?: text(R.string.sync_failed),
                    preserveTimestamp = true,
                )
                OperationResult(
                    isSuccess = false,
                    message = exception.message ?: text(R.string.sync_failed),
                )
            } finally {
                syncContext = null
                activeGeneration = null
                activeConnection = null
                activePhotoSync = null
            }
        }
    }

    /** Preset failures are partial failures: successful inventory sync remains committed. */
    private fun syncBomPresets(endpoint: ResolvedSyncEndpoint<JSONObject>, settings: SyncConfiguration, accountId: String): Pair<Boolean, String> {
        if (endpoint.probe.optInt("bom_preset_protocol") != 1) return false to " " + text(R.string.sync_bom_unsupported)
        return try {
            val outbound = synchronized(syncConfigurationLock) {
                checkSyncConfigurationUnchanged(settings)
                bomPresetStore.syncSnapshot()
            }
            val response = callJson(endpoint.configuration, "POST", "/sync/bom-presets",
                JSONObject().put("device_id", settings.deviceId).put("presets", outbound), accountId = accountId)
            check(response.optInt("bom_preset_protocol") == 1) { text(R.string.sync_bom_protocol_unsupported) }
            synchronized(syncConfigurationLock) {
                checkSyncConfigurationUnchanged(settings)
                ensureSyncActive()
                val remote = response.getJSONArray("presets")
                bomPresetStore.mergeRemote(remote, outbound)
                val versions = JSONObject()
                for (index in 0 until remote.length()) remote.getJSONObject(index).let {
                    versions.put(it.getString("id"), it.getString("updated_at"))
                }
                preferences.edit().putString(InventoryPreferenceKeys.BOM_ACK_VERSIONS, versions.toString())
                    .putString(InventoryPreferenceKeys.BOM_LAST_SYNCED_AT, java.time.Instant.now().toString())
                    .putInt(InventoryPreferenceKeys.BOM_SUCCESS_GENERATION,
                        preferences.getInt(InventoryPreferenceKeys.SYNC_CONFIG_GENERATION, 0)).commit()
            }
            true to " " + text(R.string.sync_bom_complete)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            false to " " + text(R.string.sync_bom_failed, error.message ?: text(R.string.sync_failed))
        }
    }
    private fun buildPushPayload(deviceId: String): SyncPayload {
        databaseHelper.readableDatabase.use { db ->
            val queuedEntities = mutableListOf<QueuedEntity>()
            db.rawQuery(
                """
                SELECT entity_type, entity_id, entity_updated_at
                FROM sync_queue
                ORDER BY created_at ASC
                """.trimIndent(),
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    queuedEntities += QueuedEntity(
                        entityType = cursor.getString(cursor.getColumnIndexOrThrow("entity_type")),
                        entityId = cursor.getString(cursor.getColumnIndexOrThrow("entity_id")),
                        entityUpdatedAt = cursor.getString(cursor.getColumnIndexOrThrow("entity_updated_at")),
                    )
                }
            }

            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("inventory_protocol", 1)
                put("components", JSONArray())
                put("stock_movements", JSONArray())
                put("storage_locations", getStorageLocationsJson(db))
            }

            queuedEntities.forEach { entity ->
                when (entity.entityType) {
                    "component" -> {
                        getComponentJson(db, entity.entityId)?.let {
                            payload.getJSONArray("components").put(it)
                        }
                    }
                    "stock_movement" -> {
                        getMovementJson(db, entity.entityId)?.let {
                            payload.getJSONArray("stock_movements").put(it)
                        }
                    }
                }
            }

            return SyncPayload(
                payload = payload,
                queuedEntities = queuedEntities,
            )
        }
    }

    private fun getComponentJson(
        db: SQLiteDatabase,
        componentId: String,
    ): JSONObject? {
        db.rawQuery(
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
                base_updated_at
            FROM components
            WHERE id = ?
            """.trimIndent(),
            arrayOf(componentId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return JSONObject().apply {
                put("id", cursor.getString(cursor.getColumnIndexOrThrow("id")))
                put("sku", cursor.getString(cursor.getColumnIndexOrThrow("sku")))
                put("name", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                put("category", cursor.getString(cursor.getColumnIndexOrThrow("category")))
                put("package_name", cursor.getString(cursor.getColumnIndexOrThrow("package_name")))
                put("location", cursor.getString(cursor.getColumnIndexOrThrow("location")))
                put("description", cursor.getString(cursor.getColumnIndexOrThrow("description")))
                put("quantity", cursor.getInt(cursor.getColumnIndexOrThrow("quantity")))
                put("min_stock", cursor.getInt(cursor.getColumnIndexOrThrow("min_stock")))
                put("updated_at", cursor.getString(cursor.getColumnIndexOrThrow("updated_at")))
                put("deleted", cursor.getInt(cursor.getColumnIndexOrThrow("deleted")) == 1)
                put("base_updated_at", cursor.getString(cursor.getColumnIndexOrThrow("base_updated_at")))
                put("allocations", JSONArray().apply {
                    db.rawQuery(
                        "SELECT location_id, quantity FROM component_allocations WHERE component_id = ? ORDER BY location_id",
                        arrayOf(componentId),
                    ).use { allocationCursor ->
                        while (allocationCursor.moveToNext()) put(JSONObject().apply {
                            put("location_id", allocationCursor.getString(0))
                            put("quantity", allocationCursor.getInt(1))
                        })
                    }
                })
            }
        }
    }

    private fun getMovementJson(
        db: SQLiteDatabase,
        movementId: String,
    ): JSONObject? {
        db.rawQuery(
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
                destination_location_id
            FROM stock_movements
            WHERE id = ?
            """.trimIndent(),
            arrayOf(movementId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            return JSONObject().apply {
                put("id", cursor.getString(cursor.getColumnIndexOrThrow("id")))
                put("component_id", cursor.getString(cursor.getColumnIndexOrThrow("component_id")))
                put("movement_type", cursor.getString(cursor.getColumnIndexOrThrow("movement_type")))
                put("quantity", cursor.getInt(cursor.getColumnIndexOrThrow("quantity")))
                put("reason", cursor.getString(cursor.getColumnIndexOrThrow("reason")))
                put("note", cursor.getString(cursor.getColumnIndexOrThrow("note")))
                put("happened_at", cursor.getString(cursor.getColumnIndexOrThrow("happened_at")))
                put("updated_at", cursor.getString(cursor.getColumnIndexOrThrow("updated_at")))
                put("deleted", cursor.getInt(cursor.getColumnIndexOrThrow("deleted")) == 1)
                put("location_id", nullableMovementLocation(db, cursor.getString(cursor.getColumnIndexOrThrow("location_id"))))
                put("destination_location_id", nullableMovementLocation(db, cursor.getString(cursor.getColumnIndexOrThrow("destination_location_id"))))
            }
        }
    }

    private fun resolveSyncEndpoint(settings: SyncConfiguration): ResolvedSyncEndpoint<JSONObject> =
        SyncEndpointResolver.resolve(settings) { configuration, timeout ->
            callJson(configuration, "POST", "/auth/ping", null, timeout)
        }

    private fun requireSyncIdentity(endpoint: ResolvedSyncEndpoint<JSONObject>): JSONObject {
        check(endpoint.probe.optInt("inventory_protocol", 0) == 1) {
            text(R.string.sync_inventory_protocol_unavailable)
        }
        val identity = callJson(endpoint.configuration, "GET", "/auth/me", null)
        check(identity.optString("server_id").isNotBlank() && identity.optString("account_id").isNotBlank()) {
            text(R.string.sync_identity_missing_fields)
        }
        return identity
    }

    internal fun callJson(
        settings: SyncConfiguration,
        method: String,
        path: String,
        body: JSONObject?,
        timeoutMillis: Int = 60_000,
        accountId: String? = null,
    ): JSONObject {
        ensureSyncActive()
        val connection = URL("${settings.serverBaseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
        activeConnection = connection
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer ${settings.apiToken}")
        if (accountId != null) connection.setRequestProperty("X-Component-Vault-Account-Id", accountId)
        if (method == "GET" && path.startsWith("/sync/pull")) connection.setRequestProperty("X-Component-Vault-Device-Id", settings.deviceId)
        connection.setRequestProperty("Accept", "application/json")
        return try {
            ensureSyncActive()
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { writer ->
                    writer.write(body.toString())
                }
            }
            val responseCode = connection.responseCode
            val responseText = readResponseText(connection, responseCode)
            ensureSyncActive()
            if (responseCode !in 200..299) {
                if (path == "/auth/me" && responseCode == 404) {
                    throw IllegalStateException(text(R.string.sync_identity_route_unavailable))
                }
                throw IllegalStateException(buildErrorMessage(responseCode, responseText))
            }
            if (responseText.isBlank()) {
                JSONObject()
            } else {
                try {
                    JSONObject(responseText)
                } catch (exception: org.json.JSONException) {
                    if (path == "/auth/me") {
                        throw IllegalStateException(text(R.string.sync_identity_invalid_response), exception)
                    }
                    throw exception
                }
            }
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun readResponseText(
        connection: HttpURLConnection,
        responseCode: Int,
    ): String {
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return ""

        return stream.bufferedReader().use { it.readText() }
    }

    private fun buildErrorMessage(
        statusCode: Int,
        body: String,
    ): String {
        if (body.isBlank()) {
            return text(R.string.sync_server_error, statusCode)
        }

        return try {
            val parsedBody = JSONObject(body)
            val validationErrors = parsedBody.optJSONArray("detail")
            if (statusCode == 422 && validationErrors != null && validationErrors.length() > 0) {
                val first = validationErrors.optJSONObject(0)
                val location = first?.optJSONArray("loc")
                val path = location?.let { fields ->
                    (0 until fields.length()).joinToString(".") { fields.optString(it) }
                }.orEmpty()
                return text(
                    R.string.sync_validation_failed,
                    path,
                    first?.optString("msg").orEmpty(),
                    validationErrors.length(),
                )
            }
            val detail = parsedBody.optString("detail")
            if (detail.isBlank()) {
                text(R.string.sync_server_error_detail, statusCode, body)
            } else {
                text(R.string.sync_server_error_detail, statusCode, detail)
            }
        } catch (_: Exception) {
            text(R.string.sync_server_error_detail, statusCode, body)
        }
    }

    private fun applyPullResponse(
        db: SQLiteDatabase,
        pullResponse: JSONObject,
        pushedEntities: List<QueuedEntity>,
    ) {
        check(pullResponse.optInt("inventory_protocol", 0) == 1) {
            "The sync response is missing inventory_protocol=1. Local changes were kept."
        }
        val locations = pullResponse.optJSONArray("storage_locations") ?: JSONArray()
        for (index in 0 until locations.length()) {
            upsertRemoteStorageLocation(db, locations.getJSONObject(index))
        }
        // Older clients stored JSON null as the literal string "null". Repair only
        // those dangling references, inside the pull transaction, retaining every movement.
        for (column in listOf("location_id", "destination_location_id")) {
            db.execSQL(
                "UPDATE stock_movements SET $column = NULL WHERE $column = 'null' " +
                    "AND NOT EXISTS (SELECT 1 FROM storage_locations WHERE id = 'null')",
            )
        }
        val components = pullResponse.optJSONArray("components") ?: JSONArray()
        for (index in 0 until components.length()) {
            val component = components.getJSONObject(index)
            val id = component.getString("id")
            val pushed = pushedEntities.firstOrNull { it.entityType == "component" && it.entityId == id }
            val queued = db.rawQuery(
                "SELECT entity_updated_at FROM sync_queue WHERE entity_type = 'component' AND entity_id = ?",
                arrayOf(id),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (SyncProtocol.canApplyInventorySnapshot(queued, pushed?.entityUpdatedAt)) {
                upsertRemoteComponent(db, component)
            } else if (pushed != null) {
                // A second local edit survives the pull, based on the version actually uploaded.
                db.execSQL("UPDATE components SET base_updated_at = ? WHERE id = ?", arrayOf(pushed.entityUpdatedAt, id))
            }
        }

        val movements = pullResponse.optJSONArray("stock_movements") ?: JSONArray()
        for (index in 0 until movements.length()) {
            val movement = movements.getJSONObject(index)
            upsertRemoteMovement(db, movement)
            removeQueuedIfSuperseded(
                db = db,
                entityType = "stock_movement",
                entityId = movement.getString("id"),
                updatedAt = movement.getString("updated_at"),
            )
        }

        pushedEntities.forEach { entity ->
            if (entity.entityType == "component" && (0 until components.length()).none {
                components.getJSONObject(it).getString("id") == entity.entityId
            }) {
                db.execSQL("UPDATE components SET base_updated_at = ? WHERE id = ?", arrayOf(entity.entityUpdatedAt, entity.entityId))
            }
            removeQueuedIfSuperseded(
                db = db,
                entityType = entity.entityType,
                entityId = entity.entityId,
                updatedAt = entity.entityUpdatedAt,
            )
        }
    }

    private fun upsertRemoteComponent(
        db: SQLiteDatabase,
        component: JSONObject,
    ) {
        val values = ContentValues().apply {
            put("id", component.getString("id"))
            put("sku", component.getString("sku"))
            put("name", component.getString("name"))
            put("category", component.getString("category"))
            put("package_name", component.getString("package_name"))
            put("location", component.getString("location"))
            put("description", if (component.isNull("description")) null as String? else component.optString("description"))
            put("quantity", component.getInt("quantity"))
            put("min_stock", component.getInt("min_stock"))
            put("updated_at", component.getString("updated_at"))
            put("deleted", if (component.getBoolean("deleted")) 1 else 0)
            put("base_updated_at", component.getString("updated_at"))
        }
        db.insertWithOnConflict(
            "components",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        val allocations = component.optJSONArray("allocations") ?: JSONArray().put(
            JSONObject().put("location_id", component.getString("location").trim().ifBlank { "待整理" })
                .put("quantity", component.getInt("quantity")),
        )
        run {
            db.delete("component_allocations", "component_id = ?", arrayOf(component.getString("id")))
            for (index in 0 until allocations.length()) {
                val allocation = allocations.getJSONObject(index)
                if (component.optJSONArray("allocations") == null) {
                    ensureStorageLocation(db, allocation.getString("location_id"), component.getString("updated_at"))
                }
                setAllocationQuantity(
                    db,
                    component.getString("id"),
                    allocation.getString("location_id"),
                    allocation.getInt("quantity"),
                )
            }
            verifyAllocationTotal(db, component.getString("id"), component.getInt("quantity"))
        }
    }

    private fun upsertRemoteMovement(
        db: SQLiteDatabase,
        movement: JSONObject,
    ) {
        db.rawQuery(
            "SELECT updated_at FROM stock_movements WHERE id = ?",
            arrayOf(movement.getString("id")),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val existingUpdatedAt = cursor.getString(cursor.getColumnIndexOrThrow("updated_at"))
                if (
                    !SyncProtocol.isRemoteAtLeastAsNew(
                        localUpdatedAt = existingUpdatedAt,
                        remoteUpdatedAt = movement.getString("updated_at"),
                    )
                ) {
                    return
                }
            }
        }

        val values = ContentValues().apply {
            put("id", movement.getString("id"))
            put("component_id", movement.getString("component_id"))
            put("movement_type", movement.getString("movement_type"))
            put("quantity", movement.getInt("quantity"))
            put("reason", movement.getString("reason"))
            put("note", movement.optString("note"))
            put("happened_at", movement.getString("happened_at"))
            put("updated_at", movement.getString("updated_at"))
            put("deleted", if (movement.getBoolean("deleted")) 1 else 0)
            put("location_id", nullableMovementLocation(db, if (movement.isNull("location_id")) null else movement.optString("location_id")))
            put("destination_location_id", nullableMovementLocation(db, if (movement.isNull("destination_location_id")) null else movement.optString("destination_location_id")))
        }
        db.insertWithOnConflict(
            "stock_movements",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun nullableMovementLocation(db: SQLiteDatabase, value: String?): String? {
        if (value.isNullOrBlank()) return null
        if (value != "null") return value
        // A real location named "null" remains a valid reference.
        return db.rawQuery("SELECT 1 FROM storage_locations WHERE id = ?", arrayOf(value)).use {
            if (it.moveToFirst()) value else null
        }
    }

    private fun removeQueuedIfSuperseded(
        db: SQLiteDatabase,
        entityType: String,
        entityId: String,
        updatedAt: String,
    ) {
        db.rawQuery(
            """
            SELECT entity_updated_at
            FROM sync_queue
            WHERE entity_type = ? AND entity_id = ?
            """.trimIndent(),
            arrayOf(entityType, entityId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return
            }
            val queuedUpdatedAt = cursor.getString(
                cursor.getColumnIndexOrThrow("entity_updated_at"),
            )
            if (!SyncProtocol.timestampsEqual(queuedUpdatedAt, updatedAt)) {
                return
            }
            db.delete(
                "sync_queue",
                "entity_type = ? AND entity_id = ? AND entity_updated_at = ?",
                arrayOf(entityType, entityId, queuedUpdatedAt),
            )
        }
    }

    private fun updateSyncStatus(
        lastSyncedAt: String?,
        message: String,
        preserveTimestamp: Boolean,
    ) {
        val editor = preferences.edit().putString(InventoryPreferenceKeys.LAST_SYNC_MESSAGE, message)
        if (!preserveTimestamp && lastSyncedAt != null) {
            editor.putString(InventoryPreferenceKeys.LAST_SYNCED_AT, lastSyncedAt)
        }
        editor.apply()
    }

    private fun upsertRemoteStorageLocation(db: SQLiteDatabase, location: JSONObject) {
        val id = location.getString("id")
        val remoteUpdatedAt = location.getString("updated_at")
        val existingUpdatedAt = db.rawQuery(
            "SELECT updated_at FROM storage_locations WHERE id = ?",
            arrayOf(id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (existingUpdatedAt != null && !SyncProtocol.isRemoteAtLeastAsNew(existingUpdatedAt, remoteUpdatedAt)) return
        db.insertWithOnConflict(
            "storage_locations",
            null,
            ContentValues().apply {
                put("id", id)
                put("name", location.getString("name"))
                put("updated_at", remoteUpdatedAt)
                put("deleted", if (location.getBoolean("deleted")) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun getStorageLocationsJson(db: SQLiteDatabase): JSONArray = JSONArray().apply {
        db.rawQuery("SELECT id, name, updated_at, deleted FROM storage_locations ORDER BY id", null).use { cursor ->
            while (cursor.moveToNext()) put(JSONObject().apply {
                put("id", cursor.getString(0))
                put("name", cursor.getString(1))
                put("updated_at", cursor.getString(2))
                put("deleted", cursor.getInt(3) == 1)
            })
        }
    }

    private fun readStoredSyncCursor(): Long? = if (preferences.contains(InventoryPreferenceKeys.SYNC_CURSOR)) {
        preferences.getLong(InventoryPreferenceKeys.SYNC_CURSOR, 0L).coerceAtLeast(0L)
    } else {
        null
    }

    private fun saveSyncCursor(decision: CursorDecision) {
        val editor = preferences.edit()
        when (decision) {
            is CursorDecision.Store -> editor.putLong(InventoryPreferenceKeys.SYNC_CURSOR, decision.value)
            CursorDecision.Clear -> editor.remove(InventoryPreferenceKeys.SYNC_CURSOR)
        }
        check(editor.commit()) { "Unable to persist sync cursor" }
    }

    private fun checkSyncConfigurationUnchanged(settings: SyncConfiguration) {
        val currentServerUrl = preferences.getString(InventoryPreferenceKeys.SERVER_BASE_URL, "").orEmpty()
        val currentApiToken = preferences.getString(InventoryPreferenceKeys.API_TOKEN, "").orEmpty()
        val currentExternalUrl = preferences.getString(InventoryPreferenceKeys.EXTERNAL_SERVER_BASE_URL, "").orEmpty()
        check(
            currentServerUrl == settings.serverBaseUrl &&
                currentExternalUrl == settings.externalServerBaseUrl &&
                currentApiToken == settings.apiToken,
        ) {
            "Sync configuration changed while synchronization was running. Please retry."
        }
    }

    private fun text(@StringRes resId: Int, vararg args: Any): String =
        appContext.getString(resId, *args)

    companion object {
        private val startupRecovered = java.util.concurrent.atomic.AtomicBoolean(false)
        private val processSyncMutex = Mutex()
    }

    private data class SyncPayload(
        val payload: JSONObject,
        val queuedEntities: List<QueuedEntity>,
    )

    private data class QueuedEntity(
        val entityType: String,
        val entityId: String,
        val entityUpdatedAt: String,
    )

}
