package com.componentvault.android.data

import com.componentvault.android.model.ComponentOfficialMetadata
import com.componentvault.android.model.officialParameterKind
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

internal enum class ReelCatalogField(val parameterKind: String? = null) {
    Brand, Package, Resistance("resistance"), Capacitance("capacitance"), Inductance("inductance"),
    Voltage("voltage"), Tolerance("tolerance"),
}

internal data class ReelCatalogConflict(val field: ReelCatalogField, val label: String, val catalog: String)

internal data class ReelCatalogReview(
    val matchedFields: Int,
    val unverifiedFields: Int,
    val conflicts: List<ReelCatalogConflict>,
    val hasComparableElectricalParameter: Boolean,
)

internal fun reviewReelCatalog(label: ReelLabel, metadata: ComponentOfficialMetadata): ReelCatalogReview {
    var matchedFields = 0
    var unverifiedFields = 0
    var hasComparableElectricalParameter = false
    val conflicts = mutableListOf<ReelCatalogConflict>()
    fun record(field: ReelCatalogField, labelValue: String, catalogValue: String?, matches: Boolean?) {
        if (field.parameterKind != null && matches != null) hasComparableElectricalParameter = true
        when (matches) {
            true -> matchedFields++
            false -> conflicts.add(ReelCatalogConflict(field, labelValue, requireNotNull(catalogValue)))
            null -> unverifiedFields++
        }
    }

    label.brand?.takeIf(String::isNotBlank)?.let { brand ->
        val officialBrand = metadata.brand?.takeIf(String::isNotBlank) ?: metadata.vendor
        val key = brandKey(brand)
        val officialKey = officialBrand?.let(::brandKey).orEmpty()
        val matches = if (key.isEmpty() || officialKey.isEmpty()) null else key == officialKey
        record(ReelCatalogField.Brand, brand, officialBrand, matches)
    }
    if (label.packageName.isNotBlank()) {
        val officialPackage = metadata.packageName
        record(ReelCatalogField.Package, label.packageName, officialPackage, packageMatches(label.packageName, officialPackage))
    }

    val officialParameters = reelCatalogParameters(metadata.parameters).entries.groupBy { reelParameterKind(it.key) }
    val labelParameters = label.parameters.entries.groupBy { reelParameterKind(it.key) }
    for (field in ReelCatalogField.entries) {
        val kind = field.parameterKind ?: continue
        val printed = labelParameters[kind].orEmpty().takeIf { it.isNotEmpty() } ?: continue
        val official = officialParameters[kind].orEmpty()
        val fromLabel = singleValue(kind, printed)
        val fromCatalog = singleValue(kind, official)
        val matches = if (fromLabel == null || fromCatalog == null) null else {
            fromLabel.amount == fromCatalog.amount &&
                (fromLabel.qualifier == null || fromCatalog.qualifier == null || fromLabel.qualifier == fromCatalog.qualifier)
        }
        record(field, printed.first().value, official.firstOrNull()?.value, matches)
    }
    return ReelCatalogReview(matchedFields, unverifiedFields, conflicts, hasComparableElectricalParameter)
}

/** Some catalog entries use a generic Value field. Its unit must identify the quantity. */
internal fun reelCatalogParameters(parameters: Map<String, String>): Map<String, String> {
    val value = parameters.entries.singleOrNull {
        compact(Normalizer.normalize(it.key, Normalizer.Form.NFKC).substringBefore('(')) == "value"
    } ?: return parameters
    val parameter = listOf(
        "Resistance" to "resistance", "Capacitance" to "capacitance", "Inductance" to "inductance",
    ).singleOrNull { (_, kind) -> singleValue(kind, listOf(value)) != null } ?: return parameters
    if (parameters.keys.any { reelParameterKind(it) == parameter.second }) return parameters
    return parameters - value.key + (parameter.first to value.value.trim().withHeaderUnit(value.key))
}

internal fun reelParameterKind(key: String): String? = officialParameterKind(key) ?: when (compact(key)) {
    "capacitancetolerance", "电容量允许公差" -> "tolerance"
    "voltagerated", "ratedvoltage" -> "voltage"
    else -> null
}

private fun compact(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

private fun brandKey(value: String): String = when (val key = compact(Normalizer.normalize(value, Normalizer.Form.NFKC).substringBefore('('))) {
    "国巨" -> "yageo"
    "三星", "三星电机", "samsungelectromechanics" -> "samsung"
    "罗姆", "rohmsemiconductor" -> "rohm"
    else -> key
}

private val passiveSizes = mapOf(
    "0201" to "0603", "0402" to "1005", "0603" to "1608", "0805" to "2012",
    "1206" to "3216", "1210" to "3225", "1812" to "4532", "2010" to "5025", "2512" to "6432",
)
private val printedPackageSize = Regex("""(?i)(?:SMD\s*[,\-]?\s*)?(\d{4})(?:\s*\(\s*(\d{4})(?:\s*metric)?\s*\))?""")
private val metricPackageSize = Regex("""(?i)(\d{4})\s*metric""")

private fun packageMatches(printed: String, catalog: String?): Boolean? {
    if (catalog.isNullOrBlank()) return null
    val fromLabel = packageSize(printed)
    val fromCatalog = packageSize(catalog)
    if (fromLabel != null && fromCatalog != null) return fromLabel == fromCatalog
    return true.takeIf { printed.trim().equals(catalog.trim(), ignoreCase = true) }
}

private fun packageSize(raw: String): String? {
    val text = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
    metricPackageSize.matchEntire(text)?.groupValues?.get(1)?.let { metric ->
        return passiveSizes.entries.firstOrNull { it.value == metric }?.key
    }
    val match = printedPackageSize.matchEntire(text) ?: return null
    val imperial = match.groupValues[1]
    return imperial.takeIf {
        val metric = passiveSizes[it] ?: return null
        match.groupValues[2].isEmpty() || match.groupValues[2] == metric
    }
}

private data class ComparableParameter(val amount: BigDecimal, val qualifier: String?)
private val resistorMarking = Regex("""(\d+)([RrKkMm])(\d*)""")
private val voltageSuffix = Regex("""(?i)(?<=V)(DC|AC)$""")

private fun singleValue(kind: String, entries: List<Map.Entry<String, String>>): ComparableParameter? {
    val values = entries.map { entry ->
        val raw = Normalizer.normalize(entry.value.trim(), Normalizer.Form.NFKC).withHeaderUnit(entry.key)
        val normalized = raw.filterNot(Char::isWhitespace)
        val voltage = voltageSuffix.find(normalized).takeIf { kind == "voltage" }
        val value = when {
            voltage != null -> normalized.substring(0, voltage.range.first)
            kind == "resistance" -> resistorMarking.matchEntire(normalized)?.destructured?.let { (whole, unit, fraction) ->
                val prefix = when (unit) {
                    "r", "R" -> ""
                    "K" -> "k"
                    else -> unit
                }
                whole + fraction.takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty() + prefix + "Ω"
            } ?: normalized
            else -> normalized
        }
        val amount = normalizeElectricalParameter(kind, value) ?: return null
        ComparableParameter(amount, voltage?.value?.lowercase(Locale.ROOT))
    }.distinct()
    return values.singleOrNull()
}
