package com.componentvault.android.data

import com.componentvault.android.model.ComponentOfficialMetadata
import com.componentvault.android.model.ReferencePriceSnapshot
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Domestic public library. Official domestic UI renders priceList.price with the yuan sign. */
internal object EasyEdaCatalog {
    fun parse(sku: String, body: String): ComponentOfficialMetadata? {
        val expected = LcscPublicCatalog.normalizeSku(sku) ?: return null
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 200) throw IOException("Invalid JLCEDA response")
        val products = root.optJSONObject("result")?.optJSONArray("productList") ?: return null
        val product = (0 until products.length()).asSequence().mapNotNull { products.optJSONObject(it) }
            .firstOrNull { it.optString("code") == expected } ?: return null
        return parseProduct(expected, product)
    }

    fun parseSearch(body: String): List<LcscDomesticProduct> {
        return parseSearch(body, domestic = true)
    }

    fun parseInternationalSearch(body: String): List<LcscDomesticProduct> = parseSearch(body, domestic = false)

    private fun parseSearch(body: String, domestic: Boolean): List<LcscDomesticProduct> {
        val root = JSONObject(body)
        val successCode = if (domestic) 200 else 0
        if (root.optInt("code", -1) != successCode) throw IOException("Invalid library response")
        val products = root.optJSONObject("result")?.optJSONArray("productList") ?: return emptyList()
        return (0 until products.length()).mapNotNull { index ->
            val product = products.optJSONObject(index) ?: return@mapNotNull null
            val sku = LcscPublicCatalog.normalizeSku(product.optString(if (domestic) "code" else "number")) ?: return@mapNotNull null
            val metadata = parseProduct(sku, product, domestic).copy(matchedBy = "keyword")
            LcscDomesticProduct(metadata, metadata.parameters, metadata.datasheetUrl)
        }.distinctBy { it.metadata.sku }.take(20)
    }

    private fun parseProduct(expected: String, product: JSONObject, domestic: Boolean = true): ComponentOfficialMetadata {
        val device = product.optJSONObject("device_info")
        val attributes = device?.optJSONObject("attributes")
        fun text(json: JSONObject?, key: String) = (json?.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val ignoredAttributes = setOf("Symbol", "Footprint", "Designator", "3D Model", "3D Model Title", "3D Model Transform", "Add into BOM", "Convert to PCB", "Name", "Supplier", "Supplier Part", "Supplier Footprint", "Manufacturer", "Manufacturer Part", "LCSC Part Name", "Datasheet", "JLCPCB Part Class")
        val parameters = buildMap {
            attributes?.keys()?.forEach { key -> if (key !in ignoredAttributes) text(attributes, key)?.let { put(key, it) } }
        }
        val tags = device?.optJSONObject("tags")
        val categoryKey = if (domestic) "name_cn" else "name"
        val categoryPath = listOfNotNull(text(tags?.optJSONObject("parent_tag"), categoryKey), text(tags?.optJSONObject("child_tag"), categoryKey)).joinToString("/").takeIf { it.isNotBlank() }
        val model = text(product, if (domestic) "model" else "mpn") ?: text(attributes, "Manufacturer Part")
        val brand = text(product, if (domestic) "brandName" else "manufacturer") ?: text(attributes, "Manufacturer")
        val images = product.optJSONArray("image")
        val image = if (domestic) text(product, "image")?.split("<$>")?.firstNotNullOfOrNull(LcscPublicCatalog::trustedImageUrl)
            else (0 until (images?.length() ?: 0)).asSequence().mapNotNull { images?.optJSONObject(it) }
                .mapNotNull { LcscPublicCatalog.trustedImageUrl(text(it, "224x224")) }.firstOrNull()
        val productId = product.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val officialUrl = if (!domestic) requireNotNull(LcscPublicCatalog.productUrl(expected)) else productId?.let { "https://item.szlcsc.com/$it.html" }
            ?: requireNotNull(LcscPublicCatalog.domesticSearchUrl(expected))
        val pdf = text(product, "pdfFileUrl")?.let { if (it.startsWith("/")) "https://atta.szlcsc.com$it" else it }
            ?: text(attributes, "Datasheet")
        val priceList = product.optJSONArray("priceList")
        val quote = if (domestic && product.optBoolean("isShowPrice", true)) {
            (0 until (priceList?.length() ?: 0)).asSequence().mapNotNull { priceList?.optJSONObject(it) }
                .mapNotNull { tier ->
                    val minimum = tier.opt("startNumber")?.toString()?.toBigDecimalOrNull()
                        ?.let { runCatching { it.intValueExact() }.getOrNull() }?.takeIf { it > 0 } ?: return@mapNotNull null
                    ReferencePriceSnapshot.create(tier.opt("price")?.toString(), "CNY", minimum, officialUrl, java.time.Instant.now().toString())
                }.minByOrNull { requireNotNull(it.minimumQuantity) }
        } else null
        return ComponentOfficialMetadata(
            source = if (domestic) "lceda_public_library" else "easyeda_public_library", sku = expected, name = model ?: text(product, "name") ?: expected, model = model,
            brand = brand, vendor = brand, packageName = text(product, if (domestic) "standard" else "package") ?: text(attributes, "Supplier Footprint"),
            category = (if (domestic) text(product, "catalogName") else null) ?: categoryPath?.substringAfterLast('/'), categoryPath = categoryPath,
            description = text(product, "name") ?: text(attributes, "LCSC Part Name"), parameters = parameters, imageUrl = image,
            officialUrl = officialUrl, datasheetUrl = pdf, referencePrice = quote,
            matchedBy = "sku", confidence = "exact",
        )
    }

    fun lookup(sku: String): ComponentOfficialMetadata? {
        val normalized = LcscPublicCatalog.normalizeSku(sku) ?: return null
        return parse(normalized, fetch(normalized))
    }

    fun search(keyword: String): List<LcscDomesticProduct> = keyword.trim().takeIf { it.isNotEmpty() }
        ?.let { parseSearch(fetch(it)) }.orEmpty()

    fun searchInternational(keyword: String): List<LcscDomesticProduct> = keyword.trim().takeIf { it.isNotEmpty() }
        ?.let { parseInternationalSearch(fetch(it, domestic = false)) }.orEmpty()

    private fun fetch(keyword: String, domestic: Boolean = true): String {
        val encoded = URLEncoder.encode(keyword, Charsets.UTF_8.name())
        val host = if (domestic) "pro.lceda.cn" else "pro.easyeda.com"
        val connection = URL("https://$host/api/eda/product/search?keyword=$encoded&currPage=1&pageSize=20").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) throw IOException("EasyEDA HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2 * 1024 * 1024) throw IOException("EasyEDA response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }
}
