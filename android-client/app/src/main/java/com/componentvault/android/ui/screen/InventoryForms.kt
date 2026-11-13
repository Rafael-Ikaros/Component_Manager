package com.componentvault.android.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.componentvault.android.R
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.componentvault.android.model.LocalComponentSku
import com.componentvault.android.model.ComponentAllocationRecord
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.MovementEntryDraft
import com.componentvault.android.model.OperationResult
import com.componentvault.android.model.StorageLocationRecord

@Composable
internal fun ComponentEditorSurface(
    existing: ComponentRecord?,
    initialDraft: ComponentDraft? = null,
    availableCategories: List<String> = emptyList(),
    storageLocations: List<StorageLocationRecord> = emptyList(),
    layoutMode: InventoryLayoutMode,
    onDismiss: () -> Unit,
    onSave: (ComponentDraft, (OperationResult) -> Unit) -> Unit,
    onSaveAndGenerateLabel: ((ComponentDraft, (OperationResult) -> Unit) -> Unit)? = null,
    editingId: String? = existing?.id,
    expectedUpdatedAt: String? = existing?.updatedAt,
) {
    val strings = vaultStrings()
    val isEditing = editingId != null
    val stateKey = editingId ?: initialDraft?.sku.orEmpty()
    var sku by rememberSaveable(stateKey) { mutableStateOf(existing?.sku ?: initialDraft?.sku.orEmpty()) }
    var name by rememberSaveable(stateKey) { mutableStateOf(existing?.name ?: initialDraft?.name.orEmpty()) }
    var category by rememberSaveable(stateKey) { mutableStateOf(existing?.category ?: initialDraft?.category.orEmpty()) }
    var packageName by rememberSaveable(stateKey) {
        mutableStateOf(existing?.packageName ?: initialDraft?.packageName.orEmpty())
    }
    var location by rememberSaveable(stateKey) { mutableStateOf(existing?.location ?: initialDraft?.location.orEmpty()) }
    var description by rememberSaveable(stateKey) {
        mutableStateOf(existing?.description ?: initialDraft?.description.orEmpty())
    }
    val photoSession = rememberComponentPhotoSession(
        stateKey = stateKey,
        openingDescription = existing?.description ?: initialDraft?.description.orEmpty(),
    )
    fun dismissEditor() {
        photoSession.finish(description, committed = false)
        onDismiss()
    }
    BackHandler(enabled = !layoutMode.prefersDialogForms, onBack = ::dismissEditor)
    var quantityText by rememberSaveable(stateKey) {
        mutableStateOf((existing?.quantity ?: initialDraft?.quantity ?: 0).toString())
    }
    var minStockText by rememberSaveable(stateKey) {
        mutableStateOf((existing?.minStock ?: initialDraft?.minStock ?: 0).toString())
    }
    var valuationIsTotal by rememberSaveable(stateKey) { mutableStateOf(initialDraft?.purchaseAmount != null) }
    fun valuationUnit(text: String): String? = com.componentvault.android.data.InventoryCost.text(com.componentvault.android.data.InventoryCost.decimal(text)?.let { if (valuationIsTotal) it.divide(java.math.BigDecimal(quantityText.toInt()), 16, java.math.RoundingMode.HALF_EVEN) else it })
    var valuationText by rememberSaveable(stateKey) { mutableStateOf((initialDraft?.purchaseAmount ?: initialDraft?.valuationUnitCost).orEmpty()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val title = if (!isEditing) {
        strings.forms.addComponentTitle
    } else {
        strings.forms.editComponentTitle
    }

    AdaptiveFormSurface(
        title = title,
        layoutMode = layoutMode,
        onDismiss = ::dismissEditor,
        saveLabel = if (onSaveAndGenerateLabel == null) {
            null
        } else {
            strings.common.actionSaveAndGenerateLabel
        },
        onSave = {
            sku = LocalComponentSku.ensure(sku)
            validateComponentDraft(
                strings = strings,
                existingId = editingId,
                expectedUpdatedAt = expectedUpdatedAt,
                sku = sku,
                name = name,
                category = category,
                packageName = packageName,
                location = location,
                description = description,
                quantityText = quantityText,
                minStockText = minStockText,
                onError = { errorMessage = it },
            )?.let { draft ->
                errorMessage = null
                val cost = runCatching { valuationUnit(valuationText) }.getOrElse { errorMessage = it.message; return@let }
                onSave(draft.copy(valuationUnitCost = cost.takeIf { isEditing }, valuationAmount = if (isEditing) purchaseTotal(valuationText,!valuationIsTotal,draft.quantity) else null, purchaseAmount = if (!isEditing) com.componentvault.android.data.InventoryCost.text(com.componentvault.android.data.InventoryCost.decimal(valuationText)?.let { if (valuationIsTotal) it else it.multiply(java.math.BigDecimal(draft.quantity)) }) else null)) { result ->
                    if (result.isSuccess) photoSession.finish(description, committed = true)
                    if (!result.isSuccess) {
                        errorMessage = result.message
                    }
                }
            }
        },
        onSecondarySave = {
            sku = LocalComponentSku.ensure(sku)
            validateComponentDraft(
                strings = strings,
                existingId = editingId,
                expectedUpdatedAt = expectedUpdatedAt,
                sku = sku,
                name = name,
                category = category,
                packageName = packageName,
                location = location,
                description = description,
                quantityText = quantityText,
                minStockText = minStockText,
                onError = { errorMessage = it },
            )?.let { draft ->
                errorMessage = null
                val cost = runCatching { valuationUnit(valuationText) }.getOrElse { errorMessage = it.message; return@let }
                onSaveAndGenerateLabel?.invoke(draft.copy(valuationUnitCost = cost.takeIf { isEditing }, valuationAmount = if (isEditing) purchaseTotal(valuationText,!valuationIsTotal,draft.quantity) else null, purchaseAmount = if (!isEditing) com.componentvault.android.data.InventoryCost.text(com.componentvault.android.data.InventoryCost.decimal(valuationText)?.let { if (valuationIsTotal) it else it.multiply(java.math.BigDecimal(draft.quantity)) }) else null)) { result ->
                    if (result.isSuccess) photoSession.finish(description, committed = true)
                    if (!result.isSuccess) {
                        errorMessage = result.message
                    }
                }
            }
        },
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !valuationIsTotal, onClick = { valuationIsTotal = false }, label = { Text(if (!isEditing) "购买单价" else "补录单价") })
                FilterChip(selected = valuationIsTotal, onClick = { valuationIsTotal = true }, label = { Text(if (!isEditing) "购买总金额" else "补录当前总额") })
            }
            OutlinedTextField(value = valuationText, onValueChange = { valuationText = it }, label = { Text(if (!isEditing) "实际采购金额（CNY）" else if (valuationIsTotal) "补录当前库存总额（CNY）" else "补录当前库存单价（CNY）") }, supportingText = { Text(if (!isEditing) "留空表示成本未知；0 表示赠品。" else "留空保留现有成本；填写将按当前库存估值并记录调整，不修改历史采购。") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        item {
            SectionPane(
                title = strings.forms.componentBasicTitle,
                supporting = strings.forms.componentBasicSubtitle,
            ) {
                OutlinedTextField(
                    value = sku,
                    onValueChange = { sku = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.common.fieldSku) },
                    supportingText = { Text(androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.component_internal_sku_help)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.common.fieldName) },
                    singleLine = true,
                )
                EditableChoiceField(
                    value = category,
                    onValueChange = { category = it },
                    label = strings.common.fieldCategory,
                    options = availableCategories.distinct(),
                    optionLabel = { localizedCategoryLabel(it) },
                    tag = "component_editor_category",
                )
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.common.fieldPackage) },
                    singleLine = true,
                )
            }
        }
        item {
            SectionPane(title = strings.forms.componentStockTitle) {
                QuantityInputField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = strings.common.fieldQuantity,
                    minimum = 0,
                )
                OutlinedTextField(
                    value = minStockText,
                    onValueChange = { minStockText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.common.fieldMinimumStock) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
        item {
            SectionPane(title = strings.forms.componentStorageTitle) {
                ImportLocationPicker(
                    value = location,
                    onValueChange = { location = it },
                    storageLocations = storageLocations,
                )
                ComponentPhotoEditor(description, { description = it }, session = photoSession)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.common.fieldDescription) },
                    minLines = 3,
                )
            }
        }
        if (!errorMessage.isNullOrBlank()) {
            item {
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EditableChoiceField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<String>,
    optionLabel: @Composable (String) -> String,
    tag: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = if (value in options) optionLabel(value) else value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().testTag(tag),
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { expanded = true }, enabled = options.isNotEmpty(),
                    modifier = Modifier.testTag("${tag}_choices")) {
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = label)
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = {
                    onValueChange(option)
                    expanded = false
                })
            }
        }
    }
}
private fun validateComponentDraft(
    strings: ComponentVaultStrings,
    existingId: String?,
    expectedUpdatedAt: String?,
    sku: String,
    name: String,
    category: String,
    packageName: String,
    location: String,
    description: String,
    quantityText: String,
    minStockText: String,
    onError: (String) -> Unit,
): ComponentDraft? {
    val quantity = quantityText.toIntOrNull()
    val minStock = minStockText.toIntOrNull()
    if (sku.isBlank() || name.isBlank() || category.isBlank() || packageName.isBlank() || location.isBlank()) {
        onError(strings.forms.componentRequiredFields)
        return null
    }
    if (quantity == null || minStock == null) {
        onError(strings.forms.invalidQuantityMinStock)
        return null
    }
    if (quantity < 0 || minStock < 0) {
        onError(strings.forms.componentNonNegative)
        return null
    }

    return ComponentDraft(
        id = existingId,
        expectedUpdatedAt = expectedUpdatedAt,
        sku = sku,
        name = name,
        category = category,
        packageName = packageName,
        location = location,
        description = description,
        quantity = quantity,
        minStock = minStock,
    )
}

