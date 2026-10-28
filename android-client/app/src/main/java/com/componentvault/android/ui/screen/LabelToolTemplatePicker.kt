package com.componentvault.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.data.M1TestPaperProfile

internal enum class LabelToolTemplate { Component, ComponentCompact, ComponentText, Text, Qr, Free }

@Composable
internal fun LabelToolTemplatePicker(
    onSelect: (LabelToolTemplate, M1TestPaperProfile) -> Unit,
    onBack: () -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var widthText by rememberSaveable { mutableStateOf("40") }
    var heightText by rememberSaveable { mutableStateOf("60") }
    val paper = runCatching { M1TestPaperProfile(widthText.toFloat(), heightText.toFloat()) }.getOrNull()
    val template = chosen?.let { name -> LabelToolTemplate.entries.firstOrNull { it.name == name } }

    SecondaryPageScaffold(
        title = stringResource(if (template == null) R.string.label_tool_title else R.string.label_ui_choose_paper),
        onBack = { if (template == null) onBack() else chosen = null },
        bottomBar = {
            if (template != null) Button(onClick = { if (template != null && paper != null) onSelect(template, paper) },
                enabled = paper != null,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) {
                Text(stringResource(R.string.label_ui_open_editor))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (template == null) {
                Text(stringResource(R.string.label_ui_choose_template_hint),
                    style = MaterialTheme.typography.bodyMedium)
                LabelToolTemplate.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { item ->
                            val title = when (item) {
                                LabelToolTemplate.Component -> R.string.label_tool_component
                                LabelToolTemplate.ComponentCompact -> R.string.label_ui_component_compact
                                LabelToolTemplate.ComponentText -> R.string.label_ui_component_text
                                LabelToolTemplate.Text -> R.string.label_tool_text
                                LabelToolTemplate.Qr -> R.string.label_tool_qr
                                LabelToolTemplate.Free -> R.string.label_tool_free
                            }
                            val description = when (item) {
                                LabelToolTemplate.Component -> R.string.label_ui_component_desc
                                LabelToolTemplate.ComponentCompact -> R.string.label_ui_component_compact_desc
                                LabelToolTemplate.ComponentText -> R.string.label_ui_component_text_desc
                                LabelToolTemplate.Text -> R.string.label_ui_text_desc
                                LabelToolTemplate.Qr -> R.string.label_ui_qr_desc
                                LabelToolTemplate.Free -> R.string.label_ui_free_desc
                            }
                            Card(Modifier.weight(1f).clickable { chosen = item.name }) {
                                Column(Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.fillMaxWidth().height(92.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center) {
                                        Box(Modifier.fillMaxWidth(0.78f).height(64.dp)
                                            .background(MaterialTheme.colorScheme.surface),
                                            contentAlignment = Alignment.Center) {
                                            Text(when (item) {
                                                LabelToolTemplate.Component -> "R  0603   ▦"
                                                LabelToolTemplate.ComponentCompact -> "R   ▦"
                                                LabelToolTemplate.ComponentText -> "R  0603"
                                                LabelToolTemplate.Text -> "Aa  123"
                                                LabelToolTemplate.Qr -> "▦"
                                                LabelToolTemplate.Free -> "Aa     ▦"
                                            }, style = MaterialTheme.typography.titleMedium)
                                        }
                                    }
                                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            } else {
                Text(stringResource(R.string.label_ui_choose_paper_hint),
                    style = MaterialTheme.typography.bodyMedium)
                LabelPaperChoices(paper?.widthMm, paper?.heightMm, true) { size ->
                    widthText = size.widthMm.toInt().toString()
                    heightText = size.heightMm.toInt().toString()
                }
                Text(stringResource(R.string.label_ui_custom_size), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(widthText, { widthText = it },
                        label = { Text(stringResource(R.string.bluetooth_label_print_width)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, modifier = Modifier.weight(1f).testTag("label_paper_width"))
                    OutlinedTextField(heightText, { heightText = it },
                        label = { Text(stringResource(R.string.bluetooth_label_print_height)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, modifier = Modifier.weight(1f).testTag("label_paper_height"))
                }
                if (paper == null) Text(stringResource(R.string.label_ui_paper_range),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
