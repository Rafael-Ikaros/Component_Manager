package com.componentvault.android.data

import com.componentvault.android.BuildConfig

internal enum class LabelPrinterProfile(val id: String, val widthRange: ClosedFloatingPointRange<Float>, val heightRange: ClosedFloatingPointRange<Float>) {
    M1("m1", 20f..48f, 10f..100f),
    HmA300eCpcl("hm-a300e-cpcl", 25f..72f, 10f..300f),
    GenericCpcl("cpcl-spp", 20f..72f, 10f..300f),
    DetongerP1("detonger-p1", 10f..48f, 10f..300f),
    SupvanT50Pro("supvan-t50-pro", 20f..50f, 10f..300f);

    val usesCpcl get() = this == HmA300eCpcl || this == GenericCpcl
    val supportsContinuous get() = usesCpcl || this == DetongerP1
    val requiresPhysicalReview get() = usesCpcl || this == SupvanT50Pro
    val configurable get() = if (this == HmA300eCpcl) GenericCpcl else this

    fun validate(paper: M1TestPaperProfile) {
        require(paper.widthMm in widthRange && paper.heightMm in heightRange) { "Paper exceeds printer profile" }
        require(kotlin.math.abs(paper.offsetXmm) <= widthRange.endInclusive && kotlin.math.abs(paper.offsetYmm) <= heightRange.endInclusive)
    }

    companion object {
        val selectable = entries.filter { it.configurable == it && (it != SupvanT50Pro || BuildConfig.SUPVAN_T50_PRO) }
        private val m1BluetoothName = Regex("(?:HPRT +)?M1(?:-[0-9A-F]{4})?", RegexOption.IGNORE_CASE)
        private val hmA300BluetoothName = Regex("(?:HPRT +)?HM-A300E?(?:-[0-9A-F]{4})?", RegexOption.IGNORE_CASE)
        private val supvanT50ProName = Regex("(?:SUPVAN[ -]+|硕方[ -]*)?T50[ -]*PRO(?:[-_ ].*)?", RegexOption.IGNORE_CASE)
        // Bluetooth firmware serial prefixes for T50 Pro in the supvan-cups model registry.
        private val supvanT50ProPrefixes = setOf(
            "T0046A", "T0100A", "T0112A", "T0113A", "T0145B", "T0149B", "T0150B", "T0151B",
            "T0152B", "T0159B", "T0160B", "T0200", "T0201", "T0202",
        )

        fun defaultForDeviceName(name: String?): LabelPrinterProfile? {
            val bluetoothName = name?.trim() ?: return null
            return when {
                m1BluetoothName.matches(bluetoothName) -> M1
                hmA300BluetoothName.matches(bluetoothName) -> GenericCpcl
                BuildConfig.SUPVAN_T50_PRO && (supvanT50ProName.matches(bluetoothName) ||
                    supvanT50ProPrefixes.any { bluetoothName.startsWith(it, ignoreCase = true) }) -> SupvanT50Pro
                else -> null
            }
        }

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
