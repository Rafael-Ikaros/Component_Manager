package com.componentvault.android.model

import java.net.URI
import java.util.Locale

enum class ComponentImportSourceType {
    JlcText,
    JlcQr,
    SupplierOcr,
    WarehouseLabel,
}

enum class ComponentImportFieldOrigin {
    Parsed,
    Rule,
    Learned,
    Server,
    PublicWeb,
    User,
}

data class ComponentImportFieldOrigins(
    val sku: ComponentImportFieldOrigin = ComponentImportFieldOrigin.Parsed,
    val name: ComponentImportFieldOrigin = ComponentImportFieldOrigin.Parsed,
    val category: ComponentImportFieldOrigin = ComponentImportFieldOrigin.Parsed,
    val packageName: ComponentImportFieldOrigin = ComponentImportFieldOrigin.Parsed,
    val model: ComponentImportFieldOrigin = ComponentImportFieldOrigin.Parsed,
    val brand: ComponentImportFieldOrigin = ComponentImportFieldOrigin.Parsed,
)

enum class ComponentImportLearningMatchType {
    Sku,
    Mpn,
}

data class ComponentImportLearningMapping(
    val id: String,
    val sourceType: ComponentImportSourceType,
    val sourceSku: String? = null,
    val sourceMpn: String? = null,
    val resolvedName: String? = null,
    val resolvedCategory: String? = null,
    val resolvedPackageName: String? = null,
    val resolvedModel: String? = null,
    val resolvedBrand: String? = null,
    val resolvedDescription: String? = null,
    val confidence: Int = 100,
    val lastUsedAt: String? = null,
)

data class ComponentImportLearningMatch(
    val mapping: ComponentImportLearningMapping,
    val matchedBy: ComponentImportLearningMatchType,
)

data class ImportLearningSummary(
    val mappingCount: Int = 0,
)

data class ComponentImportCandidate(
    val sourceType: ComponentImportSourceType,
    val rawPayload: String,
    val sourceLabel: String,
    val sku: String = "",
    val name: String = "",
    val packageName: String = "",
    val category: String = "",
    val model: String? = null,
    val brand: String? = null,
    val vendor: String? = null,
    val modelFamily: String? = null,
    val recognitionConfidence: String? = null,
    val matchedBy: String? = null,
    val normalizedPackageKey: String? = null,
    val suggestedQuantity: Int? = null,
    val notes: List<String> = emptyList(),
    val fieldOrigins: ComponentImportFieldOrigins = ComponentImportFieldOrigins(),
) {
    fun toComponentDraft(
        quantity: Int,
        location: String,
        minStock: Int,
        skuOverride: String = sku,
        nameOverride: String = name,
        categoryOverride: String = category,
        packageNameOverride: String = packageName,
        modelOverride: String? = model,
        brandOverride: String? = brand,
        notesOverride: List<String> = notes,
    ): ComponentDraft {
        val description = buildImportDescription(
            sourceLabel = sourceLabel,
            rawPayload = rawPayload,
            model = modelOverride,
            brand = brandOverride,
            notes = notesOverride,
        )

        return ComponentDraft(
            sku = skuOverride.trim(),
            name = nameOverride.trim(),
            category = categoryOverride.trim(),
            packageName = packageNameOverride.trim(),
            location = location.trim(),
            description = description,
            quantity = quantity,
            minStock = minStock,
        )
    }
}

val ComponentImportCandidate.isJlcSource: Boolean
    get() = sourceType == ComponentImportSourceType.JlcText || sourceType == ComponentImportSourceType.JlcQr

enum class ComponentOfficialLookupOutcome {
    Success,
    NoMatch,
    NotConfigured,
    Failed,
}

data class ComponentOfficialMetadata(
    val source: String? = null,
    val sku: String? = null,
    val name: String? = null,
    val description: String? = null,
    val packageName: String? = null,
    val category: String? = null,
    val model: String? = null,
    val brand: String? = null,
    val vendor: String? = null,
    val modelFamily: String? = null,
    val categoryPath: String? = null,
    val officialUrl: String? = null,
    val imageUrl: String? = null,
    val matchedBy: String? = null,
    val confidence: String? = null,
    val ruleVersion: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val datasheetUrl: String? = null,
    val referencePrice: ReferencePriceSnapshot? = null,
)

