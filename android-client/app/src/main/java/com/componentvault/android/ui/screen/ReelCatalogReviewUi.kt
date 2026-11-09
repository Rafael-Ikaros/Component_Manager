package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.data.ReelCatalogConflict
import com.componentvault.android.data.ReelCatalogField
import com.componentvault.android.data.ReelCatalogReview

@Composable
internal fun ReelCatalogReviewSummary(review: ReelCatalogReview) {
    if (review.conflicts.isNotEmpty()) {
        Text(
            stringResource(R.string.import_reel_conflict_count, review.conflicts.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (review.matchedFields > 0) {
        Text(
            stringResource(R.string.import_reel_matched_fields, review.matchedFields),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (review.matchedFields == 0 && review.conflicts.isEmpty()) {
        Text(stringResource(R.string.import_reel_unverified), style = MaterialTheme.typography.bodySmall)
        return
    }
    if (review.unverifiedFields > 0) {
        Text(
            stringResource(R.string.import_reel_unverified_count, review.unverifiedFields),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (!review.hasComparableElectricalParameter) {
        Text(stringResource(R.string.import_reel_parameters_unverified), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ReelCatalogConflictDialog(
    partNumber: String,
    conflicts: List<ReelCatalogConflict>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_reel_conflict_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(partNumber, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.import_reel_conflict_hint))
                conflicts.forEach { conflict ->
                    ValueBlock(
                        label = stringResource(conflict.field.labelResource),
                        value = stringResource(R.string.import_reel_conflict_values, conflict.label, conflict.catalog),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.import_reel_confirm_conflict)) }
        },
    )
}

private val ReelCatalogField.labelResource: Int get() = when (this) {
    ReelCatalogField.Brand -> R.string.importer_brand_label
    ReelCatalogField.Package -> R.string.field_package
    ReelCatalogField.Resistance -> R.string.import_reel_parameter_resistance
    ReelCatalogField.Capacitance -> R.string.import_reel_parameter_capacitance
    ReelCatalogField.Inductance -> R.string.import_reel_parameter_inductance
    ReelCatalogField.Voltage -> R.string.import_reel_parameter_voltage
    ReelCatalogField.Tolerance -> R.string.import_reel_parameter_tolerance
}
