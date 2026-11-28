package com.componentvault.android.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onOpenLocationLabels: (() -> Unit)? = null,
) {
    SettingsRouteScaffold(
        title = stringResource(R.string.appearance_printing),
        caption = stringResource(R.string.appearance_printing_hint),
        onBack = onBack,
    ) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    SettingsSectionCard(
                        title = stringResource(LabelToolTemplate.Component.title),
                        supporting = stringResource(R.string.v2_label_component_hint),
                        icon = R.drawable.v2_tag,
                        selected = false,
                        onClick = { onSelect(LabelToolTemplate.Component) },
                    )
                    if (onOpenLocationLabels != null) {
                        HorizontalDivider(Modifier.padding(start = 51.dp, end = 16.dp))
                        SettingsSectionCard(
                            title = stringResource(R.string.v2_label_location),
                            supporting = stringResource(R.string.v2_label_location_hint),
                            icon = R.drawable.v2_qr_code,
                            selected = false,
                            onClick = onOpenLocationLabels,
                        )
                    }
                    HorizontalDivider(Modifier.padding(start = 51.dp, end = 16.dp))
                    SettingsSectionCard(
                        title = stringResource(R.string.v2_label_bluetooth),
                        supporting = stringResource(R.string.v2_label_bluetooth_hint),
                        icon = R.drawable.v2_printer,
                        selected = false,
                        onClick = { onSelect(LabelToolTemplate.Free) },
                    )
                }
            }
            Text(
                stringResource(R.string.v2_label_other_templates),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    LabelToolTemplate.entries.filter { it != LabelToolTemplate.Component }.forEachIndexed { index, template ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 51.dp, end = 16.dp))
                        SettingsSectionCard(
                            title = stringResource(template.title),
                            supporting = stringResource(template.description),
                            icon = if (template == LabelToolTemplate.Qr) R.drawable.v2_qr_code else R.drawable.v2_tag,
                            selected = false,
                            onClick = { onSelect(template) },
                        )
                    }
                }
            }
        }
    }
}
