package com.componentvault.android.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.data.LabelWorkbookColumn
import com.componentvault.android.data.LabelWorkbookExporter
import com.componentvault.android.model.ComponentRecord
import com.componentvault.android.model.ComponentAllocationRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun InventoryBackupScreen(
    viewModel: InventoryViewModel,
    components: List<ComponentRecord> = emptyList(),
    onDismiss: () -> Unit,
) {
    val state = viewModel.backupUiState
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var labelColumns by remember { mutableStateOf(LabelWorkbookExporter.defaultColumns) }
    var labelExportMessage by remember { mutableStateOf("") }
    var labelExportDialogVisible by remember { mutableStateOf(false) }
    var labelExportBusy by remember { mutableStateOf(false) }
    var selectedLocationsOnly by remember { mutableStateOf(false) }
    var selectedLocationIds by remember { mutableStateOf<Set<String?>>(emptySet()) }
    var pendingLabelRows by remember { mutableStateOf<List<LabelWorkbookExporter.Row>>(emptyList()) }
    var pendingLabelColumns by remember { mutableStateOf<Set<LabelWorkbookColumn>>(emptySet()) }
    val allocations = viewModel.uiState.inventory.allocations
    val activeComponents = components.filterNot(ComponentRecord::deleted)
    val activeComponentIds = activeComponents.map(ComponentRecord::id).toSet()
    val assignedComponentIds = allocations.map(ComponentAllocationRecord::componentId).toSet()
    val locationOptions = buildList {
        allocations.filter { it.componentId in activeComponentIds }
            .forEach { add(it.locationId.takeIf(String::isNotBlank) to it.locationName) }
        activeComponents.filter { it.id !in assignedComponentIds }
            .forEach { add(it.location.takeIf(String::isNotBlank) to it.location) }
    }.distinctBy { it.first }.sortedWith(compareBy({ it.first != null }, { it.second.lowercase() }))
    val previewRows = LabelWorkbookExporter.rows(
        components, allocations, if (selectedLocationsOnly) selectedLocationIds else null,
    )
    val create = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri -> uri?.let(viewModel::exportInventoryBackup) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::previewInventoryBackup)
    }
    val createLabels = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri ->
        val selectedColumns = pendingLabelColumns
        val selectedRows = pendingLabelRows
        pendingLabelRows = emptyList()
        pendingLabelColumns = emptySet()
        if (uri != null) {
            if (selectedRows.isEmpty() || selectedColumns.isEmpty()) {
                labelExportMessage = context.getString(R.string.label_workbook_export_retry)
            } else {
                labelExportBusy = true
                scope.launch {
                    runCatching {
                        val bytes = withContext(Dispatchers.Default) {
                            LabelWorkbookExporter.exportRows(selectedRows, selectedColumns)
                        }
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                                ?: error("无法打开导出文件。")
                        }
                    }.onSuccess {
                        labelExportMessage = context.getString(R.string.label_workbook_export_success)
                    }.onFailure {
                        labelExportMessage = it.message ?: context.getString(R.string.label_workbook_export_failure)
                    }
                    labelExportBusy = false
                }
            }
        }
    }
    SettingsRouteScaffold(
        title = stringResource(R.string.appearance_backup_title),
        caption = stringResource(R.string.appearance_backup_hint),
        onBack = onDismiss,
    ) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    SettingsSectionCard(
                        title = stringResource(R.string.v2_backup_export),
                        supporting = stringResource(R.string.v2_backup_export_hint),
                        icon = R.drawable.v2_download,
                        selected = false,
                        enabled = !state.loading,
                        onClick = { create.launch("component-vault-backup.xlsx") },
                    )
                    HorizontalDivider(Modifier.padding(start = 51.dp, end = 16.dp))
                    SettingsSectionCard(
                        title = stringResource(R.string.v2_backup_restore),
                        supporting = stringResource(R.string.v2_backup_restore_hint),
                        icon = R.drawable.v2_folder_open,
                        selected = false,
                        enabled = !state.loading,
                        onClick = { open.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/zip")) },
                    )
                    HorizontalDivider(Modifier.padding(start = 51.dp, end = 16.dp))
                    SettingsSectionCard(
                        title = stringResource(R.string.v2_backup_labels),
                        supporting = stringResource(R.string.v2_backup_labels_hint),
                        icon = R.drawable.v2_file_spreadsheet,
                        selected = false,
                        enabled = !state.loading && !labelExportBusy && activeComponents.isNotEmpty(),
                        onClick = { labelExportDialogVisible = true },
                    )
                }
            }
            if (activeComponents.isEmpty()) {
                Text(stringResource(R.string.label_workbook_export_empty), style = MaterialTheme.typography.bodySmall)
            }
            if (labelExportMessage.isNotBlank()) Text(labelExportMessage)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.preview?.let { preview ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(if (preview.workbook.source.name == "ComponentVault") R.string.backup_own_source else R.string.backup_lcsc_source),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(stringResource(R.string.backup_counts, preview.newComponentCount, preview.newLocationCount, preview.skippedComponentCount))
                        preview.warnings.forEach { warning -> Text("• $warning", color = MaterialTheme.colorScheme.tertiary) }
                        Text(stringResource(R.string.backup_merge_notice), style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = viewModel::confirmInventoryRestore,
                            enabled = !state.loading && (preview.newComponentCount > 0 || preview.newLocationCount > 0),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.backup_confirm)) }
                    }
                }
            }
            if (state.message.isNotBlank()) Text(state.message)
        }
    }
    if (labelExportDialogVisible) {
        AlertDialog(
            onDismissRequest = { labelExportDialogVisible = false },
            title = { Text(stringResource(R.string.label_workbook_export_title)) },
            text = {
                Column {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item { Text(stringResource(R.string.label_workbook_export_locations_title), style = MaterialTheme.typography.titleSmall) }
                    item {
                        Row(Modifier.fillMaxWidth().clickable { selectedLocationsOnly = false }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = !selectedLocationsOnly, onClick = { selectedLocationsOnly = false })
                            Text(stringResource(R.string.label_workbook_export_all_locations))
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().clickable { selectedLocationsOnly = true }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = selectedLocationsOnly, onClick = { selectedLocationsOnly = true })
                            Text(stringResource(R.string.label_workbook_export_selected_locations))
                        }
                    }
                    if (selectedLocationsOnly) {
                        items(locationOptions) { (id, name) ->
                            Row(Modifier.fillMaxWidth().clickable {
                                selectedLocationIds = if (id in selectedLocationIds) selectedLocationIds - id else selectedLocationIds + id
                            }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(
                                    checked = id in selectedLocationIds,
                                    onCheckedChange = { checked ->
                                        selectedLocationIds = if (checked) selectedLocationIds + id else selectedLocationIds - id
                                    },
                                )
                                Text(if (id == null) stringResource(R.string.label_workbook_export_unknown_location)
                                    else if (name == id) name else "$name ($id)")
                            }
                        }
                    }
                    item { Text(stringResource(R.string.label_workbook_export_fields_title), style = MaterialTheme.typography.titleSmall) }
                    items(LabelWorkbookColumn.entries) { column ->
                        Row(Modifier.fillMaxWidth().clickable {
                            labelColumns = if (column in labelColumns) labelColumns - column else labelColumns + column
                        }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                checked = column in labelColumns,
                                onCheckedChange = { checked -> labelColumns = if (checked) labelColumns + column else labelColumns - column },
                            )
                            Text(labelWorkbookColumnTitle(column))
                        }
                    }
                    }
                    Text(stringResource(R.string.label_workbook_export_preview_count, previewRows.size))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLabelRows = previewRows.toList()
                        pendingLabelColumns = labelColumns.toSet()
                        labelExportDialogVisible = false
                        createLabels.launch("component-vault-labels.xlsx")
                    },
                    enabled = labelColumns.isNotEmpty() && previewRows.isNotEmpty(),
                ) { Text(stringResource(R.string.label_workbook_export_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { labelExportDialogVisible = false }) { Text(stringResource(R.string.label_workbook_export_cancel)) }
            },
        )
    }
}

@Composable
private fun labelWorkbookColumnTitle(column: LabelWorkbookColumn): String = stringResource(
    when (column) {
        LabelWorkbookColumn.Name -> R.string.label_workbook_column_name
        LabelWorkbookColumn.Sku -> R.string.label_workbook_column_sku
        LabelWorkbookColumn.Model -> R.string.label_workbook_column_model
        LabelWorkbookColumn.PackageName -> R.string.label_workbook_column_package
        LabelWorkbookColumn.Category -> R.string.label_workbook_column_category
        LabelWorkbookColumn.Location -> R.string.label_workbook_column_location
        LabelWorkbookColumn.Quantity -> R.string.label_workbook_column_quantity
        LabelWorkbookColumn.LongQrText -> R.string.label_workbook_column_long_qr
        LabelWorkbookColumn.ShortQrText -> R.string.label_workbook_column_short_qr
    },
)
