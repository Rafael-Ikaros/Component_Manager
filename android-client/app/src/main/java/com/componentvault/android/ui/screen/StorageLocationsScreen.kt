package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.model.OperationResult
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.model.StorageLocationSaveIntent

@Composable
internal fun StorageLocationsScreen(
    locations: List<StorageLocationRecord>,
    onDismiss: () -> Unit,
    onSave: (String, String, StorageLocationSaveIntent, (OperationResult) -> Unit) -> Unit,
    onDelete: (String, (OperationResult) -> Unit) -> Unit,
    onPrintLabel: (StorageLocationRecord) -> Unit = {},
) {
    var editor by remember { mutableStateOf<LocationEditor?>(null) }
    var deleteTarget by remember { mutableStateOf<StorageLocationRecord?>(null) }
    var operationMessage by remember { mutableStateOf("") }
    var operationFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val value = editor
    if (value != null) {
        LocationEditorPage(
            initial = value,
            busy = busy,
            onDismiss = { if (!busy) editor = null },
            onSave = { code, name, setError ->
                busy = true
                val intent = if (value.isEditing) StorageLocationSaveIntent.Edit else StorageLocationSaveIntent.Create
                onSave(code, name, intent) { result ->
                    busy = false
                    if (result.isSuccess) {
                        editor = null
                        operationMessage = result.message
                        operationFailed = false
                    } else {
                        setError(result.message)
                    }
                }
            },
        )
    } else {
        SettingsRouteScaffold(
            title = stringResource(R.string.locations_title),
            caption = stringResource(R.string.v2_locations_count, locations.size),
            onBack = onDismiss,
            navigationEnabled = !busy,
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .padding(horizontal = 18.dp).testTag("locations_list"),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
            ) {
                if (operationMessage.isNotBlank()) {
                    item {
                        Text(
                            operationMessage,
                            color = if (operationFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("locations_operation_message").padding(bottom = 12.dp),
                        )
                    }
                }
                if (locations.isEmpty()) {
                    item {
                        Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.locations_empty_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.locations_empty_description),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                itemsIndexed(locations, key = { _, location -> location.id }) { index, location ->
                    val top = if (index == 0) 24.dp else 0.dp
                    val bottom = if (index == locations.lastIndex) 24.dp else 0.dp
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    SettingsSectionCard(
                                        title = location.id,
                                        supporting = location.name,
                                        icon = R.drawable.v2_map_pin,
                                        selected = false,
                                        onClick = {
                                            operationMessage = ""
                                            editor = LocationEditor(location.id, location.name, isEditing = true)
                                        },
                                        enabled = !busy,
                                        modifier = Modifier.testTag("locations_edit_${location.id}"),
                                    )
                                }
                                var actionsExpanded by remember(location.id) { mutableStateOf(false) }
                                Box(Modifier.padding(end = 6.dp)) {
                                    IconButton(onClick = { actionsExpanded = true }, enabled = !busy) {
                                        Icon(painterResource(R.drawable.v2_ellipsis_vertical),
                                            stringResource(R.string.inventory_ui_more_actions), Modifier.size(18.dp))
                                    }
                                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.location_qr_print)) },
                                            onClick = { actionsExpanded = false; onPrintLabel(location) },
                                            modifier = Modifier.testTag("locations_print_${location.id}"),
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_delete)) },
                                            onClick = { actionsExpanded = false; deleteTarget = location },
                                            modifier = Modifier.testTag("locations_delete_${location.id}"),
                                        )
                                    }
                                }
                            }
                            if (index != locations.lastIndex) {
                                HorizontalDivider(Modifier.padding(start = 51.dp, end = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = { operationMessage = ""; editor = LocationEditor() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 21.dp).heightIn(min = 48.dp)
                            .testTag("locations_add"),
                        shape = RoundedCornerShape(15.dp),
                    ) { Text(stringResource(R.string.locations_add)) }
                }
            }
        }
    }

    deleteTarget?.let { location ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            title = { Text(stringResource(R.string.locations_delete_title)) },
            text = { Text(stringResource(R.string.locations_delete_description, location.name, location.id)) },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        onDelete(location.id) { result ->
                            busy = false
                            deleteTarget = null
                            operationMessage = result.message
                            operationFailed = !result.isSuccess
                        }
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

internal data class LocationEditor(
    val code: String = "",
    val name: String = "",
    val isEditing: Boolean = false,
)

@Composable
private fun LocationEditorPage(
    initial: LocationEditor,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, (String) -> Unit) -> Unit,
) {
    var code by remember(initial) { mutableStateOf(initial.code) }
    var name by remember(initial) { mutableStateOf(initial.name) }
    var error by remember(initial) { mutableStateOf("") }
    val codeInvalid = code.isBlank() || code.length > 120
    val nameInvalid = name.isBlank() || name.length > 200

    SettingsRouteScaffold(
        title = stringResource(if (initial.isEditing) R.string.locations_edit_title else R.string.locations_create_title),
        caption = if (initial.isEditing) stringResource(R.string.locations_code_immutable_hint) else null,
        navigationEnabled = !busy,
        onBack = onDismiss,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (initial.isEditing) {
                Row(Modifier.fillMaxWidth().padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.locations_code_label), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(code, Modifier.testTag("locations_code"))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            } else {
                SettingsTextField(
                    value = code,
                    onValueChange = { code = it.trim(); error = "" },
                    label = { Text(stringResource(R.string.locations_code_label)) },
                    isError = codeInvalid && code.isNotEmpty(),
                    enabled = !busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("locations_code"),
                )
            }
            SettingsTextField(
                value = name,
                onValueChange = { name = it; error = "" },
                label = { Text(stringResource(R.string.locations_name_label)) },
                isError = nameInvalid && name.isNotEmpty(),
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("locations_name"),
            )
            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("locations_editor_error"))
            }
            if (busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(stringResource(R.string.locations_saving))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                TextButton(
                    enabled = !busy, onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    enabled = !busy && !codeInvalid && !nameInvalid,
                    onClick = { onSave(code.trim(), name.trim()) { error = it } },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(15.dp),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}
