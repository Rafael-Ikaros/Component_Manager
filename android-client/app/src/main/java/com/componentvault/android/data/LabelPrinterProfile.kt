package com.componentvault.android.data

internal enum class LabelPrinterProfile(val id: String, val widthRange: ClosedFloatingPointRange<Float>, val heightRange: ClosedFloatingPointRange<Float>) {
    M1("m1", 20f..48f, 10f..100f),
    HmA300eCpcl("hm-a300e-cpcl", 25f..72f, 10f..300f),
    DetongerP1("detonger-p1", 10f..48f, 10f..300f);

    val displayName: String get() = when (this) {
        M1 -> "M1"
        HmA300eCpcl -> "HM-A300E CPCL"
        DetongerP1 -> "德佟 P1"
    }

    fun validate(paper: M1TestPaperProfile) {
        require(paper.widthMm in widthRange && paper.heightMm in heightRange) { "Paper exceeds printer profile" }
        require(kotlin.math.abs(paper.offsetXmm) <= widthRange.endInclusive && kotlin.math.abs(paper.offsetYmm) <= heightRange.endInclusive)
    }

    companion object {
        fun fromId(id: String): LabelPrinterProfile = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unsupported printer profile: $id")
    }
}

internal enum class LabelMediaType(val id: String) {
    Gap("gap"), Continuous("continuous");
    companion object {
        fun fromId(id: String): LabelMediaType = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unsupported media type: $id")
    }
}
