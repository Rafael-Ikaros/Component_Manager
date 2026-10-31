package com.componentvault.android.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.StringRes
import com.componentvault.android.R
import com.componentvault.android.model.AppPreferences
import com.componentvault.android.model.AppLanguage
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentImportLearningMapping
import com.componentvault.android.model.ComponentImportLearningMatch
import com.componentvault.android.model.ComponentImportLearningMatchType
import com.componentvault.android.model.ComponentImportResolution
import com.componentvault.android.model.ComponentOfficialLookupOutcome
import com.componentvault.android.model.ComponentOfficialLookupResult
import com.componentvault.android.model.ComponentOfficialMetadata
import com.componentvault.android.model.ImportLearningSummary
import com.componentvault.android.model.OperationResult
import com.componentvault.android.model.SyncConfiguration
import com.componentvault.android.model.withRecognitionMetadata
import com.componentvault.android.model.withLearningMapping
import com.componentvault.android.model.withOfficialMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

internal class InventoryImportCatalog(
    private val appContext: Context,
    private val databaseHelper: InventoryDatabaseHelper,
    private val preferences: SharedPreferences,
    private val syncService: InventorySyncService,
) {
    private val localPartRecognitionEngine by lazy { LocalPartRecognitionEngine(appContext) }
    private val publicCatalogLookup = LcscCombinedLookup()

    internal fun searchPublicCatalog(keyword: String, language: AppLanguage) = publicCatalogLookup.searchPublicCatalog(keyword, language)

    suspend fun loadImportLearningSummary(): ImportLearningSummary = withContext(Dispatchers.IO) {
        databaseHelper.readableDatabase.use { db ->
            db.rawQuery(
                """
                SELECT COUNT(*) AS mapping_count
                FROM import_learning_mappings
                """.trimIndent(),
                null,
            ).use { cursor ->
                cursor.moveToFirst()
                ImportLearningSummary(
                    mappingCount = cursor.getInt(cursor.getColumnIndexOrThrow("mapping_count")),
                )
            }
        }
    }

    suspend fun enrichImportCandidate(
        candidate: ComponentImportCandidate,
        appPreferences: AppPreferences,
        syncConfiguration: SyncConfiguration,
    ): ComponentImportResolution = withContext(Dispatchers.IO) {
        if (candidate.sourceType == com.componentvault.android.model.ComponentImportSourceType.WarehouseLabel) {
            return@withContext ComponentImportResolution(candidate = candidate)
        }

        var enrichedCandidate = candidate
        if (appPreferences.enableLocalAutoRecognition) {
            localPartRecognitionEngine.recognize(
                candidate = candidate,
                aggressive = appPreferences.preferAggressiveAutoRecognition,
            )?.let { metadata ->
                enrichedCandidate = enrichedCandidate.withRecognitionMetadata(metadata)
            }
        }

        val learningMatch = if (appPreferences.enableLocalImportLearning) {
            databaseHelper.writableDatabase.use { db ->
                findImportLearningMatch(
                    db = db,
                    sku = candidate.sku,
                    mpn = candidate.model,
                )?.also { match ->
                    touchImportLearningMapping(
                        db = db,
                        mappingId = match.mapping.id,
                    )
                }
            }
        } else {
            null
        }

        if (learningMatch != null) {
            enrichedCandidate = enrichedCandidate.withLearningMapping(learningMatch)
        }

        val publicLookupResult = if (appPreferences.enablePublicJlcLookup &&
            LcscPublicCatalog.normalizeSku(enrichedCandidate.sku) != null
        ) {
            lookupPublicPartMetadata(enrichedCandidate.sku)
        } else null

        val officialLookupResult = if (publicLookupResult?.outcome == ComponentOfficialLookupOutcome.Success) {
            publicLookupResult.also { result ->
                result.metadata?.let { enrichedCandidate = enrichedCandidate.withOfficialMetadata(it) }
            }
        } else {
            publicLookupResult
        }

        if (appPreferences.enableLocalImportLearning) {
            enrichedCandidate = CategoryLearningStore(appContext).apply(enrichedCandidate)
        }

        ComponentImportResolution(
            candidate = enrichedCandidate,
            learningMatch = learningMatch,
            officialLookupResult = officialLookupResult,
        )
    }

    private suspend fun lookupPublicPartMetadata(sku: String): ComponentOfficialLookupResult =
        withContext(Dispatchers.IO) {
            val language = AppLanguage.fromStorageValue(
                preferences.getString(InventoryPreferenceKeys.APP_LANGUAGE, AppLanguage.ZhCn.storageValue),
            )
            val preferredSource = LcscCatalogRoutePolicy.preferredSource(language)
            val cacheScope = preferredSource.wireName
            readCachedLookup(sku, "", cacheScope)?.takeIf {
                it.sku.equals(sku, true)
            }?.let {
                return@withContext ComponentOfficialLookupResult(
                    outcome = ComponentOfficialLookupOutcome.Success,
                    metadata = it.copy(
                        category = it.categoryPath?.takeIf(String::isNotBlank)
                            ?.let(OfficialCategoryNormalizer::normalize) ?: it.category,
                    ),
                    fromCache = true,
                    message = text(
                        R.string.catalog_lookup_cache_success,
                        text(
                            when (it.source) {
                                LcscCatalogSource.Domestic.wireName -> R.string.catalog_source_domestic
                                LcscCatalogSource.Eda.wireName -> R.string.catalog_source_eda
                                else -> R.string.catalog_source_international
                            },
                        ),
                    ),
                )
            }
            try {
                val route = publicCatalogLookup.lookupWithRoute(sku, preferredSource)
                val metadata = route.metadata
                if (metadata == null) {
                    ComponentOfficialLookupResult(
                        outcome = if (route.attempts.all { it.failure == LcscCatalogFailureKind.NoMatch }) {
                            ComponentOfficialLookupOutcome.NoMatch
                        } else {
                            ComponentOfficialLookupOutcome.Failed
                        },
                        message = catalogLookupMessage(route),
                    )
                } else {
                    if (LcscCatalogRoutePolicy.shouldPersist(route)) cacheLookup(metadata, cacheScope)
                    ComponentOfficialLookupResult(
                        outcome = ComponentOfficialLookupOutcome.Success,
                        metadata = metadata,
                        message = catalogLookupMessage(route),
                    )
                }
            } catch (error: IOException) {
                ComponentOfficialLookupResult(
                    outcome = ComponentOfficialLookupOutcome.Failed,
                    message = text(R.string.importer_public_lookup_unavailable),
                )
            }
        }

    suspend fun lookupPartMetadata(
        syncConfiguration: SyncConfiguration,
        sku: String?,
        mpn: String?,
        name: String?,
        brand: String?,
        packageHint: String?,
        sourceType: String?,
    ): ComponentOfficialLookupResult = withContext(Dispatchers.IO) {
        val normalizedSku = sku?.trim().orEmpty()
        val normalizedMpn = mpn?.trim().orEmpty()
        val normalizedName = name?.trim().orEmpty()
        val normalizedBrand = brand?.trim().orEmpty()
        val normalizedPackageHint = packageHint?.trim().orEmpty()
        val normalizedSourceType = sourceType?.trim().orEmpty()
        if (
            normalizedSku.isBlank() &&
            normalizedMpn.isBlank() &&
            normalizedName.isBlank() &&
            normalizedBrand.isBlank() &&
            normalizedPackageHint.isBlank()
        ) {
            return@withContext ComponentOfficialLookupResult(
                outcome = ComponentOfficialLookupOutcome.NoMatch,
            )
        }

        readCachedLookup(normalizedSku, normalizedMpn, SERVER_LOOKUP_CACHE_SCOPE)?.let { cached ->
            return@withContext ComponentOfficialLookupResult(
                outcome = ComponentOfficialLookupOutcome.Success,
                metadata = cached,
                fromCache = true,
                message = text(R.string.importer_lookup_cache_success),
            )
        }

        if (syncConfiguration.serverBaseUrl.isBlank() || syncConfiguration.apiToken.isBlank()) {
            return@withContext ComponentOfficialLookupResult(
                outcome = ComponentOfficialLookupOutcome.NotConfigured,
                message = text(R.string.importer_lookup_not_configured),
            )
        }

        return@withContext try {
            val queryParts = buildList {
                if (normalizedSku.isNotBlank()) {
                    add("sku=${java.net.URLEncoder.encode(normalizedSku, Charsets.UTF_8.name())}")
                }
                if (normalizedMpn.isNotBlank()) {
                    add("mpn=${java.net.URLEncoder.encode(normalizedMpn, Charsets.UTF_8.name())}")
                }
                if (normalizedName.isNotBlank()) {
                    add("name=${java.net.URLEncoder.encode(normalizedName, Charsets.UTF_8.name())}")
                }
                if (normalizedBrand.isNotBlank()) {
                    add("brand=${java.net.URLEncoder.encode(normalizedBrand, Charsets.UTF_8.name())}")
                }
                if (normalizedPackageHint.isNotBlank()) {
                    add("package_hint=${java.net.URLEncoder.encode(normalizedPackageHint, Charsets.UTF_8.name())}")
                }
                if (normalizedSourceType.isNotBlank()) {
                    add("source_type=${java.net.URLEncoder.encode(normalizedSourceType, Charsets.UTF_8.name())}")
                }
            }
            val response = syncService.callJson(
                settings = syncConfiguration,
                method = "GET",
                path = "/admin-api/part-lookup?${queryParts.joinToString("&")}",
                body = null,
            )
            if (!response.optBoolean("found")) {
                ComponentOfficialLookupResult(
                    outcome = ComponentOfficialLookupOutcome.NoMatch,
                    message = text(R.string.importer_lookup_no_match),
                )
            } else {
                val rawName = response.optString("name").blankToNull()
                val rawSku = response.optString("sku").blankToNull()
                val rawMpn = response.optString("mpn").blankToNull()
                val rawCategory = response.optString("category").blankToNull()
                val rawCategoryPath = response.optString("category_path").blankToNull()
                val rawBrand = response.optString("brand").blankToNull()
                val rawVendor = response.optString("vendor").blankToNull()
                val rawModelFamily = response.optString("model_family").blankToNull()
                val rawPackage = response.optString("package_name").blankToNull()
                    ?: ComponentPackageInferencer.infer(
                        rawName,
                        rawMpn,
                        rawSku,
                        rawCategory,
                        rawCategoryPath,
                    )
                val details = parseServerOfficialDetails(response)
                val metadata = ComponentOfficialMetadata(
                    source = response.optString("source").blankToNull(),
                    sku = rawSku,
                    name = rawName,
                    description = details.description,
                    packageName = rawPackage,
                    category = ComponentCategoryInferencer.infer(
                        rawCategoryPath,
                        rawCategory,
                        rawName,
                        rawPackage,
                        rawMpn,
                        rawBrand,
                    ),
                    model = rawMpn,
                    brand = rawBrand,
                    vendor = rawVendor ?: rawBrand,
                    modelFamily = rawModelFamily,
                    categoryPath = rawCategoryPath,
                    officialUrl = response.optString("official_url").blankToNull(),
                    matchedBy = response.optString("matched_by").blankToNull(),
                    confidence = response.optString("confidence").blankToNull(),
                    ruleVersion = response.optString("rule_version").blankToNull(),
                    parameters = details.parameters,
                )
                cacheLookup(metadata, SERVER_LOOKUP_CACHE_SCOPE)
                ComponentOfficialLookupResult(
                    outcome = ComponentOfficialLookupOutcome.Success,
                    metadata = metadata,
                    fromCache = response.optBoolean("cache_hit"),
                    message = if (response.optBoolean("cache_hit")) {
                        text(R.string.importer_lookup_cache_success)
                    } else {
                        text(R.string.importer_lookup_success)
                    },
                )
            }
        } catch (exception: Exception) {
            ComponentOfficialLookupResult(
                outcome = ComponentOfficialLookupOutcome.Failed,
                message = text(
                    R.string.importer_lookup_failed_pattern,
                    exception.message ?: text(R.string.importer_scanner_failed_description),
                ),
            )
        }
    }

    suspend fun clearImportLearningMappings(): OperationResult = withContext(Dispatchers.IO) {
        databaseHelper.writableDatabase.use { db ->
            db.delete("import_learning_mappings", null, null)
        }
        CategoryLearningStore(appContext).clear()
        OperationResult(
            isSuccess = true,
            message = text(R.string.settings_local_learning_cleared),
        )
    }

    private fun catalogLookupMessage(result: LcscCatalogLookupResult): String {
        fun sourceName(source: LcscCatalogSource): String = text(
            when (source) {
                LcscCatalogSource.Domestic -> R.string.catalog_source_domestic
                LcscCatalogSource.Eda -> R.string.catalog_source_eda
                LcscCatalogSource.International -> R.string.catalog_source_international
            },
        )
        fun failureName(failure: LcscCatalogFailureKind): String = text(
            when (failure) {
                LcscCatalogFailureKind.Blocked -> R.string.catalog_failure_blocked
                LcscCatalogFailureKind.RateLimited -> R.string.catalog_failure_rate_limited
                LcscCatalogFailureKind.CoolingDown -> R.string.catalog_failure_cooling_down
                LcscCatalogFailureKind.Unreachable -> R.string.catalog_failure_unreachable
                LcscCatalogFailureKind.NoMatch -> R.string.catalog_failure_no_match
                LcscCatalogFailureKind.InvalidResponse -> R.string.catalog_failure_invalid_response
            },
        )
        val resolved = result.resolvedSource
        if (resolved != null && !result.usedFallback) {
            return appContext.getString(R.string.catalog_lookup_source_success, sourceName(resolved))
        }
        if (resolved != null) {
            val primaryFailure = result.attempts.firstOrNull()?.failure ?: LcscCatalogFailureKind.NoMatch
            return appContext.getString(
                R.string.catalog_lookup_fallback_success,
                sourceName(result.preferredSource),
                failureName(primaryFailure),
                sourceName(resolved),
            )
        }
        val failures = LcscCatalogRoutePolicy.order(result.preferredSource).mapIndexed { index, source ->
            val failure = result.attempts.getOrNull(index)?.failure ?: LcscCatalogFailureKind.NoMatch
            "${sourceName(source)}：${failureName(failure)}"
        }
        return appContext.getString(R.string.catalog_lookup_all_failed, failures.joinToString("；"))
    }

    internal fun readCachedLookup(
        sku: String,
        mpn: String,
        cacheScope: String? = null,
    ): ComponentOfficialMetadata? {
        val now = System.currentTimeMillis()
        val cacheKeys = buildList {
            if (sku.isNotBlank()) {
                add("${LOOKUP_CACHE_PREFIX}${cacheScope?.let { "$it:" }.orEmpty()}sku:${sku.lowercase(Locale.US)}")
            }
            if (mpn.isNotBlank()) {
                add("${LOOKUP_CACHE_PREFIX}${cacheScope?.let { "$it:" }.orEmpty()}mpn:${mpn.lowercase(Locale.US)}")
            }
        }

        cacheKeys.forEach { cacheKey ->
            val raw = preferences.getString(cacheKey, null) ?: return@forEach
            val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            val fetchedAt = payload.optLong("fetched_at", 0L)
            if (fetchedAt <= 0L || now - fetchedAt > LOOKUP_CACHE_MAX_AGE_MS) {
                preferences.edit().remove(cacheKey).apply()
                return@forEach
            }
            return ComponentOfficialMetadata(
                source = payload.optString("source").blankToNull(),
                sku = payload.optString("sku").blankToNull(),
                name = payload.optString("name").blankToNull(),
                description = payload.optString("description").blankToNull(),
                packageName = payload.optString("package_name").blankToNull(),
                category = payload.optString("category").blankToNull(),
                model = payload.optString("model").blankToNull(),
                brand = payload.optString("brand").blankToNull(),
                vendor = payload.optString("vendor").blankToNull(),
                modelFamily = payload.optString("model_family").blankToNull(),
                categoryPath = payload.optString("category_path").blankToNull(),
                officialUrl = payload.optString("official_url").blankToNull(),
                imageUrl = payload.optString("image_url").blankToNull(),
                matchedBy = payload.optString("matched_by").blankToNull(),
                confidence = payload.optString("confidence").blankToNull(),
                ruleVersion = payload.optString("rule_version").blankToNull(),
                parameters = payload.optJSONObject("parameters")?.let { json ->
                    buildMap {
                        json.keys().forEach { key ->
                            json.optString(key).blankToNull()?.let { value -> put(key, value) }
                        }
                    }
                }.orEmpty(),
                datasheetUrl = payload.optString("datasheet_url").blankToNull(),
                referencePrice = payload.optJSONObject("reference_price")?.let { price ->
                    com.componentvault.android.model.ReferencePriceSnapshot.create(
                        price.optString("unit_price"), price.optString("currency"),
                        price.optInt("minimum_quantity", 0).takeIf { it > 0 },
                        price.optString("source_url"), price.optString("queried_at"),
                    )
                },
            )
        }

        return null
    }

    internal fun cacheLookup(metadata: ComponentOfficialMetadata, cacheScope: String? = null) {
        val payload = JSONObject().apply {
            put("source", metadata.source)
            put("sku", metadata.sku)
            put("name", metadata.name)
            put("description", metadata.description)
            put("package_name", metadata.packageName)
            put("category", metadata.category)
            put("model", metadata.model)
            put("brand", metadata.brand)
            put("vendor", metadata.vendor)
            put("model_family", metadata.modelFamily)
            put("category_path", metadata.categoryPath)
            put("official_url", metadata.officialUrl)
            put("image_url", metadata.imageUrl)
            put("matched_by", metadata.matchedBy)
            put("confidence", metadata.confidence)
            put("rule_version", metadata.ruleVersion)
            put("parameters", JSONObject(metadata.parameters))
            put("datasheet_url", metadata.datasheetUrl)
            metadata.referencePrice?.let { price ->
                put("reference_price", JSONObject().apply {
                    put("unit_price", price.unitPrice)
                    put("currency", price.currency)
                    price.minimumQuantity?.let { put("minimum_quantity", it) }
                    put("source_url", price.sourceUrl)
                    put("queried_at", price.queriedAt)
                })
            }
            put("fetched_at", System.currentTimeMillis())
        }.toString()

        val editor = preferences.edit()
        metadata.sku?.takeIf { it.isNotBlank() }?.let { value ->
            editor.putString("${LOOKUP_CACHE_PREFIX}${cacheScope?.let { "$it:" }.orEmpty()}sku:${value.lowercase(Locale.US)}", payload)
        }
        metadata.model?.takeIf { it.isNotBlank() }?.let { value ->
            editor.putString("${LOOKUP_CACHE_PREFIX}${cacheScope?.let { "$it:" }.orEmpty()}mpn:${value.lowercase(Locale.US)}", payload)
        }
        editor.apply()
    }

    internal fun clearLookupCache() {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(LOOKUP_CACHE_PREFIX) }.forEach(editor::remove)
        editor.apply()
    }

    internal fun cleanupLookupCache() {
        val now = System.currentTimeMillis()
        val scopedKey = Regex(
            "^${Regex.escape(LOOKUP_CACHE_PREFIX)}(?:lcsc_domestic_web|lcsc_public_web|lceda_public_library|easyeda_public_library|$SERVER_LOOKUP_CACHE_SCOPE):(sku|mpn):.+$",
        )
        val editor = preferences.edit()
        var changed = false
        preferences.all.forEach { (key, value) ->
            if (!key.startsWith(LOOKUP_CACHE_PREFIX)) return@forEach
            val payload = (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val fetchedAt = payload?.optLong("fetched_at", 0L) ?: 0L
            if (!scopedKey.matches(key) || fetchedAt <= 0L || now - fetchedAt !in 0..LOOKUP_CACHE_MAX_AGE_MS) {
                editor.remove(key)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    private fun findImportLearningMatch(
        db: SQLiteDatabase,
        sku: String,
        mpn: String?,
    ): ComponentImportLearningMatch? {
        val normalizedSku = sku.trim()
        if (normalizedSku.isNotBlank()) {
            db.rawQuery(
                """
                SELECT
                    id,
                    source_type,
                    COALESCE(source_sku, '') AS source_sku,
                    COALESCE(source_mpn, '') AS source_mpn,
                    COALESCE(resolved_name, '') AS resolved_name,
                    COALESCE(resolved_category, '') AS resolved_category,
                    COALESCE(resolved_package_name, '') AS resolved_package_name,
                    COALESCE(resolved_model, '') AS resolved_model,
                    COALESCE(resolved_brand, '') AS resolved_brand,
                    COALESCE(resolved_description, '') AS resolved_description,
                    confidence,
                    last_used_at
                FROM import_learning_mappings
                WHERE source_sku = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(normalizedSku),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    return ComponentImportLearningMatch(
                        mapping = readImportLearningMapping(cursor),
                        matchedBy = ComponentImportLearningMatchType.Sku,
                    )
                }
            }
        }

        val normalizedMpn = mpn?.trim().orEmpty()
        if (normalizedMpn.isNotBlank()) {
            db.rawQuery(
                """
                SELECT
                    id,
                    source_type,
                    COALESCE(source_sku, '') AS source_sku,
                    COALESCE(source_mpn, '') AS source_mpn,
                    COALESCE(resolved_name, '') AS resolved_name,
                    COALESCE(resolved_category, '') AS resolved_category,
                    COALESCE(resolved_package_name, '') AS resolved_package_name,
                    COALESCE(resolved_model, '') AS resolved_model,
                    COALESCE(resolved_brand, '') AS resolved_brand,
                    COALESCE(resolved_description, '') AS resolved_description,
                    confidence,
                    last_used_at
                FROM import_learning_mappings
                WHERE source_mpn = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(normalizedMpn),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    return ComponentImportLearningMatch(
                        mapping = readImportLearningMapping(cursor),
                        matchedBy = ComponentImportLearningMatchType.Mpn,
                    )
                }
            }
        }

        return null
    }

    private fun readImportLearningMapping(
        cursor: Cursor,
    ): ComponentImportLearningMapping {
        return ComponentImportLearningMapping(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            sourceType = cursor.getString(cursor.getColumnIndexOrThrow("source_type")).toImportSourceType(),
            sourceSku = cursor.getString(cursor.getColumnIndexOrThrow("source_sku")).blankToNull(),
            sourceMpn = cursor.getString(cursor.getColumnIndexOrThrow("source_mpn")).blankToNull(),
            resolvedName = cursor.getString(cursor.getColumnIndexOrThrow("resolved_name")).blankToNull(),
            resolvedCategory = cursor.getString(cursor.getColumnIndexOrThrow("resolved_category")).blankToNull(),
            resolvedPackageName = cursor.getString(cursor.getColumnIndexOrThrow("resolved_package_name")).blankToNull(),
            resolvedModel = cursor.getString(cursor.getColumnIndexOrThrow("resolved_model")).blankToNull(),
            resolvedBrand = cursor.getString(cursor.getColumnIndexOrThrow("resolved_brand")).blankToNull(),
            resolvedDescription = cursor.getString(cursor.getColumnIndexOrThrow("resolved_description")).blankToNull(),
            confidence = cursor.getInt(cursor.getColumnIndexOrThrow("confidence")),
            lastUsedAt = cursor.getString(cursor.getColumnIndexOrThrow("last_used_at")).blankToNull(),
        )
    }

    private fun touchImportLearningMapping(
        db: SQLiteDatabase,
        mappingId: String,
    ) {
        db.update(
            "import_learning_mappings",
            ContentValues().apply {
                put("last_used_at", utcNow())
            },
            "id = ?",
            arrayOf(mappingId),
        )
    }

    internal fun upsertImportLearningMapping(
        db: SQLiteDatabase,
        sourceCandidate: ComponentImportCandidate,
        draft: ComponentDraft,
        parsedDescription: com.componentvault.android.model.ParsedImportDescription,
    ) {
        val normalizedSourceSku = sourceCandidate.sku.trim().blankToNull() ?: draft.sku.trim().blankToNull()
        val normalizedSourceMpn = sourceCandidate.model?.trim()?.blankToNull()
            ?: parsedDescription.model?.trim()?.blankToNull()
        if (normalizedSourceSku == null && normalizedSourceMpn == null) {
            return
        }

        val now = utcNow()
        val existingRecord = findExistingImportLearningRecord(
            db = db,
            sourceSku = normalizedSourceSku,
            sourceMpn = normalizedSourceMpn,
        )
        val mappingId = existingRecord?.first ?: "ilm-${randomId()}"
        val createdAt = existingRecord?.second ?: now
        val resolvedModel = parsedDescription.model?.trim()?.blankToNull()
            ?: sourceCandidate.model?.trim()?.blankToNull()
        val resolvedBrand = parsedDescription.brand?.trim()?.blankToNull()
            ?: sourceCandidate.brand?.trim()?.blankToNull()
        val resolvedName = draft.name.trim().blankToNull()

        val values = ContentValues().apply {
            put("id", mappingId)
            put("source_type", sourceCandidate.sourceType.toStorageValue())
            put("source_sku", normalizedSourceSku)
            put("source_mpn", normalizedSourceMpn)
            put("resolved_name", resolvedName)
            put("resolved_category", draft.category.trim().blankToNull())
            put("resolved_package_name", draft.packageName.trim().blankToNull())
            put("resolved_model", resolvedModel)
            put("resolved_brand", resolvedBrand)
            put("resolved_description", draft.description.trim().blankToNull())
            put("confidence", 100)
            put("last_used_at", now)
            put("created_at", createdAt)
            put("updated_at", now)
        }
        db.insertWithOnConflict(
            "import_learning_mappings",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun findExistingImportLearningRecord(
        db: SQLiteDatabase,
        sourceSku: String?,
        sourceMpn: String?,
    ): Pair<String, String>? {
        sourceSku?.let { sku ->
            db.rawQuery(
                """
                SELECT id, created_at
                FROM import_learning_mappings
                WHERE source_sku = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(sku),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(cursor.getColumnIndexOrThrow("id")) to
                        cursor.getString(cursor.getColumnIndexOrThrow("created_at"))
                }
            }
        }

        sourceMpn?.let { mpn ->
            db.rawQuery(
                """
                SELECT id, created_at
                FROM import_learning_mappings
                WHERE source_mpn = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(mpn),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(cursor.getColumnIndexOrThrow("id")) to
                        cursor.getString(cursor.getColumnIndexOrThrow("created_at"))
                }
            }
        }

        return null
    }

    private fun String.toImportSourceType() = when (lowercase(Locale.US)) {
        "jlc_text" -> com.componentvault.android.model.ComponentImportSourceType.JlcText
        "jlc_qr" -> com.componentvault.android.model.ComponentImportSourceType.JlcQr
        "supplier_ocr" -> com.componentvault.android.model.ComponentImportSourceType.SupplierOcr
        else -> com.componentvault.android.model.ComponentImportSourceType.WarehouseLabel
    }

    private fun com.componentvault.android.model.ComponentImportSourceType.toStorageValue(): String =
        when (this) {
            com.componentvault.android.model.ComponentImportSourceType.JlcText -> "jlc_text"
            com.componentvault.android.model.ComponentImportSourceType.JlcQr -> "jlc_qr"
            com.componentvault.android.model.ComponentImportSourceType.SupplierOcr -> "supplier_ocr"
            com.componentvault.android.model.ComponentImportSourceType.WarehouseLabel -> "warehouse_label"
        }

    private fun String.blankToNull(): String? = trim().takeIf { it.isNotBlank() }

    private fun text(@StringRes resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    private companion object {
        const val LOOKUP_CACHE_PREFIX = "lcsc_lookup_cache:"
        const val SERVER_LOOKUP_CACHE_SCOPE = "server"
        const val LOOKUP_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