data class ComponentRecognitionMetadata(
    val source: String = "local_rules",
    val packageName: String? = null,
    val category: String? = null,
    val vendor: String? = null,
    val modelFamily: String? = null,
    val matchedBy: String? = null,
    val confidence: String? = null,
    val ruleVersion: String? = null,
)

data class ComponentOfficialLookupResult(
    val outcome: ComponentOfficialLookupOutcome,
    val metadata: ComponentOfficialMetadata? = null,
    val message: String? = null,
    val fromCache: Boolean = false,
)

data class ComponentImportResolution(
    val candidate: ComponentImportCandidate,
    val learningMatch: ComponentImportLearningMatch? = null,
    val officialLookupResult: ComponentOfficialLookupResult? = null,
)

fun ComponentImportCandidate.referenceDisplayName(): String? {
    val referenceBrand = (vendor ?: brand).orEmpty().trim()
    val referenceModel = model.orEmpty().trim()
    val referenceSku = sku.trim()

    return when {
        referenceModel.isNotBlank() && referenceBrand.isNotBlank() &&
            !referenceModel.contains(referenceBrand, ignoreCase = true) ->
            "$referenceBrand $referenceModel"
        referenceModel.isNotBlank() -> referenceModel
        referenceSku.isNotBlank() -> referenceSku
        else -> null
    }
}

fun ComponentImportCandidate.withLearningMapping(
    learningMatch: ComponentImportLearningMatch,
): ComponentImportCandidate {
    val mapping = learningMatch.mapping
    val mergedNotes = buildList {
        addAll(notes)
        addIfMissing(
            "本地导入学习：匹配方式 ${
                when (learningMatch.matchedBy) {
                    ComponentImportLearningMatchType.Sku -> "SKU"
                    ComponentImportLearningMatchType.Mpn -> "MPN"
                }
            }",
        )
    }

    return copy(
        name = mapping.resolvedName?.takeIf { it.isNotBlank() } ?: name,
        packageName = mapping.resolvedPackageName?.takeIf { it.isNotBlank() } ?: packageName,
        category = mapping.resolvedCategory?.takeIf { it.isNotBlank() } ?: category,
        model = mapping.resolvedModel?.takeIf { it.isNotBlank() } ?: model,
        brand = mapping.resolvedBrand?.takeIf { it.isNotBlank() } ?: brand,
        notes = mergedNotes,
        fieldOrigins = fieldOrigins.copy(
            name = if (!mapping.resolvedName.isNullOrBlank()) {
                ComponentImportFieldOrigin.Learned
            } else {
                fieldOrigins.name
            },
            category = if (!mapping.resolvedCategory.isNullOrBlank()) {
                ComponentImportFieldOrigin.Learned
            } else {
                fieldOrigins.category
            },
            packageName = if (!mapping.resolvedPackageName.isNullOrBlank()) {
                ComponentImportFieldOrigin.Learned
            } else {
                fieldOrigins.packageName
            },
            model = if (!mapping.resolvedModel.isNullOrBlank()) {
                ComponentImportFieldOrigin.Learned
            } else {
                fieldOrigins.model
            },
            brand = if (!mapping.resolvedBrand.isNullOrBlank()) {
                ComponentImportFieldOrigin.Learned
            } else {
                fieldOrigins.brand
            },
        ),
    )
}