internal data class MovementEditorState(
    val purchaseText: String = "",
    val purchaseIsUnit: Boolean = false,
    val movementType: String = "inbound",
    val quantityText: String = "1",
    val reason: String = "",
    val note: String = "",
    val locationId: String = "",
    val destinationLocationId: String = "",
    val errorMessage: String? = null,
)

internal fun validateMovementEditorState(
    strings: ComponentVaultStrings,
    selectedComponentId: String?,
    state: MovementEditorState,
): Result<MovementEntryDraft> {
    return runCatching {
        val quantity = state.quantityText.toIntOrNull()
        if (selectedComponentId.isNullOrBlank() || state.reason.isBlank() || quantity == null) {
            throw IllegalStateException(strings.forms.chooseComponentTypeReason)
        }
        if (state.movementType == "adjustment" && quantity == 0) {
            throw IllegalStateException(strings.forms.adjustmentNonZero)
        }
        if (state.movementType != "adjustment" && quantity <= 0) {
            throw IllegalStateException(strings.forms.movementQuantityPositive)
        }
        if (state.locationId.isBlank()) throw IllegalStateException("请选择发生库存变动的库位。")
        if (state.movementType == "transfer" &&
            (state.destinationLocationId.isBlank() || state.destinationLocationId == state.locationId)
        ) throw IllegalStateException("调拨必须选择两个不同库位。")
        MovementEntryDraft(
            purchaseAmount = if (state.movementType == "inbound") com.componentvault.android.data.InventoryCost.text(com.componentvault.android.data.InventoryCost.decimal(state.purchaseText)?.let { if (state.purchaseIsUnit) it.multiply(java.math.BigDecimal(quantity)) else it }) else null,
            componentId = selectedComponentId,
            movementType = state.movementType,
            quantity = quantity,
            reason = state.reason,
            note = state.note,
            locationId = state.locationId,
            destinationLocationId = state.destinationLocationId.takeIf { state.movementType == "transfer" },
        )
    }
}

