package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    val currentMedia = if (profile.supportsContinuous) media else LabelMediaType.Gap
    var custom by rememberSaveable { mutableStateOf(false) }
    var widthText by rememberSaveable { mutableStateOf(paper.widthMm.paperNumber()) }
    var heightText by rememberSaveable { mutableStateOf(paper.heightMm.paperNumber()) }
    var mediaType by rememberSaveable { mutableStateOf(currentMedia) }
    var rotation by rememberSaveable { mutableStateOf(paper.rotationDegrees) }
    var offsetX by rememberSaveable { mutableStateOf<String?>(null) }
    var offsetY by rememberSaveable { mutableStateOf<String?>(null) }
    var presetName by rememberSaveable {
        mutableStateOf(savedPapers.firstOrNull { it.matches(paper.widthMm, paper.heightMm, currentMedia) }?.name.orEmpty())
    }
    val width = widthText.parsePaperNumber()
    val height = heightText.parsePaperNumber()
    val sizeIsValid = width != null && height != null && width in M1TestPaperProfile.WidthRange && height in M1TestPaperProfile.HeightRange
    val draft = remember(paper, width, height, rotation, offsetX, offsetY) {
        if (width == null || height == null) null else runCatching {
            paper.copy(widthMm = width, heightMm = height, rotationDegrees = rotation,
                offsetXmm = offsetX?.replace(',', '.')?.toFloat() ?: paper.offsetXmm,
                offsetYmm = offsetY?.replace(',', '.')?.toFloat() ?: paper.offsetYmm)
        }.getOrNull()
    }
    val compatibleSaved = savedPapers.filter {
        profile.supportsContinuous || it.media == LabelMediaType.Gap
    }
    val selectedSaved = compatibleSaved.firstOrNull {
        it.name == presetName && it.resolvePaper(paper.offsetXmm, paper.offsetYmm) == draft && it.media == mediaType
    }
    val canSave = draft != null && (savedPapers.size < LabelPaperPresetStore.MaxPresets ||
        savedPapers.any { it.name == presetName.trim() })

    fun selectSize(widthMm: Float, heightMm: Float, type: LabelMediaType, name: String = "") {
        widthText = widthMm.paperNumber()
        heightText = heightMm.paperNumber()
        mediaType = type
        presetName = name
    }

    val scrollState = if (custom) rememberScrollState() else rememberScrollState()
    SecondaryPageScaffold(
        title = stringResource(R.string.label_paper_picker_title),
        onBack = onDismiss,
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { draft?.let { onApply(it, mediaType) } },
                        enabled = draft != null,
                        modifier = Modifier.weight(1f).testTag("label_paper_apply"),
                    ) { Text(stringResource(R.string.label_paper_use_once)) }
                    Button(
                        onClick = {
                            draft?.let {
                                val preset = SavedLabelPaper(
                                    presetName.trim(), it.widthMm, it.heightMm, mediaType, it.rotationDegrees,
                                    it.offsetXmm.takeIf { offsetX != null },
                                    it.offsetYmm.takeIf { offsetY != null },
                                )
                                store.save(listOf(preset) + savedPapers.filterNot { saved ->
                                    saved.name == preset.name
                                })
                                onApply(it, mediaType)
                            }
                        },
                        enabled = canSave,
                        modifier = Modifier.weight(1f).testTag("label_paper_save_apply"),
                    ) { Text(stringResource(R.string.label_paper_save_apply)) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(horizontal = 16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                SegmentedButton(
                    selected = !custom,
                    onClick = { custom = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.label_paper_common_tab)) }
                SegmentedButton(
                    selected = custom,
                    onClick = { custom = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.testTag("label_paper_custom_tab"),
                ) { Text(stringResource(R.string.label_paper_custom_tab)) }
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(scrollState).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.label_paper_presets_hint), style = MaterialTheme.typography.bodySmall)
                if (draft != null) Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(paperDimensions(draft.widthMm, draft.heightMm), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.label_paper_dimension_order), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (custom) {
                    Text(stringResource(R.string.label_ui_custom_size), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaperField(widthText, { widthText = it }, stringResource(R.string.bluetooth_label_print_width),
                            Modifier.weight(1f).testTag("label_paper_width"))
                        PaperField(heightText, { heightText = it }, stringResource(R.string.bluetooth_label_print_height),
                            Modifier.weight(1f).testTag("label_paper_height"))
                    }
                } else {
                    if (paper.widthMm in profile.widthRange && paper.heightMm in profile.heightRange) {
                        Text(stringResource(R.string.label_paper_current), style = MaterialTheme.typography.titleSmall)
                        FilterChip(
                            selected = width == paper.widthMm && height == paper.heightMm && mediaType == currentMedia,
                            onClick = {
                                val current = compatibleSaved.firstOrNull { it.matches(paper.widthMm, paper.heightMm, currentMedia) }
                                selectSize(paper.widthMm, paper.heightMm, currentMedia, current?.name.orEmpty())
                                rotation = paper.rotationDegrees
                                offsetX = null
                                offsetY = null
                            },
                            label = { Text(paperDimensions(paper.widthMm, paper.heightMm)) },
                        )
                    }
                    if (compatibleSaved.isNotEmpty()) {
                        Text(stringResource(R.string.label_paper_saved), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            compatibleSaved.forEach { saved ->
                                FilterChip(
                                    selected = saved == selectedSaved,
                                    onClick = {
                                        selectSize(saved.widthMm, saved.heightMm, saved.media, saved.name)
                                        rotation = saved.rotationDegrees
                                        offsetX = saved.offsetXmm?.toString()
                                        offsetY = saved.offsetYmm?.toString()
                                    },
                                    label = { Text(saved.name.ifBlank { paperDimensions(saved.widthMm, saved.heightMm) }) },
                                )
                            }
                        }
                        if (selectedSaved != null) TextButton(onClick = {
                            savedPapers = savedPapers - selectedSaved
                            store.save(savedPapers)
                            presetName = ""
                        }) { Text(stringResource(R.string.label_paper_remove_saved)) }
                    }
                    Text(stringResource(R.string.label_ui_common_sizes), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabelPaperSize.entries.filter { it.widthMm in profile.widthRange && it.heightMm in profile.heightRange }
                            .forEach { size ->
                                FilterChip(
                                    selected = width == size.widthMm && height == size.heightMm,
                                    onClick = {
                                        val saved = compatibleSaved.firstOrNull { it.matches(size.widthMm, size.heightMm, mediaType) }
                                        selectSize(size.widthMm, size.heightMm, mediaType, saved?.name.orEmpty())
                                    },
                                    label = { Text("${size.widthMm.paperNumber()} × ${size.heightMm.paperNumber()}") },
                                )
                            }
                    }
                }
                if (custom || !sizeIsValid) Text(
                    stringResource(R.string.label_editor_paper_range),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sizeIsValid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                if (sizeIsValid && draft == null) Text(
                    stringResource(R.string.label_paper_alignment_invalid),
                    color = MaterialTheme.colorScheme.error,
                )
                Text(stringResource(R.string.label_paper_media), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val options = if (profile.supportsContinuous) LabelMediaType.entries else listOf(LabelMediaType.Gap)
                    options.forEach { option ->
                        FilterChip(
                            selected = option == mediaType,
                            onClick = {
                                mediaType = option
                                presetName = compatibleSaved.firstOrNull { it.matches(width, height, option) }?.name.orEmpty()
                            },
                            label = { Text(stringResource(if (option == LabelMediaType.Gap) R.string.label_media_gap
                                else R.string.label_media_continuous)) },
                        )
                    }
                }
                Text(stringResource(R.string.label_paper_rotation), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    M1TestPaperProfile.Rotations.forEach { degrees ->
                        FilterChip(rotation == degrees, { rotation = degrees }, { Text("$degrees°") })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperField(
                        offsetX ?: paper.offsetXmm.toString(), { offsetX = it },
                        stringResource(R.string.bluetooth_label_print_offset_x),
                        Modifier.weight(1f).testTag("label_paper_offset_x"),
                    )
                    PaperField(
                        offsetY ?: paper.offsetYmm.toString(), { offsetY = it },
                        stringResource(R.string.bluetooth_label_print_offset_y),
                        Modifier.weight(1f).testTag("label_paper_offset_y"),
                    )
                }
                if (custom) OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it.take(60) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_paper_name)) },
                    modifier = Modifier.fillMaxWidth().testTag("label_paper_name"),
                )
            }
        }
    }
}

@Composable
private fun PaperField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun paperDimensions(widthMm: Float, heightMm: Float) =
    stringResource(R.string.label_paper_dimensions, widthMm.paperNumber(), heightMm.paperNumber())

private fun String.parsePaperNumber() = trim().replace(',', '.').toFloatOrNull()
private fun Float.paperNumber() = if (toInt().toFloat() == this) toInt().toString() else toString()