fun ComponentImportCandidate.withRecognitionMetadata(
    metadata: ComponentRecognitionMetadata,
): ComponentImportCandidate {
    val resolvedPackageName = when {
        !metadata.packageName.isNullOrBlank() && packageName.isBlank() -> metadata.packageName
        !metadata.packageName.isNullOrBlank() && packageName.equals(sku, ignoreCase = true) -> metadata.packageName
        !metadata.packageName.isNullOrBlank() && !model.isNullOrBlank() &&
            packageName.equals(model, ignoreCase = true) -> metadata.packageName
        else -> packageName
    }.orEmpty()

    val resolvedCategory = when {
        !metadata.category.isNullOrBlank() && category.isBlank() -> metadata.category
        !metadata.category.isNullOrBlank() && category.equals("General", ignoreCase = true) -> metadata.category
        !metadata.category.isNullOrBlank() && category.count { it == '/' } < metadata.category.count { it == '/' } ->
            metadata.category
        else -> category
    }.orEmpty()

    val mergedNotes = buildList {
        addAll(notes)
        metadata.vendor?.let { addIfMissing("识别厂商：$it") }
        metadata.modelFamily?.let { addIfMissing("识别型号族：$it") }
        metadata.matchedBy?.let { addIfMissing("识别命中方式：$it") }
        metadata.confidence?.let { addIfMissing("识别置信度：$it") }
        metadata.ruleVersion?.let { addIfMissing("识别规则版本：$it") }
    }

    val resolvedBrand = brand?.takeIf { it.isNotBlank() }
        ?: vendor?.takeIf { it.isNotBlank() }
        ?: metadata.vendor?.takeIf { it.isNotBlank() }
    val resolvedVendor = vendor?.takeIf { it.isNotBlank() }
        ?: metadata.vendor?.takeIf { it.isNotBlank() }

    return copy(
        packageName = resolvedPackageName,
        category = resolvedCategory,
        brand = resolvedBrand,
        vendor = resolvedVendor,
        modelFamily = modelFamily?.takeIf { it.isNotBlank() } ?: metadata.modelFamily?.takeIf { it.isNotBlank() },
        recognitionConfidence = metadata.confidence?.takeIf { it.isNotBlank() } ?: recognitionConfidence,
        matchedBy = metadata.matchedBy?.takeIf { it.isNotBlank() } ?: matchedBy,
        normalizedPackageKey = metadata.packageName?.takeIf { it.isNotBlank() } ?: normalizedPackageKey,
        notes = mergedNotes,
        fieldOrigins = fieldOrigins.copy(
            category = if (resolvedCategory != category) {
                ComponentImportFieldOrigin.Rule
            } else {
                fieldOrigins.category
            },
            packageName = if (resolvedPackageName != packageName) {
                ComponentImportFieldOrigin.Rule
            } else {
                fieldOrigins.packageName
            },
            brand = if (brand.isNullOrBlank() && !resolvedBrand.isNullOrBlank()) {
                ComponentImportFieldOrigin.Rule
            } else {
                fieldOrigins.brand
            },
        ),
    )
}

