package com.componentvault.android.data

import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentImportSourceType
import java.util.Locale

internal data class ReelLabel(
    val models: List<String>,
    val automaticModel: String?,
    val brand: String?,
    val nominalQuantity: Int?,
    val packageName: String,
    val category: String,
    val parameters: Map<String, String>,
) {
    fun candidate(model: String?, sourceLabel: String): ComponentImportCandidate = ComponentImportCandidate(
        sourceType = ComponentImportSourceType.SupplierOcr,
        rawPayload = model.orEmpty(),
        sourceLabel = sourceLabel,
        name = model.orEmpty(),
        model = model,
        brand = brand,
        packageName = packageName,
        category = category,
        notes = parameters.map { (name, value) -> "参数：$name：$value" },
    )
}

internal object ReelLabelParser {
    private val explicitModel = Regex(
        """(?i)^\s*(?:MPN|MFR\.?\s*(?:P/N|PART(?:\s+NO\.?)?)|P/N|PART\s*(?:NO\.?|NUMBER|#)|MODEL|厂商型号|制造商型号|型号)\s*(?:[:：]\s*|\s+)([A-Z0-9][A-Z0-9./_+\-]{2,79})(?![A-Z0-9./_+\-])""",
    )
    private val rohmModel = Regex("""(?i)\bMCR\s*[0-9O]{2}\s*EZP\s*(?:FX|[FJ])\s*(?:[0-9O]{3,4}|\dR\d{2})\b""")
    private val yageoModel = Regex("""(?i)\bRC\s*[0-9O]{4}\s*[A-Z]\s*R\s*-\s*\d{2}\s*[A-Z0-9]{2,8}\b""")
    private val samsungModel = Regex("""(?i)\bCL\s*[0-9O]{2}\s*[A-Z]\s*[0-9O]{3}\s*[A-Z]{2}\s*[A-Z0-9]{2,6}\b""")
    private val standaloneModel = Regex("""(?i)[A-Z][A-Z0-9./_+\-]{5,79}""")
    private val quantity = """([0-9]{1,3}(?:[,. ]\d{3})+|[0-9]{1,7})"""
    private val numericModelSegments = listOf(
        Regex("""^MCR([0-9O]{2})(?=EZP)"""),
        Regex("""^MCR\d{2}EZP(?:FX|[FJ])([0-9O]{3,4})$"""),
        Regex("""^RC([0-9O]{4})(?=[A-Z]R-)"""),
        Regex("""^CL([0-9O]{2})(?=[A-Z][0-9O]{3})"""),
        Regex("""^CL\d{2}[A-Z]([0-9O]{3})(?=[A-Z]{2})"""),
    )
    private val quantityPatterns = listOf(
        Regex("""(?i)(?:\bQTY\b|\bQUANTITY\b|数量)\s*[:：]?\s*$quantity(?![\d.,])"""),
        Regex("""(?i)(?<![\d.,])$quantity\s*(?:PCS\b|件|个)"""),
    )
    private val parameterPatterns = mapOf(
        "Resistance" to Regex("""(?i)(?<![A-Z0-9.])\d+(?:\.\d+)?\s*(?:[KM]?Ω|[KM]?OHMS?|R)(?![A-Z0-9])"""),
        "Capacitance" to Regex("""(?i)(?<![A-Z0-9.])\d+(?:\.\d+)?\s*[PNµμU]F(?![A-Z0-9])"""),
        "Inductance" to Regex("""(?i)(?<![A-Z0-9.])\d+(?:\.\d+)?\s*[NµμUM]H(?![A-Z0-9])"""),
        "Rated Voltage" to Regex("""(?i)(?<![A-Z0-9.])\d+(?:\.\d+)?\s*V(?:DC|AC)?(?![A-Z0-9])"""),
        "Tolerance" to Regex("""±\s*\d+(?:\.\d+)?\s*%"""),
    )
    private val packageLabel = Regex("""(?i)\b(?:0201|0402|0603|0805|1206|1210|1812|2010|2512)\b""")
    private val brandNames = listOf("ROHM", "YAGEO", "SAMSUNG", "MURATA", "TDK", "TAIYO YUDEN", "VISHAY", "KOA", "WALSIN")
    private val explicitBrand = Regex("""(?i)^\s*(?:brand|manufacturer|mfg|品牌|厂商)\s*[:：]\s*(.{1,60})$""")

