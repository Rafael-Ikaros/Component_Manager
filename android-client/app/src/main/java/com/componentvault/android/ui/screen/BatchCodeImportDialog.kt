package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.model.StorageLocationRecord

@Composable
internal fun BatchCodeImportDialog(
    location: String,
    locations: List<StorageLocationRecord>,
    onLocationChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onText: (String) -> Unit,
    onXlsx: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.batch_codes_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.batch_codes_hint))
                if (!busy) ImportLocationPicker(location, onLocationChange, locations)
                OutlinedTextField(text, { if (it.length <= 64 * 1024) text = it },
                    label = { Text(stringResource(R.string.batch_codes_input)) },
                    placeholder = { Text("C25804 100\nC14663 50") }, minLines = 3, maxLines = 6,
                    enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("batch_codes_input"))
                OutlinedButton(onClick = onXlsx, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.batch_codes_xlsx))
                }
                Text(stringResource(R.string.batch_codes_xlsx_hint), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onText(text) }, enabled = !busy && text.isNotBlank()) {
                Text(stringResource(R.string.batch_codes_parse))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
