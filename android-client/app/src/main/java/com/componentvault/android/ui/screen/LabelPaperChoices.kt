package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.data.LabelPaperSize

@Composable
internal fun LabelPaperChoices(
    widthMm: Float?,
    heightMm: Float?,
    enabled: Boolean,
    isM1: Boolean = true,
    onSelect: (LabelPaperSize) -> Unit,
) {
    Text(stringResource(R.string.label_ui_common_sizes), style = MaterialTheme.typography.titleSmall)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LabelPaperSize.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { size ->
                    FilterChip(
                        selected = widthMm == size.widthMm && heightMm == size.heightMm,
                        onClick = { onSelect(size) },
                        enabled = enabled,
                        label = { Text("${size.widthMm.toInt()} × ${size.heightMm.toInt()}") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    if (isM1) Text(stringResource(R.string.label_ui_m1_size_boundary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
