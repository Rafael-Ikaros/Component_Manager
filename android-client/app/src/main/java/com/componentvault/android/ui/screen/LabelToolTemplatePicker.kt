package com.componentvault.android.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.componentvault.android.R

internal enum class LabelToolTemplate(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val sample: String,
) {
    Component(R.string.label_tool_component, R.string.label_ui_component_desc, "R  0603   ▦"),
    ComponentCompact(R.string.label_ui_component_compact, R.string.label_ui_component_compact_desc, "R   ▦"),
    ComponentText(R.string.label_ui_component_text, R.string.label_ui_component_text_desc, "R  0603"),
    Text(R.string.label_tool_text, R.string.label_ui_text_desc, "Aa  123"),
    Qr(R.string.label_tool_qr, R.string.label_ui_qr_desc, "▦"),
    Free(R.string.label_tool_free, R.string.label_ui_free_desc, "Aa     ▦"),
}

@Composable
internal fun LabelToolTemplatePicker(
    onSelect: (LabelToolTemplate) -> Unit,
    onBack: () -> Unit,
) {
    SecondaryPageScaffold(title = stringResource(R.string.label_tool_title), onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LabelToolTemplate.entries.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { template ->
                        Card(onClick = { onSelect(template) }, modifier = Modifier.weight(1f)) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().height(80.dp)
                                        .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(template.sample, style = MaterialTheme.typography.titleMedium)
                                }
                                Text(stringResource(template.title), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(template.description), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