fun ComponentImportCandidate.withOfficialMetadata(
    metadata: ComponentOfficialMetadata,
): ComponentImportCandidate {
    val metadataOrigin = if (metadata.source in setOf("lcsc_public_web", "lcsc_domestic_web", "lceda_public_library", "easyeda_public_library")) {
        ComponentImportFieldOrigin.PublicWeb
    } else ComponentImportFieldOrigin.Server
    val resolvedBrand = if (fieldOrigins.brand in setOf(ComponentImportFieldOrigin.User,
            ComponentImportFieldOrigin.Learned)) brand?.trim()?.takeIf(String::isNotBlank)
        else metadata.brand?.trim()?.takeIf(String::isNotBlank)
            ?: brand?.trim()?.takeIf(String::isNotBlank)
            ?: vendor?.trim()?.takeIf(String::isNotBlank)
            ?: metadata.vendor?.trim()?.takeIf(String::isNotBlank)
    val resolvedStoredModel = if (fieldOrigins.model in setOf(ComponentImportFieldOrigin.User,
            ComponentImportFieldOrigin.Learned)) model?.trim()?.takeIf(String::isNotBlank)
        else metadata.model?.trim()?.takeIf(String::isNotBlank)
            ?: model?.trim()?.takeIf(String::isNotBlank)
    val resolvedModel = resolvedStoredModel?.takeIf { it.length <= 80 }
    val officialCanonicalName = resolvedModel?.let { modelName ->
        listOfNotNull(resolvedBrand?.takeUnless { modelName.startsWith(it, ignoreCase = true) }, modelName)
            .joinToString(" ")
    } ?: metadata.name?.trim()?.takeIf { it.isNotBlank() && it.length <= 80 }
        ?: metadata.sku?.trim()?.takeIf { it.isNotBlank() && it.length <= 80 }
    val resolvedName = when {
        officialCanonicalName == null -> name
        name.isBlank() -> officialCanonicalName
        fieldOrigins.name == ComponentImportFieldOrigin.User ||
            fieldOrigins.name == ComponentImportFieldOrigin.Learned -> name
        resolvedModel != null && fieldOrigins.name == ComponentImportFieldOrigin.Parsed -> officialCanonicalName
        name.equals(sku, ignoreCase = true) -> officialCanonicalName
        !model.isNullOrBlank() && name.equals(model, ignoreCase = true) -> officialCanonicalName
        !metadata.model.isNullOrBlank() && name.equals(metadata.model, ignoreCase = true) -> officialCanonicalName
        else -> name
    }.orEmpty()

    val resolvedPackageName = when {
        packageName.isBlank() && !metadata.packageName.isNullOrBlank() -> metadata.packageName
        fieldOrigins.packageName in setOf(ComponentImportFieldOrigin.User, ComponentImportFieldOrigin.Learned) ->
            packageName
        !metadata.packageName.isNullOrBlank() && packageName.equals(sku, ignoreCase = true) -> metadata.packageName
        !metadata.packageName.isNullOrBlank() && !model.isNullOrBlank() &&
            packageName.equals(model, ignoreCase = true) -> metadata.packageName
        !metadata.packageName.isNullOrBlank() && recognitionConfidence.equals("fallback", ignoreCase = true) ->
            metadata.packageName
        else -> packageName
    }.orEmpty()

    val resolvedCategory = when {
        category.isBlank() && !metadata.category.isNullOrBlank() -> metadata.category
        fieldOrigins.category in setOf(ComponentImportFieldOrigin.User, ComponentImportFieldOrigin.Learned) ->
            category
        !metadata.categoryPath.isNullOrBlank() && !metadata.category.isNullOrBlank() -> metadata.category
        category.equals("General", ignoreCase = true) && !metadata.category.isNullOrBlank() -> metadata.category
        !metadata.category.isNullOrBlank() && category.count { it == '/' } < metadata.category.count { it == '/' } ->
            metadata.category
        !metadata.category.isNullOrBlank() && recognitionConfidence.equals("fallback", ignoreCase = true) ->
            metadata.category
        else -> category
    }.orEmpty()

    val mergedNotes = buildList {
        addAll(ReferencePriceNotes.merge(notes, metadata.referencePrice))
        metadata.source?.let { addIfMissing("识别来源：$it") }
        if (metadata.source in setOf("lceda_public_library", "easyeda_public_library")) {
            addIfMissing("资料来源：嘉立创EDA/EasyEDA官方库")
            addIfMissing("元件库链接：https://lceda.cn/ · https://easyeda.com")
        }
        metadata.vendor?.let { addIfMissing("识别厂商：$it") }
        metadata.modelFamily?.let { addIfMissing("识别型号族：$it") }
        metadata.matchedBy?.let { addIfMissing("官方查询：匹配方式 ${it.uppercase()}") }
        metadata.categoryPath?.let { addIfMissing("官方分类路径：$it") }
        metadata.officialUrl?.let { addIfMissing("官方链接：$it") }
        metadata.description?.let { addIfMissing("官方描述：$it") }
        trustedProductImageUrl(metadata.imageUrl)?.let { addIfMissing("商品图片：$it") }
        metadata.datasheetUrl?.let { addIfMissing("数据手册：$it") }
        metadata.parameters.forEach { (key, value) -> addIfMissing("参数：$key：$value") }
        metadata.ruleVersion?.let { addIfMissing("识别规则版本：$it") }
    }

    val resolvedVendor = vendor?.takeIf { it.isNotBlank() }
        ?: metadata.vendor?.takeIf { it.isNotBlank() }
        ?: metadata.brand?.takeIf { it.isNotBlank() }

    return copy(
        sku = sku.ifBlank { metadata.sku?.takeIf { it.isNotBlank() }.orEmpty() },
        name = resolvedName,
        packageName = resolvedPackageName,
        category = resolvedCategory,
        model = resolvedStoredModel,
        brand = resolvedBrand,
        vendor = resolvedVendor,
        modelFamily = modelFamily?.takeIf { it.isNotBlank() } ?: metadata.modelFamily?.takeIf { it.isNotBlank() },
        recognitionConfidence = metadata.confidence?.takeIf { it.isNotBlank() } ?: recognitionConfidence,
        matchedBy = metadata.matchedBy?.takeIf { it.isNotBlank() } ?: matchedBy,
        normalizedPackageKey = metadata.packageName?.takeIf { it.isNotBlank() } ?: normalizedPackageKey,
        notes = mergedNotes,
        fieldOrigins = fieldOrigins.copy(
            sku = if (sku.isBlank() && !metadata.sku.isNullOrBlank()) {
                metadataOrigin
            } else {
                fieldOrigins.sku
            },
            name = if (resolvedName != name && officialCanonicalName != null) {
                metadataOrigin
            } else {
                fieldOrigins.name
            },
            category = if (resolvedCategory != category && !metadata.category.isNullOrBlank()) {
                metadataOrigin
            } else {
                fieldOrigins.category
            },
            packageName = if (resolvedPackageName != packageName && !metadata.packageName.isNullOrBlank()) {
                metadataOrigin
            } else {
                fieldOrigins.packageName
            },
            model = if (resolvedStoredModel != model && !metadata.model.isNullOrBlank()) {
                metadataOrigin
            } else {
                fieldOrigins.model
            },
            brand = if (resolvedBrand != brand && !metadata.brand.isNullOrBlank()) {
                metadataOrigin
            } else {
                fieldOrigins.brand
            },
        ),
    )
}

