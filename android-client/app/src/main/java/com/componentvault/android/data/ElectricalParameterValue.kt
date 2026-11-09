package com.componentvault.android.data

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

internal fun String.withHeaderUnit(header: String): String {
    if (!matches(Regex("\\d+(?:\\.\\d+)?"))) return this
    val unit = Regex("[（(]([^（）()]*)[）)]").find(header)?.groupValues?.get(1)?.trim()
    return if (unit.isNullOrBlank()) this else this + unit
}

internal fun normalizeElectricalParameter(key: String, raw: String?): BigDecimal? {
    if (raw.isNullOrBlank()) return null
    val text = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        .replace('μ', 'u').replace('µ', 'u').replace("±", "")
        .replace("Ω", "ohm").replace(" ", "")
    val match = Regex("(?i)^(\\d+(?:\\.\\d+)?|\\d+/\\d+)([a-z%/°]+)$").matchEntire(text) ?: return null
    val amount = match.groupValues[1].split('/').let { parts ->
        if (parts.size == 1) parts[0].toBigDecimalOrNull()
        else runCatching { parts[0].toBigDecimalOrNull()?.divide(parts[1].toBigDecimalOrNull() ?: return null) }.getOrNull()
    } ?: return null
    val unit = match.groupValues[2]
    val factor = when (key) {
        "resistance" -> when (unit) {
            "ohm", "ohms", "OHM", "OHMS" -> BigDecimal.ONE
            "kohm", "kohms", "Kohm", "Kohms" -> BigDecimal("1000")
            "mohm", "mohms" -> BigDecimal("0.001")
            "Mohm", "Mohms" -> BigDecimal("1000000")
            else -> null
        }
        "capacitance" -> when (unit.lowercase(Locale.ROOT)) {
            "f" -> BigDecimal.ONE
            "mf" -> BigDecimal("0.001")
            "uf" -> BigDecimal("0.000001")
            "nf" -> BigDecimal("0.000000001")
            "pf" -> BigDecimal("0.000000000001")
            else -> null
        }
        "inductance" -> when (unit.lowercase(Locale.ROOT)) {
            "h" -> BigDecimal.ONE
            "mh" -> BigDecimal("0.001")
            "uh" -> BigDecimal("0.000001")
            "nh" -> BigDecimal("0.000000001")
            else -> null
        }
        "tolerance" -> if (unit == "%") BigDecimal("0.01") else null
        "voltage" -> when (unit.lowercase(Locale.ROOT)) {
            "v" -> BigDecimal.ONE
            "mv" -> BigDecimal("0.001")
            "kv" -> BigDecimal("1000")
            else -> null
        }
        "power" -> when (unit.lowercase(Locale.ROOT)) {
            "w" -> BigDecimal.ONE
            "mw" -> BigDecimal("0.001")
            else -> null
        }
        "temperature_coefficient" -> if (unit.lowercase(Locale.ROOT) in setOf("ppm/c", "ppm/°c", "ppm/k")) BigDecimal.ONE else null
        "current" -> when (unit.lowercase(Locale.ROOT)) {
            "a" -> BigDecimal.ONE
            "ma" -> BigDecimal("0.001")
            "ua" -> BigDecimal("0.000001")
            else -> null
        }
        else -> null
    } ?: return null
    return amount.multiply(factor).stripTrailingZeros()
}
