package com.componentvault.android.data.bom

import com.componentvault.android.data.normalizeElectricalParameter
import com.componentvault.android.data.withHeaderUnit
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/** Conservative suggestions for unmatched passive BOM rows. Selection remains a user action. */
object BomElectricalAlternativeMatcher {
    data class Result(
        val candidates: List<InventoryMatchCandidate>,
        val explanation: String,
    )

    private val required = mapOf(
        "R" to listOf("resistance", "tolerance", "power"),
        "C" to listOf("capacitance", "tolerance", "voltage"),
        "L" to listOf("inductance", "tolerance", "current"),
    )
    private val labels = mapOf(
        "type" to "类型", "package" to "封装", "resistance" to "阻值",
        "capacitance" to "容量", "inductance" to "电感量", "tolerance" to "精度",
        "power" to "功率", "voltage" to "耐压", "current" to "额定电流",
        "temperature_coefficient" to "温度系数",
    )
    private val numberWithUnit = Regex(
        "(?i)(?<![a-z0-9])(?:\\d+(?:\\.\\d+)?|\\d+/\\d+)\\s*" +
            "(?:[kKmM]?\\s*(?:Ω|ohms?)|[pnumµμ]?F|[numµμ]?H|[kKmMµμu]?V|[mµμu]?A|[mµμu]?W|ppm/(?:°?[CK])|%)",
    )

    fun suggest(requirement: BomRequirement, inventory: List<InventoryMatchCandidate>): Result {
        val kind = sourceType(requirement)
            ?: return Result(emptyList(), "无法推荐替代：缺少明确的 R/C/L 类型。")
        val packageName = requirement.packageName?.trim()?.takeIf(String::isNotEmpty)
            ?: return Result(emptyList(), "无法推荐替代：缺少封装。")
        val source = sourceValues(requirement)
        val missing = required.getValue(kind).filterNot(source::containsKey)
        if (missing.isNotEmpty()) {
            return Result(emptyList(), "无法推荐替代：缺少${missing.joinToString("、") { labels.getValue(it) }}。")
        }
        if (source.values.any { it == null }) {
            return Result(emptyList(), "无法推荐替代：BOM 电气参数无法识别或互相冲突。")
        }
        val candidates = inventory.filter { candidate ->
            candidate.active && classify(candidate.category) == kind &&
                candidate.packageName?.normalizedIdentityPart() == packageName.normalizedIdentityPart() &&
                source.all { (key, value) ->
                    normalizeElectricalParameter(key, candidate.parameters.entries.firstOrNull { parameterKind(it.key) == key }?.value) == value
                }
        }
        return Result(
            candidates,
            if (candidates.isEmpty()) "无符合类型、封装及全部已给电气参数的库存候选。"
            else "电气参数与封装一致；请人工复核并选择替代料。",
        )
    }

    private fun sourceType(requirement: BomRequirement): String? {
        val types = requirement.sourceRows.flatMap { row ->
            row.fields.mapNotNull { (key, value) ->
                when {
                    normalizedKey(key) in setOf("type", "category", "类型", "类别", "元件类型") -> classify(value)
                    normalizedKey(key) in setOf("reference", "references", "ref", "refdes", "designator", "位号", "参考位号", "元件位号") ->
                        Regex("(?i)^\\s*([RCL])\\d+").find(value)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
                    else -> null
                }
            }
        }.distinct()
        return types.singleOrNull()
    }

    private fun sourceValues(requirement: BomRequirement): Map<String, BigDecimal?> {
        val values = mutableMapOf<String, BigDecimal?>()
        for (row in requirement.sourceRows) for ((header, raw) in row.fields) {
            if (raw.isBlank()) continue
            val key = parameterKind(header)
            if (key != null) {
                val value = normalizeElectricalParameter(key, raw.withHeaderUnit(header))
                merge(values, key, value)
            } else if (isUnsupportedElectricalHeader(header)) {
                merge(values, "unsupported:${header.trim()}", null)
            } else if (normalizedKey(header) in setOf("comment", "comments", "备注", "描述")) {
                for (token in numberWithUnit.findAll(raw).map { it.value }) {
                    val tokenKind = unitKind(token) ?: continue
                    merge(values, tokenKind, normalizeElectricalParameter(tokenKind, token))
                }
            }
        }
        return values
    }

    private fun merge(values: MutableMap<String, BigDecimal?>, key: String, value: BigDecimal?) {
        if (key in values && values[key] != value) values[key] = null
        else values[key] = value
    }

    private fun unitKind(value: String): String? {
        val unit = Regex("[A-Za-z%Ω/°]+$").find(value.trim())?.value.orEmpty().replace("Ω", "ohm").lowercase(Locale.ROOT)
        return when {
            unit == "%" -> "tolerance"
            unit.startsWith("ppm/") -> "temperature_coefficient"
            unit.endsWith("ohm") || unit.endsWith("ohms") -> "resistance"
            unit.endsWith('f') -> "capacitance"
            unit.endsWith('h') -> "inductance"
            unit.endsWith('v') -> "voltage"
            unit.endsWith('w') -> "power"
            unit.endsWith('a') -> "current"
            else -> null
        }
    }

    private fun parameterKind(key: String): String? = when (normalizedKey(key).substringBefore('(')) {
        "阻值", "电阻值", "resistance" -> "resistance"
        "容值", "容量", "电容值", "电容量", "capacitance" -> "capacitance"
        "电感量", "电感值", "感值", "inductance" -> "inductance"
        "精度", "误差", "容差", "阻值精度", "tolerance" -> "tolerance"
        "功率", "额定功率", "power", "power rating", "rated power" -> "power"
        "耐压", "额定电压", "工作电压", "电压", "voltage", "rated voltage", "voltage rating" -> "voltage"
        "额定电流", "工作电流", "电流", "current", "rated current", "current rating" -> "current"
        "温度系数", "电阻温度系数", "temperature coefficient", "tempco" -> "temperature_coefficient"
        else -> null
    }

    private fun isUnsupportedElectricalHeader(header: String): Boolean {
        val key = normalizedKey(header)
        return listOf("阻抗", "esr", "dcr", "纹波", "ripple", "频率", "frequency",
            "漏电", "leakage", "q factor", "品质因数", "工作温度", "operating temperature",
            "resistance", "capacitance", "inductance", "current", "voltage", "power",
            "电阻", "电容", "电感", "电流", "电压", "功率", "容量")
            .any { it in key }
    }

    private fun classify(value: String?): String? {
        val text = value?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when {
            text == "r" || "电阻" in text || "resistor" in text -> "R"
            text == "c" || "电容" in text || "capacitor" in text -> "C"
            text == "l" || "电感" in text || "inductor" in text -> "L"
            else -> null
        }
    }

    private fun normalizedKey(key: String): String = Normalizer.normalize(key.trim(), Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT)
}
