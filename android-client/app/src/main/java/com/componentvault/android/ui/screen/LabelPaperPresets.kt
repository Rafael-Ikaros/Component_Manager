package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.data.LabelPaperPresetStore
import com.componentvault.android.data.M1TestPaperProfile

@Composable
internal fun LabelPaperPresets(store: LabelPaperPresetStore, paper: M1TestPaperProfile?,
                              enabled: Boolean, onSelect: (M1TestPaperProfile) -> Unit) {
    var presets by remember(store) { mutableStateOf(store.load()) }
    var name by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.label_paper_presets), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.label_paper_presets_hint), style = MaterialTheme.typography.bodySmall)
    presets.forEach { preset ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { name = preset.name; onSelect(preset.paper) }, enabled = enabled,
                modifier = Modifier.weight(1f)) {
                Text("${preset.name} · ${preset.paper.widthMm} × ${preset.paper.heightMm} · ${preset.paper.rotationDegrees}°")
            }
            TextButton(onClick = { presets = store.delete(preset.name) }, enabled = enabled) {
                Text(stringResource(R.string.label_paper_preset_delete))
            }
        }
    }
    OutlinedTextField(name, { name = it.take(40) }, enabled = enabled, singleLine = true,
        label = { Text(stringResource(R.string.label_paper_preset_name)) }, modifier = Modifier.fillMaxWidth())
    val updating = presets.any { it.name == name.trim() }
    TextButton(onClick = { paper?.let { presets = store.save(name, it) } },
        enabled = enabled && paper != null && name.isNotBlank() &&
            (updating || presets.size < LabelPaperPresetStore.MaxPresets)) {
        Text(stringResource(if (updating) R.string.label_paper_preset_update else R.string.label_paper_preset_save))
    }
}