    fun parse(lines: List<String>): ReelLabel? {
        val cleanLines = lines.map { it.replace('－', '-').replace('−', '-').trim() }
        val manufacturerModels = cleanLines.flatMap { line ->
            listOf(rohmModel, yageoModel, samsungModel).flatMap { pattern ->
                pattern.findAll(line).map { printedModel(it.value) }.toList()
            }
        }.distinct()
        val labeledModels = cleanLines.mapNotNull { explicitModel.find(it)?.groupValues?.get(1) }
            .map(::printedModel).distinct()
        val preferred = labeledModels.ifEmpty { manufacturerModels }
        val readModels = preferred.ifEmpty {
            cleanLines.filter { standaloneModel.matches(it) && it.any(Char::isDigit) }.map(::modelKey).distinct()
        }.sortedByDescending(String::length)
        if (readModels.isEmpty()) return null

        val brand = cleanLines.mapNotNull { explicitBrand.matchEntire(it)?.groupValues?.get(1)?.trim() }
            .distinct().singleOrNull()
            ?: brandNames.filter { name ->
                val pattern = Regex("""(?i)\b${Regex.escape(name)}\b""")
                cleanLines.any(pattern::containsMatchIn)
            }.singleOrNull()
            ?: "ROHM".takeIf { manufacturerModels.any(rohmModel::matches) }
        // Samsung's 1608 marking independently identifies the CL10 size code.
        // Keep both readings so the suggested correction requires confirmation.
        val samsung1608 = brand.equals("SAMSUNG", ignoreCase = true) &&
            cleanLines.any { it.startsWith("1608 ") }
        val correctedModels = if (samsung1608) {
            readModels.filter { it.startsWith("CL18") && samsungModel.matches(it) }
                .map { "CL10" + it.drop(4) }
        } else emptyList()
        val models = (correctedModels + readModels).distinct().take(8)
        val quantities = cleanLines.flatMap { line ->
            quantityPatterns.flatMap { pattern ->
                pattern.findAll(line).mapNotNull { it.groupValues[1].filter(Char::isDigit).toIntOrNull() }.toList()
            }
        }.distinct()
        val parameters = parameterPatterns.mapNotNull { (name, pattern) ->
            cleanLines.flatMap { line -> pattern.findAll(line).map { it.value.filterNot(Char::isWhitespace) }.toList() }
                .distinct().singleOrNull()?.let { name to it }
        }.toMap()
        val packageName = cleanLines.mapNotNull { packageLabel.find(it)?.value }.distinct().singleOrNull()
            ?: "0603".takeIf {
                models.all { model -> model.startsWith("RC0603") } ||
                    samsung1608
            }.orEmpty()
        val category = when {
            "Capacitance" in parameters -> "电容"
            "Inductance" in parameters -> "电感"
            "Resistance" in parameters -> "电阻"
            else -> ""
        }
        val automaticModel = preferred.singleOrNull()?.takeIf { models.size == 1 }
        return ReelLabel(models, automaticModel, brand, quantities.singleOrNull(), packageName, category, parameters)
    }

    fun exactProducts(model: String, products: List<LcscDomesticProduct>): List<LcscDomesticProduct> {
        val key = modelKey(model)
        return products.filter { it.metadata.model?.let(::modelKey) == key }
    }

    private fun printedModel(value: String): String = numericModelSegments.fold(modelKey(value)) { model, pattern ->
        val digits = pattern.find(model)?.groups?.get(1) ?: return@fold model
        model.replaceRange(digits.range, digits.value.replace('O', '0'))
    }

    private fun modelKey(value: String): String =
        value.filterNot(Char::isWhitespace).replace('－', '-').replace('−', '-').uppercase(Locale.ROOT)
}
