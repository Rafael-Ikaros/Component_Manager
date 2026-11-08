package com.componentvault.android.data

import com.componentvault.android.model.ComponentLabelSeed
import java.util.UUID

internal enum class LabelPrintItemState { Pending, Sending, Sent, Uncertain, Failed, Skipped }

internal enum class FreeLabelTemplate { Text, Qr, Free }

internal data class FreeLabel(val title: String, val template: FreeLabelTemplate)

internal data class LabelPrintItem(
    val id: String,
    val seed: ComponentLabelSeed? = null,
    val copyNumber: Int,
    val copies: Int,
    val state: LabelPrintItemState = LabelPrintItemState.Pending,
    val detail: String = "",
    val design: LabelDesign? = null,
    val freeLabel: FreeLabel? = null,
) {
    init {
        require((seed == null) != (freeLabel == null)) { "Print item needs exactly one source" }
        if (freeLabel != null) require(design != null) { "Free label needs a design" }
    }
}

internal data class LabelPrintQueue(
    val items: List<LabelPrintItem> = emptyList(),
    val templateId: String = ComponentLabelTemplate.default.id,
    val textTemplateId: String = ComponentTextLabelTemplate.default.id,
    val paper: M1TestPaperProfile = M1TestPaperProfile(40f, 60f),
    val printerProfileId: String = LabelPrinterProfile.M1.id,
    val mediaType: String = LabelMediaType.Gap.id,
) {
    init { LabelPrinterProfile.fromId(printerProfileId).validate(paper); LabelMediaType.fromId(mediaType) }

    /** A send interrupted by process death may already have reached the printer. */
    fun recovered(): LabelPrintQueue = copy(items = items.map { item ->
        if (item.state == LabelPrintItemState.Sending) {
            item.copy(state = LabelPrintItemState.Uncertain)
        } else {
            item
        }
    })

    fun update(id: String, state: LabelPrintItemState, detail: String = ""): LabelPrintQueue {
        require(items.any { it.id == id }) { "Print item does not exist: $id" }
        return copy(items = items.map { item ->
            if (item.id == id) item.copy(state = state, detail = detail) else item
        })
    }

    companion object {
        const val MaxItems = 500
        const val MaxCopies = 99

        fun create(
            selections: List<Pair<ComponentLabelSeed, Int>>,
            templateId: String = ComponentLabelTemplate.default.id,
            textTemplateId: String = ComponentTextLabelTemplate.default.id,
            paper: M1TestPaperProfile = M1TestPaperProfile(40f, 60f),
            designs: Map<String, LabelDesign> = emptyMap(),
            printerProfileId: String = LabelPrinterProfile.M1.id,
            mediaType: String = LabelMediaType.Gap.id,
        ): LabelPrintQueue {
            require(selections.isNotEmpty()) { "Select at least one component to print" }
            var total = 0
            val items = selections.flatMap { (seed, copies) ->
                require(copies in 1..MaxCopies) { "Copies must be between 1 and $MaxCopies" }
                total += copies
                require(total <= MaxItems) { "Print job may contain at most $MaxItems labels" }
                (1..copies).map { copyNumber ->
                    val design = designs[seed.sku]
                    design?.validate(paper)
                    LabelPrintItem(UUID.randomUUID().toString(), seed, copyNumber, copies,
                        design = design?.copy(elements = design.elements.toList()))
                }
            }
            return LabelPrintQueue(items, templateId, textTemplateId, paper, printerProfileId, mediaType)
        }

        fun createFree(
            freeLabel: FreeLabel,
            copies: Int,
            paper: M1TestPaperProfile,
            design: LabelDesign,
            printerProfileId: String = LabelPrinterProfile.M1.id,
            mediaType: String = LabelMediaType.Gap.id,
        ): LabelPrintQueue {
            require(copies in 1..MaxCopies) { "Copies must be between 1 and $MaxCopies" }
            require(freeLabel.title.isNotBlank()) { "Label title is required" }
            design.validate(paper)
            require(design.elements.any { it.type == LabelElementType.Text && it.text.isNotBlank() ||
                it.type == LabelElementType.Qr && it.text.isNotBlank() }) {
                "标签没有可打印内容。"
            }
            return LabelPrintQueue(
                items = (1..copies).map { copyNumber ->
                    LabelPrintItem(UUID.randomUUID().toString(), null, copyNumber, copies,
                        design = design.copy(elements = design.elements.toList()), freeLabel = freeLabel)
                },
                paper = paper,
                printerProfileId = printerProfileId,
                mediaType = mediaType,
            )
        }
    }
}