fun ComponentRecord.officialRclSpecificationSummary(): String? {
    val officialDescription = description.lineSequence()
        .firstOrNull { it.trim().startsWith("官方描述：") }
        ?.trim()?.removePrefix("官方描述：")
    val summary = rclSpecificationValues(category, officialParameters(), officialDescription)
        .takeIf { it.isNotEmpty() }?.joinToString(" · ")
    return summary
}

fun ComponentRecord.officialParameters(): Map<String, String> {
    val stored = description.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        val raw = when {
            trimmed.startsWith("参数：") -> trimmed.removePrefix("参数：")
            trimmed.startsWith("参数·") -> trimmed.removePrefix("参数·")
            else -> return@mapNotNull null
        }
        val separator = raw.indexOf('：').takeIf { it >= 0 } ?: raw.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        val key = raw.substring(0, separator).trim()
        val value = raw.substring(separator + 1).trim()
        if (key.isBlank() || value.isBlank()) null else key to value
    }.toMap().mapValues { (key, value) ->
        val unit = Regex("""[（(]([^（）()]*)[）)]\s*$""").find(key)?.groupValues?.get(1)?.trim().orEmpty()
        if (unit.isNotBlank() && value.matches(Regex("""\d+(?:\.\d+)?"""))) "$value$unit" else value
    }.toMutableMap()
    val officialDescription = description.lineSequence()
        .firstOrNull { it.trim().startsWith("官方描述：") }
        ?.trim()?.removePrefix("官方描述：")
    val inferred = rclSpecificationValues(category, stored, officialDescription)
    val primaryKind = when {
        "电阻" in category || category.contains("resistor", true) -> "resistance"
        "电容" in category || category.contains("capacitor", true) -> "capacitance"
        "电感" in category || category.contains("inductor", true) -> "inductance"
        else -> null
    }
    if (primaryKind != null && stored.keys.none { officialParameterKind(it) == primaryKind }) {
        inferred.firstOrNull { value -> when (primaryKind) {
            "resistance" -> value.matches(Regex("""(?i)\d+(?:\.\d+)?\s*(?:[km]?Ω|[km]?ohm)"""))
            "capacitance" -> value.matches(Regex("""(?i)\d+(?:\.\d+)?\s*(?:pf|nf|uf|μf|mf)"""))
            else -> value.matches(Regex("""(?i)\d+(?:\.\d+)?\s*(?:nh|uh|μh|mh)"""))
        } }?.let { stored[when (primaryKind) {
            "resistance" -> "阻值"
            "capacitance" -> "容量"
            else -> "电感量"
        }] = it }
    }
    if (stored.keys.none { officialParameterKind(it) == "tolerance" })
        inferred.firstOrNull { "%" in it }?.let { stored["精度"] = it }
    if (primaryKind == "capacitance" && stored.keys.none { officialParameterKind(it) == "voltage" })
        inferred.firstOrNull { it.matches(Regex("""(?i)\d+(?:\.\d+)?\s*V""")) }
            ?.let { stored["耐压"] = it }
    return stored
}

fun officialParameterKind(key: String): String? {
    val normalized = java.text.Normalizer.normalize(key.trim(), java.text.Normalizer.Form.NFKC)
        .replace(Regex("\\s*\\([^()]*\\)$"), "").trim().lowercase(Locale.ROOT)
    return when (normalized) {
        "阻值", "电阻值", "resistance" -> "resistance"
        "容值", "容量", "电容值", "电容量", "capacitance" -> "capacitance"
        "电感量", "电感值", "感值", "inductance" -> "inductance"
        "耐压", "额定电压", "工作电压", "电压", "voltage", "rated voltage", "voltage rating" -> "voltage"
        "精度", "误差", "容差", "阻值精度", "tolerance" -> "tolerance"
        "功率", "额定功率", "power", "power rating", "rated power" -> "power"
        "工作温度", "工作温度范围", "operating temperature", "operating temp", "operating temperature range" -> "operating_temperature"
        "类型", "元件类型", "type", "component type" -> "type"
        "温度系数", "电阻温度系数", "temperature coefficient", "resistance temperature coefficient", "tempco" -> "temperature_coefficient"
        else -> null
    }
}