@Composable
internal fun MovementTypeSelector(
    movementType: String,
    onMovementTypeChange: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("inbound", "outbound", "adjustment", "transfer").forEach { type ->
            FilterChip(
                selected = movementType == type,
                onClick = { onMovementTypeChange(type) },
                label = { Text(movementTypeLabel(type)) },
            )
        }
    }
}

@Composable
internal fun MovementEntryFields(
    state: MovementEditorState,
    onStateChange: (MovementEditorState) -> Unit,
    storageLocations: List<StorageLocationRecord> = emptyList(),
    sourceLocations: List<StorageLocationRecord> = storageLocations,
    sourceQuantities: Map<String, Int> = emptyMap(),
    stockError: String? = null,
    sourceMessage: String? = null,
) {
    val strings = vaultStrings()
    SectionPane(title = strings.forms.movementEntryTitle) {
        MovementLocationSelector(
            title = stringResource(R.string.movement_source_location),
            value = state.locationId,
            locations = sourceLocations,
            onSelect = { onStateChange(state.copy(locationId = it, errorMessage = null)) },
            quantities = sourceQuantities,
            selectedLocation = storageLocations.firstOrNull { it.id == state.locationId },
        )
        if (sourceMessage != null) Text(
            sourceMessage,
            color = MaterialTheme.colorScheme.error,
        )
        if (state.movementType == "transfer") {
            MovementLocationSelector(
                title = stringResource(R.string.movement_destination_location),
                value = state.destinationLocationId,
                locations = storageLocations.filterNot { it.id == state.locationId },
                onSelect = { onStateChange(state.copy(destinationLocationId = it, errorMessage = null)) },
            )
        }
        QuantityInputField(
            value = state.quantityText,
            onValueChange = {
                onStateChange(state.copy(quantityText = it, errorMessage = null))
            },
            modifier = Modifier.fillMaxWidth(),
            label = strings.common.fieldQuantity,
            minimum = if (state.movementType == "adjustment") Int.MIN_VALUE else 1,
            skipZero = state.movementType == "adjustment",
        )
        (state.errorMessage ?: stockError)?.takeIf { it.isNotBlank() }?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.movementType == "inbound") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !state.purchaseIsUnit, onClick = { onStateChange(state.copy(purchaseIsUnit = false)) }, label = { Text("购买总金额") })
                FilterChip(selected = state.purchaseIsUnit, onClick = { onStateChange(state.copy(purchaseIsUnit = true)) }, label = { Text("购买单价") })
            }
            OutlinedTextField(value = state.purchaseText, onValueChange = { onStateChange(state.copy(purchaseText = it, errorMessage = null)) }, label = { Text(if (state.purchaseIsUnit) "购买单价（CNY）" else "购买总金额（CNY）") }, supportingText = { Text("留空表示成本未知；0 表示赠品。") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            val entered = state.purchaseText.toBigDecimalOrNull()
            val count = state.quantityText.toIntOrNull()
            if (entered != null && count != null && count > 0) Text(if (state.purchaseIsUnit) "折算总金额（CNY）：${entered.multiply(java.math.BigDecimal(count)).toPlainString()}" else "折算单价（CNY）：${entered.divide(java.math.BigDecimal(count), 16, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()}")
        }
        OutlinedTextField(
            value = state.reason,
            onValueChange = {
                onStateChange(state.copy(reason = it, errorMessage = null))
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.common.fieldReason) },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.note,
            onValueChange = {
                onStateChange(state.copy(note = it, errorMessage = null))
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.common.fieldNote) },
            minLines = 2,
        )
        Text(
            text = strings.forms.movementEditorInstruction,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Display names to people; submit the stable ID selected from an active location. */
@Composable
internal fun MovementLocationSelector(
    title: String,
    value: String,
    locations: List<StorageLocationRecord>,
    quantities: Map<String, Int> = emptyMap(),
    selectedLocation: StorageLocationRecord? = null,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val active = locations.filterNot(StorageLocationRecord::deleted)
    @Composable
    fun locationLabel(location: StorageLocationRecord): String {
        val available = quantities[location.id] ?: return location.name
        return stringResource(R.string.movement_location_with_stock, location.name, available)
    }
    Text(title, style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = active.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text((selectedLocation ?: active.firstOrNull { it.id == value })?.let { locationLabel(it) }
                ?: stringResource(R.string.importer_select_location))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 280.dp).widthIn(max = 320.dp),
        ) {
            active.forEach { location ->
                DropdownMenuItem(
                    text = { Text(locationLabel(location), maxLines = 2) },
                    onClick = { expanded = false; onSelect(location.id) },
                )
            }
        }
    }
}

