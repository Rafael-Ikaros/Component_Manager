package com.componentvault.android.data

internal enum class LabelPrinterProfile(val id: String, val widthRange: ClosedFloatingPointRange<Float>, val heightRange: ClosedFloatingPointRange<Float>) {
    M1("m1", 20f..48f, 10f..100f),
    HmA300eCpcl("hm-a300e-cpcl", 25f..72f, 10f..300f),
    GenericCpcl("cpcl-spp", 20f..72f, 10f..300f),
    DetongerP1("detonger-p1", 10f..48f, 10f..300f);

    val usesCpcl get() = this == HmA300eCpcl || this == GenericCpcl
    val supportsContinuous get() = this != M1
    val configurable get() = if (this == HmA300eCpcl) GenericCpcl else this

    fun validate(paper: M1TestPaperProfile) {
        require(paper.widthMm in widthRange && paper.heightMm in heightRange) { "Paper exceeds printer profile" }
        require(kotlin.math.abs(paper.offsetXmm) <= widthRange.endInclusive && kotlin.math.abs(paper.offsetYmm) <= heightRange.endInclusive)
    }

    companion object {
        private val m1BluetoothName = Regex("(?:HPRT +)?M1(?:-[0-9A-F]{4})?", RegexOption.IGNORE_CASE)
        private val hmA300BluetoothName = Regex("(?:HPRT +)?HM-A300E?(?:-[0-9A-F]{4})?", RegexOption.IGNORE_CASE)

        fun defaultForDeviceName(name: String?): LabelPrinterProfile? {
            val bluetoothName = name?.trim() ?: return null
            return when {
                m1BluetoothName.matches(bluetoothName) -> M1
                hmA300BluetoothName.matches(bluetoothName) -> GenericCpcl
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
