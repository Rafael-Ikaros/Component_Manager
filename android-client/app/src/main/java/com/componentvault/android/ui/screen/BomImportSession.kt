package com.componentvault.android.ui.screen

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.componentvault.android.data.bom.BomColumnMapping
import com.componentvault.android.data.bom.BomPreset
import com.componentvault.android.data.bom.BomParseResult
import com.componentvault.android.data.bom.BomReleasePreview
import com.componentvault.android.data.bom.BomRequirement
import com.componentvault.android.data.bom.BomSheet
import com.componentvault.android.data.bom.BomTableInspection
import com.componentvault.android.data.bom.ComponentHubParseResult
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Kept in memory across activity recreation; source files and previews never enter a Bundle. */
internal class BomImportSession : ViewModel() {
    val activePreset = mutableStateOf<BomPreset?>(null)
    val presetToDelete = mutableStateOf<BomPreset?>(null)
    val presetToSave = mutableStateOf<BomReleasePreview?>(null)
    val fileBytes = mutableStateOf<ByteArray?>(null)
    val fileName = mutableStateOf("")
    val projectName = mutableStateOf("")
    val productionSets = mutableStateOf("1")
    val sheets = mutableStateOf<List<BomSheet>>(emptyList())
    val selectedSheet = mutableStateOf<String?>(null)
    val inspection = mutableStateOf<BomTableInspection?>(null)
    val columnMapping = mutableStateOf<BomColumnMapping?>(null)
    val preview = mutableStateOf<BomReleasePreview?>(null)
    val hubPreview = mutableStateOf<ComponentHubParseResult?>(null)
    val selections = mutableStateOf<Map<String, String>>(emptyMap())
    val searchQueries = mutableStateOf<Map<String, String>>(emptyMap())
    val expandedSearches = mutableStateOf<Set<String>>(emptySet())
    val excludedKeys = mutableStateOf<Set<String>>(emptySet())
    val mappingExpanded = mutableStateOf(false)
    val showAllLines = mutableStateOf(false)
    val message = mutableStateOf("")
    val messageIsError = mutableStateOf(false)
    val busy = mutableStateOf(false)
    val releaseId = mutableStateOf(UUID.randomUUID().toString())
    val batchId = mutableStateOf(UUID.randomUUID().toString())
    val releaseApplied = mutableStateOf(false)
    val confirmKind = mutableStateOf<String?>(null)
    val clearSelectionKeys = mutableStateOf<List<String>?>(null)
    val exitPending = mutableStateOf(false)
    val procurementOffer = mutableStateOf<BomProcurementOffer?>(null)
    val selectedMode = mutableStateOf<BomImportMode?>(null)
    val materialQuery = mutableStateOf("")
    val showSavedPresets = mutableStateOf(false)
    var entryMode: BomImportMode? = null
    var retainOnExit = false
    var initialized = false
    private var generation = 0L
    private var task: Job? = null

    fun token(): Long = generation

    fun isCurrent(token: Long): Boolean = initialized && generation == token

    /** Rebuild a kept BOM preview from current inventory when its screen is reopened. */
    fun consumeRetainedPreviewRefresh(mode: BomImportMode?): Boolean {
        val refresh = retainOnExit && entryMode == mode && selectedMode.value == BomImportMode.Bom &&
            preview.value != null && !releaseApplied.value
        retainOnExit = false
        return refresh
    }

    fun launchTask(block: suspend (Long) -> Unit) {
        task?.cancel()
        val token = generation
        task = viewModelScope.launch { block(token) }
    }

    fun invalidatePending() {
        generation++
        task?.cancel()
        task = null
    }

    fun resetSession() {
        invalidatePending()
        activePreset.value = null
        presetToDelete.value = null
        presetToSave.value = null
        fileBytes.value = null
        fileName.value = ""
        projectName.value = ""
        productionSets.value = "1"
        sheets.value = emptyList()
        selectedSheet.value = null
        inspection.value = null
        columnMapping.value = null
        preview.value = null
        hubPreview.value = null
        selections.value = emptyMap()
        searchQueries.value = emptyMap()
        expandedSearches.value = emptySet()
        excludedKeys.value = emptySet()
        mappingExpanded.value = false
        showAllLines.value = false
        message.value = ""
        messageIsError.value = false
        busy.value = false
        releaseId.value = UUID.randomUUID().toString()
        batchId.value = UUID.randomUUID().toString()
        releaseApplied.value = false
        confirmKind.value = null
        clearSelectionKeys.value = null
        exitPending.value = false
        procurementOffer.value = null
        selectedMode.value = null
        materialQuery.value = ""
        showSavedPresets.value = false
        entryMode = null
        retainOnExit = false
        initialized = false
    }
}

/** Empty ID explicitly suppresses an automatic match until the user chooses again. */
internal fun clearBomSelections(selections: Map<String, String>, keys: List<String>): Map<String, String> =
    selections + keys.associateWith { "" }

internal data class BomProcurementOffer(
    val parsed: BomParseResult,
    val requirements: List<BomRequirement>,
)

/** Matches the rows classified as unmatched or short in the exported CSV. */
internal fun bomProcurementEligible(preview: BomReleasePreview): List<BomRequirement> =
    preview.matchingLines
        .filter { it.componentId == null || it.availableQuantity < it.requirement.requiredQuantity }
        .map { it.requirement }
        .distinctBy { it.identity.canonicalKey }