@Composable
internal fun MovementEditorSurface(
    components: List<ComponentRecord>,
    selectedComponentId: String?,
    layoutMode: InventoryLayoutMode,
    initialMovementType: String? = null,
    allowManualComponentSelection: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (MovementEntryDraft, (OperationResult) -> Unit) -> Unit,
    storageLocations: List<StorageLocationRecord> = emptyList(),
    allocations: List<ComponentAllocationRecord> = emptyList(),
) {
    val strings = vaultStrings()
    val defaultReasons = listOf("inbound", "outbound", "adjustment", "transfer")
        .associateWith { movementTypeLabel(it) }
    var componentMenuExpanded by remember { mutableStateOf(false) }
    var chosenComponentId by remember(selectedComponentId) { mutableStateOf(selectedComponentId) }
    val selectedComponent = if (chosenComponentId == null) components.firstOrNull()
        else components.firstOrNull { it.id == chosenComponentId }
    var saving by remember { mutableStateOf(false) }
    var editorState by remember(initialMovementType) {
        mutableStateOf(
            MovementEditorState(
                movementType = initialMovementType ?: "inbound",
                reason = defaultReasons.getValue(initialMovementType ?: "inbound"),
            ),
        )
    }
    val activeLocations = storageLocations.filterNot(StorageLocationRecord::deleted)
    val quantities = allocations.asSequence()
        .filter { it.componentId == selectedComponent?.id }
        .associate { it.locationId to it.quantity }
    fun sourceOptions(type: String) = if (type == "outbound" || type == "transfer") {
        activeLocations.filter { (quantities[it.id] ?: 0) > 0 }
    } else activeLocations
    val sourceLocations = sourceOptions(editorState.movementType)
    val requiresStock = editorState.movementType == "outbound" || editorState.movementType == "transfer"
    val sourceId = editorState.locationId.ifBlank {
        sourceLocations.firstOrNull { it.id == selectedComponent?.location }?.id
            ?: if (requiresStock) sourceLocations.maxByOrNull { quantities[it.id] ?: 0 }?.id.orEmpty() else ""
    }
    val stateWithSource = editorState.copy(locationId = sourceId)
    val sourceIsValid = sourceLocations.any { it.id == sourceId }
    val available = quantities[sourceId] ?: 0
    val quantity = editorState.quantityText.toIntOrNull()
    val requested = when {
        quantity == null -> 0L
        requiresStock -> quantity.toLong()
        editorState.movementType == "adjustment" && quantity < 0 -> -quantity.toLong()
        else -> 0L
    }
    val stockError = if (sourceId.isNotBlank() && requested > available) {
        stringResource(R.string.movement_source_stock_insufficient, available, requested)
    } else null
    val sourceMessage = when {
        activeLocations.isEmpty() -> stringResource(R.string.movement_no_locations)
        requiresStock && sourceLocations.isEmpty() -> stringResource(R.string.movement_no_stock_locations)
        sourceId.isNotBlank() && !sourceIsValid && stockError == null ->
            stringResource(R.string.scan_stock_select_location)
        else -> null
    }

    AdaptiveFormSurface(
        title = strings.forms.recordStockMovementTitle,
        layoutMode = layoutMode,
        onDismiss = onDismiss,
        saveEnabled = !saving && selectedComponent != null && sourceIsValid && stockError == null,
        onSave = {
            validateMovementEditorState(
                strings = strings,
                selectedComponentId = selectedComponent?.id,
                state = stateWithSource,
            ).onSuccess { draft ->
                saving = true
                editorState = stateWithSource.copy(errorMessage = null)
                onSave(draft) { result ->
                    saving = false
                    if (!result.isSuccess) {
                        editorState = editorState.copy(errorMessage = result.message)
                    }
                }
            }.onFailure { throwable ->
                editorState = editorState.copy(
                    errorMessage = throwable.message ?: strings.forms.chooseComponentTypeReason,
                )
            }
        },
    ) {
        item {
            SectionPane(
                title = strings.forms.movementScopeTitle,
                supporting = strings.forms.movementScopeSubtitle,
            ) {
                if (allowManualComponentSelection) {
                    Column {
                        OutlinedButton(onClick = { componentMenuExpanded = true }) {
                            Text(
                                selectedComponent?.let { "${it.name} (${it.sku})" }
                                    ?: strings.common.fieldComponent,
                            )
                        }
                        DropdownMenu(
                            expanded = componentMenuExpanded,
                            onDismissRequest = { componentMenuExpanded = false },
                        ) {
                            components.forEach { component ->
                                DropdownMenuItem(
                                    text = { Text("${component.name} (${component.sku})") },
                                    onClick = {
                                        chosenComponentId = component.id
                                        componentMenuExpanded = false
                                        editorState = editorState.copy(locationId = "", errorMessage = null)
                                    },
                                )
                            }
                        }
                    }
                } else {
                    selectedComponent?.let { component ->
                        ValueBlock(label = strings.common.fieldName, value = component.name)
                        ValueBlock(label = strings.common.fieldSku, value = component.sku)
                        ValueBlock(label = strings.common.fieldCategory, value = localizedCategoryLabel(component.category))
                        ValueBlock(label = strings.common.fieldPackage, value = component.packageName)
                        ValueBlock(label = strings.common.fieldLocation, value = displayLocationName(
                            component.location, storageLocations, strings.common.labelNotConfigured,
                        ))
                        ValueBlock(
                            label = strings.movements.currentStockLabel,
                            value = component.quantity.toString(),
                        )
                    }
                }
                MovementTypeSelector(
                    movementType = editorState.movementType,
                    onMovementTypeChange = { movementType ->
                        editorState = editorState.copy(
                            movementType = movementType,
                            locationId = sourceId.takeIf { id -> sourceOptions(movementType).any { it.id == id } }.orEmpty(),
                            reason = if (editorState.reason.isBlank() ||
                                editorState.reason == defaultReasons[editorState.movementType]
                            ) defaultReasons.getValue(movementType) else editorState.reason,
                            errorMessage = null,
                        )
                    },
                )
            }
        }
        item {
            MovementEntryFields(
                state = stateWithSource,
                onStateChange = { editorState = it },
                storageLocations = activeLocations,
                sourceLocations = sourceLocations,
                sourceQuantities = if (requiresStock || editorState.movementType == "adjustment") {
                    activeLocations.associate { it.id to (quantities[it.id] ?: 0) }
                } else emptyMap(),
                stockError = stockError,
                sourceMessage = sourceMessage,
            )
        }
    }
}