fun ComponentRecord.officialParameterSearchAliases(): String = officialParameters().mapNotNull { (key, value) ->
    val aliases = when (officialParameterKind(key)) {
        "resistance" -> listOf("阻值", "resistance")
        "capacitance" -> listOf("容量", "capacitance")
        "inductance" -> listOf("电感量", "电感值", "inductance")
        "voltage" -> listOf("耐压", "voltage")
        "tolerance" -> listOf("精度", "tolerance")
        "power" -> listOf("功率", "power")
        "operating_temperature" -> listOf("工作温度", "operating temperature")
        "type" -> listOf("类型", "type")
        "temperature_coefficient" -> listOf("温度系数", "temperature coefficient")
        else -> emptyList()
    }
    aliases.takeIf { it.isNotEmpty() }?.joinToString(" ") { "$it$value" }
}.joinToString(" ")
private fun rclSpecificationValues(
    category: String,
    parameters: Map<String, String>,
    officialDescription: String? = null,
): List<String> {
    val categoryText = category.lowercase(Locale.ROOT)
    val kind = when {
        "电阻" in categoryText || "resistor" in categoryText -> "resistance"
        "电容" in categoryText || "capacitor" in categoryText -> "capacitance"
        "电感" in categoryText || "inductor" in categoryText -> "inductance"
        else -> return emptyList()
    }
    val primaryKeys = when (kind) {
        "resistance" -> listOf("阻值", "电阻值", "resistance")
        "capacitance" -> listOf("容值", "容量", "电容值", "电容量", "capacitance")
        else -> listOf("电感量", "电感值", "感值", "inductance")
    }
    fun parameter(keys: List<String>): String? = parameters.entries.firstOrNull { (key, value) ->
        val normalizedKey = java.text.Normalizer.normalize(key.trim(), java.text.Normalizer.Form.NFKC)
            .replace(Regex("\\s*\\([^()]*\\)$"), "")
            .trim()
        value.trim().length in 1..32 && keys.any { normalizedKey.equals(it, ignoreCase = true) }
    }?.value?.trim()

    val descriptionValue = officialDescription?.let { source ->
        val pattern = when (kind) {
            "resistance" -> Regex("""(?i)(?<![a-z0-9])\d{1,6}(?:\.\d{1,6})?\s*(?:[kKmM]?Ω|[kKmM]?ohm)(?![a-z0-9])""")
            "capacitance" -> Regex("""(?i)(?<![a-z0-9])\d{1,6}(?:\.\d{1,6})?\s*(?:pF|nF|uF|μF|mF)(?![a-z0-9])""")
            else -> Regex("""(?i)(?<![a-z0-9])\d{1,6}(?:\.\d{1,6})?\s*(?:nH|uH|μH|mH)(?![a-z0-9])""")
        }
        pattern.find(source)?.value?.trim()
    }
    val descriptionTolerance = officialDescription?.let { source ->
        Regex("""(?:±|\+/-)\s*\d{1,3}(?:\.\d{1,3})?\s*%""").find(source)?.value?.trim()
    }
    val primary = parameter(primaryKeys) ?: descriptionValue ?: return emptyList()
    return listOfNotNull(
        primary,
        parameter(listOf("精度", "误差", "容差", "阻值精度", "tolerance")) ?: descriptionTolerance,
        if (kind == "capacitance") parameter(listOf("耐压", "额定电压", "工作电压", "电压", "voltage", "rated voltage", "voltage rating"))
            ?: officialDescription?.let { Regex("""(?i)(?<![a-z0-9])\d{1,4}(?:\.\d{1,3})?\s*V(?![a-z0-9])""").find(it)?.value?.trim() }
        else null,
    ).distinctBy { it.lowercase(Locale.ROOT) }
}

data class ParsedImportDescription(
    val model: String? = null,
    val brand: String? = null,
    val sourceLabel: String? = null,
    val rawPayload: String? = null,
    val notes: List<String> = emptyList(),
)

