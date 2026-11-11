package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.data.LabelMediaType
import com.componentvault.android.data.LabelPaperPresetStore
import com.componentvault.android.data.LabelPaperSize
import com.componentvault.android.data.LabelPrinterProfile
import com.componentvault.android.data.M1TestPaperProfile
import com.componentvault.android.data.SavedLabelPaper

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LabelPaperPickerSheet(
    paper: M1TestPaperProfile,
    media: LabelMediaType,
    profile: LabelPrinterProfile,
    onApply: (M1TestPaperProfile, LabelMediaType) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { LabelPaperPresetStore(context) }
    var savedPapers by remember(store) { mutableStateOf(store.load()) }
    var widthText by rememberSaveable { mutableStateOf(paper.widthMm.paperNumber()) }
    var heightText by rememberSaveable { mutableStateOf(paper.heightMm.paperNumber()) }
    var mediaType by rememberSaveable { mutableStateOf(if (profile.usesCpcl) media else LabelMediaType.Gap) }
    var rotation by rememberSaveable { mutableStateOf(paper.rotationDegrees) }
    var horizontalText by rememberSaveable { mutableStateOf(paper.offsetXmm.paperNumber()) }
    var verticalText by rememberSaveable { mutableStateOf(paper.offsetYmm.paperNumber()) }
    var showAlignment by rememberSaveable { mutableStateOf(false) }
    var showSave by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf("") }
    val width = widthText.parsePaperNumber()
    val height = heightText.parsePaperNumber()
    val sizeIsValid = width != null && height != null && width in profile.widthRange && height in profile.heightRange
    val draft = remember(width, height, rotation, horizontalText, verticalText, profile) {
        runCatching {
            M1TestPaperProfile(
                requireNotNull(width), requireNotNull(height), rotation,
                requireNotNull(horizontalText.parsePaperNumber()), requireNotNull(verticalText.parsePaperNumber()),
            ).also(profile::validate)
        }.getOrNull()
    }
    fun applySize(widthMm: Float, heightMm: Float) {
        widthText = widthMm.paperNumber()
        heightText = heightMm.paperNumber()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.label_ui_paper), style = MaterialTheme.typography.titleLarge)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val compatibleSaved = savedPapers.filter {
                    it.widthMm in profile.widthRange && it.heightMm in profile.heightRange &&
                        (profile.usesCpcl || it.media == LabelMediaType.Gap)
                }
                if (compatibleSaved.isNotEmpty()) {
                    Text(stringResource(R.string.label_paper_saved), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        compatibleSaved.forEach { saved ->
                            val label = saved.name.ifBlank {
                                stringResource(R.string.label_paper_dimensions, saved.widthMm.paperNumber(), saved.heightMm.paperNumber())
                            }
                            FilterChip(
                                selected = saved.matches(width, height, mediaType),
                                onClick = {
                                    applySize(saved.widthMm, saved.heightMm)
                                    mediaType = saved.media
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    val selectedSaved = compatibleSaved.firstOrNull { it.matches(width, height, mediaType) }
                    if (selectedSaved != null) TextButton(onClick = {
                        savedPapers = savedPapers - selectedSaved
                        store.save(savedPapers)
                    }) { Text(stringResource(R.string.label_paper_remove_saved)) }
                }
                Text(stringResource(R.string.label_ui_common_sizes), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelPaperSize.entries.filter { it.widthMm in profile.widthRange && it.heightMm in profile.heightRange }
                        .forEach { size ->
                            FilterChip(
                                selected = width == size.widthMm && height == size.heightMm,
                                onClick = { applySize(size.widthMm, size.heightMm) },
                                label = { Text("${size.widthMm.paperNumber()} × ${size.heightMm.paperNumber()}") },
                            )
                        }
                }
                Text(stringResource(R.string.label_ui_custom_size), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperField(widthText, { widthText = it }, stringResource(R.string.bluetooth_label_print_width),
                        Modifier.weight(1f).testTag("label_paper_width"))
                    PaperField(heightText, { heightText = it }, stringResource(R.string.bluetooth_label_print_height),
                        Modifier.weight(1f).testTag("label_paper_height"))
                }
                if (!sizeIsValid) Text(
                    stringResource(R.string.label_paper_limits, profile.widthRange.start.paperNumber(),
                        profile.widthRange.endInclusive.paperNumber(), profile.heightRange.start.paperNumber(),
                        profile.heightRange.endInclusive.paperNumber()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (profile.usesCpcl) {
                    Text(stringResource(R.string.label_paper_media), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabelMediaType.entries.forEach { option ->
                            FilterChip(
                                selected = option == mediaType, onClick = { mediaType = option },
                                label = { Text(stringResource(if (option == LabelMediaType.Gap) R.string.label_media_gap
                                    else R.string.label_media_continuous)) },
                            )
                        }
                    }
                }
                TextButton(onClick = { showAlignment = !showAlignment }) { Text(stringResource(R.string.label_paper_alignment)) }
                if (showAlignment) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        M1TestPaperProfile.Rotations.forEach { degrees ->
                            FilterChip(rotation == degrees, { rotation = degrees }, { Text("$degrees°") })
                        }
                    }
                    PaperField(horizontalText, { horizontalText = it }, stringResource(R.string.bluetooth_label_print_offset_x),
                        Modifier.fillMaxWidth().testTag("label_paper_offset_x"), signed = true)
                    PaperField(verticalText, { verticalText = it }, stringResource(R.string.bluetooth_label_print_offset_y),
                        Modifier.fillMaxWidth().testTag("label_paper_offset_y"), signed = true)
                    Text(stringResource(R.string.label_paper_alignment_hint), style = MaterialTheme.typography.bodySmall)
                    if (sizeIsValid && draft == null) Text(
                        stringResource(R.string.label_paper_alignment_invalid), color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(
                    onClick = {
                        presetName = savedPapers.firstOrNull { it.matches(width, height, mediaType) }?.name.orEmpty()
                        showSave = true
                    },
                    enabled = sizeIsValid,
                ) { Text(stringResource(R.string.label_paper_save_size)) }
            }
            Button(
                onClick = { draft?.let { onApply(it, mediaType) } },
                enabled = draft != null,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("label_paper_apply"),
            ) { Text(stringResource(R.string.label_paper_apply)) }
        }
    }
    if (showSave && sizeIsValid) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text(stringResource(R.string.label_paper_save_size)) },
            text = {
                OutlinedTextField(
                    presetName, { presetName = it.take(60) }, singleLine = true,
                    label = { Text(stringResource(R.string.label_paper_name)) },
                    modifier = Modifier.fillMaxWidth().testTag("label_paper_name"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val preset = SavedLabelPaper(presetName.trim(), width, height, mediaType)
                    savedPapers = listOf(preset) + savedPapers.filterNot { it.matches(width, height, mediaType) }
                    store.save(savedPapers)
                    showSave = false
                }) { Text(stringResource(R.string.label_paper_save)) }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text(stringResource(R.string.bluetooth_label_print_cancel)) } },
        )
    }
}

@Composable
private fun PaperField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    signed: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) },
        singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else KeyboardType.Decimal),
    )
}

private fun String.parsePaperNumber() = trim().replace(',', '.').replace('−', '-').toFloatOrNull()
private fun Float.paperNumber() = if (toInt().toFloat() == this) toInt().toString() else toString()