@Composable
internal fun DeleteComponentConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = vaultStrings()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.forms.deleteConfirmTitle) },
        text = { Text(strings.forms.deleteConfirmMessage) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(strings.common.actionDelete)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.common.actionCancel)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdaptiveFormSurface(
    title: String,
    layoutMode: InventoryLayoutMode,
    onDismiss: () -> Unit,
    saveLabel: String? = null,
    onSave: () -> Unit,
    onSecondarySave: (() -> Unit)? = null,
    saveEnabled: Boolean = true,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val strings = vaultStrings()

    if (layoutMode.prefersDialogForms) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            // Keep the whole form in the visible dialog viewport when the IME opens.
            // Otherwise the window may pan the focused field and move its toolbar offscreen.
            Box(
                modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .heightIn(max = 860.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        TopAppBar(
                            title = { Text(title) },
                            navigationIcon = {
                                TextButton(onClick = onDismiss) {
                                    Text(strings.common.actionCancel)
                                }
                            },
                            actions = {
                                if (saveLabel != null && onSecondarySave != null) {
                                    TextButton(onClick = onSecondarySave) {
                                        Text(saveLabel)
                                    }
                                }
                                TextButton(onClick = onSave, enabled = saveEnabled) {
                                    Text(strings.common.actionSave)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                            ),
                        )
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f).testTag("component_editor_fields"),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            content = content,
                        )
                    }
                }
            }
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        TextButton(onClick = onDismiss) {
                            Text(strings.common.actionBack)
                        }
                    },
                    actions = {
                        if (saveLabel != null && onSecondarySave != null) {
                            TextButton(onClick = onSecondarySave) {
                                Text(saveLabel)
                            }
                        }
                        TextButton(onClick = onSave, enabled = saveEnabled) {
                            Text(strings.common.actionSave)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .testTag("component_editor_fields"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}