fun trustedProductImageUrl(value: String?): String? {
    val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    return normalized.takeIf {
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port == -1 &&
            host in setOf(
                "assets.lcsc.com",
                "alimg.szlcsc.com",
                "www.lcsc.com",
                "img.szlcsc.com",
                "image.szlcsc.com",
                "static.szlcsc.com",
            )
    }
}

fun productImageUrlFromDescription(description: String): String? =
    parseImportDescription(description).notes.firstNotNullOfOrNull { note ->
        when {
            note.startsWith("商品图片：") -> note.removePrefix("商品图片：").trim()
            note.startsWith("Product image: ") -> note.removePrefix("Product image: ").trim()
            else -> null
        }?.let(::trustedProductImageUrl)
    }

data class ComponentLabelSeed(
    val sku: String,
    val name: String,
    val category: String,
    val packageName: String,
    val location: String,
    val quantity: Int,
    val minStock: Int,
    val model: String? = null,
    val brand: String? = null,
    val sourceLabel: String? = null,
    val rawPayload: String? = null,
    val notes: List<String> = emptyList(),
)

fun ComponentDraft.toLabelSeed(): ComponentLabelSeed {
    val parsed = parseImportDescription(description)
    return ComponentLabelSeed(
        sku = sku,
        name = name,
        category = category,
        packageName = packageName,
        location = location,
        quantity = quantity,
        minStock = minStock,
        model = parsed.model,
        brand = parsed.brand,
        sourceLabel = parsed.sourceLabel,
        rawPayload = parsed.rawPayload,
        notes = parsed.notes,
    )
}

fun ComponentRecord.toLabelSeed(): ComponentLabelSeed {
    val parsed = parseImportDescription(description)
    return ComponentLabelSeed(
        sku = sku,
        name = name,
        category = category,
        packageName = packageName,
        location = location,
        quantity = quantity,
        minStock = minStock,
        model = parsed.model,
        brand = parsed.brand,
        sourceLabel = parsed.sourceLabel,
        rawPayload = parsed.rawPayload,
        notes = parsed.notes,
    )
}

fun buildImportDescription(
    sourceLabel: String,
    rawPayload: String,
    model: String?,
    brand: String?,
    notes: List<String>,
): String {
    val descriptionLines = buildList {
        if (!model.isNullOrBlank()) {
            add("型号：${model.trim()}")
        }
        if (!brand.isNullOrBlank()) {
            add("品牌：${brand.trim()}")
        }
        addAll(notes.map(String::trim).filter(String::isNotBlank))
        add("导入来源：${sourceLabel.trim()}")
        add("原始载荷：${rawPayload.trim()}")
    }
    return descriptionLines.joinToString(separator = "\n")
}

fun parseImportDescription(description: String): ParsedImportDescription {
    var model: String? = null
    var brand: String? = null
    var sourceLabel: String? = null
    var rawPayload: String? = null
    val notes = mutableListOf<String>()

    description.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .forEach { line ->
            when {
                line.startsWith("Model: ") || line.startsWith("型号：") ->
                    model = line
                        .removePrefix("Model: ")
                        .removePrefix("型号：")
                        .trim()
                        .blankToNull()
                line.startsWith("Brand: ") || line.startsWith("品牌：") ->
                    brand = line
                        .removePrefix("Brand: ")
                        .removePrefix("品牌：")
                        .trim()
                        .blankToNull()
                line.startsWith("Import source: ") || line.startsWith("导入来源：") ->
                    sourceLabel = line
                        .removePrefix("Import source: ")
                        .removePrefix("导入来源：")
                        .trim()
                        .blankToNull()
                line.startsWith("Raw payload: ") || line.startsWith("原始载荷：") ->
                    rawPayload = line
                        .removePrefix("Raw payload: ")
                        .removePrefix("原始载荷：")
                        .trim()
                        .blankToNull()
                else -> notes += line
            }
        }

    return ParsedImportDescription(
        model = model,
        brand = brand,
        sourceLabel = sourceLabel,
        rawPayload = rawPayload,
        notes = notes,
    )
}

private fun MutableList<String>.addIfMissing(value: String) {
    if (none { it.equals(value, ignoreCase = true) }) {
        add(value)
    }
}

private fun String.blankToNull(): String? = takeIf { it.isNotBlank() }
