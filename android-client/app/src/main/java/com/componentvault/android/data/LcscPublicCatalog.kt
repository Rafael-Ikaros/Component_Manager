package com.componentvault.android.data

import com.componentvault.android.model.ComponentOfficialMetadata
import com.componentvault.android.model.ReferencePriceSnapshot
import com.componentvault.android.model.trustedProductImageUrl
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CancellationException

/** Reads public metadata and explicitly denominated reference prices; never imports marketplace stock. */
internal object LcscPublicCatalog {
    private val skuPattern = Regex("C[0-9]{1,10}")
    private val scriptPattern = Regex(
        """<script\b[^>]*\btype\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun normalizeSku(value: String): String? = value.trim().uppercase(Locale.ROOT)
        .takeIf { skuPattern.matches(it) }

    fun productUrl(sku: String): String? = normalizeSku(sku)?.let {
        "https://www.lcsc.com/product-detail/$it.html"
    }

    fun domesticSearchUrl(sku: String): String? = normalizeSku(sku)?.let {
        "https://so.szlcsc.com/global.html?k=$it"
    }

    fun parsePage(sku: String, html: String): ComponentOfficialMetadata? {
        val expectedSku = normalizeSku(sku) ?: return null
        for (script in scriptPattern.findAll(html)) {
            val json = runCatching { JSONTokener(script.groupValues[1]).nextValue() }.getOrNull()
            val product = products(json).firstOrNull {
                normalizeSku(it.text("sku").orEmpty()) == expectedSku
            } ?: continue
            val model = product.text("mpn")
            val description = product.text("description") ?: product.text("name")
            val name = model ?: product.text("name") ?: expectedSku
            val brand = product.optJSONObject("brand")?.text("name") ?: product.text("brand")
            val categoryPath = product.text("category")
            val properties = product.optJSONArray("additionalProperty")
            val parameters = buildMap {
                (0 until (properties?.length() ?: 0)).forEach { index ->
                    val property = properties?.optJSONObject(index) ?: return@forEach
                    val key = property.text("name")?.trim().orEmpty()
                    val value = property.text("value")?.trim().orEmpty()
                    if (key.isNotBlank() && value.isNotBlank() && !key.equals("Package", true))
                        put(key, value)
                }
            }
            val packageName = (0 until (properties?.length() ?: 0)).asSequence()
                .mapNotNull { properties?.optJSONObject(it) }
                .firstOrNull { it.text("name").equals("Package", true) }
                ?.text("value")
            val imageUrl = productImageUrl(product.opt("image"))
            return ComponentOfficialMetadata(
                source = "lcsc_public_web",
                sku = expectedSku,
                name = name,
                description = description?.takeUnless { it.equals(name, ignoreCase = true) },
                model = model,
                brand = brand,
                vendor = brand,
                packageName = packageName,
                category = categoryPath?.let(OfficialCategoryNormalizer::normalize)
                    ?: ComponentCategoryInferencer.infer(
                        description ?: name,
                        packageName,
                        model,
                        brand,
                    ),
                categoryPath = categoryPath,
                officialUrl = productUrl(expectedSku),
                imageUrl = imageUrl,
                matchedBy = "sku",
                confidence = "exact",
                parameters = parameters,
                referencePrice = referencePrice(product, requireNotNull(productUrl(expectedSku))),
            )
        }
        return null
    }

    private fun products(value: Any?): Sequence<JSONObject> = sequence {
        when (value) {
            is JSONArray -> for (index in 0 until value.length()) yieldAll(products(value.opt(index)))
            is JSONObject -> {
                val types = value.opt("@type")
                if (types == "Product" || types is JSONArray &&
                    (0 until types.length()).any { types.optString(it) == "Product" }
                ) yield(value)
                yieldAll(products(value.opt("@graph")))
            }
        }
    }

    private fun referencePrice(product: JSONObject, url: String): ReferencePriceSnapshot? {
        val value = product.opt("offers")
        val offers = when (value) {
            is JSONObject -> listOf(value)
            is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
            else -> emptyList()
        }
        return offers.firstNotNullOfOrNull { offer ->
            if (offer.optString("@type") == "AggregateOffer") null else {
                val quantity = offer.optJSONObject("eligibleQuantity")?.opt("minValue")?.toString()?.toBigDecimalOrNull()
                    ?.let { runCatching { it.intValueExact() }.getOrNull() }?.takeIf { it > 0 }
                ReferencePriceSnapshot.create(offer.opt("price")?.toString(), offer.text("priceCurrency"), quantity, url, java.time.Instant.now().toString())
            }
        }
    }

    private fun JSONObject.text(key: String): String? = (opt(key) as? String)
        ?.trim()?.takeIf { it.isNotEmpty() }

    fun trustedImageUrl(value: String?): String? = trustedProductImageUrl(value)

    private fun productImageUrl(value: Any?): String? = when (value) {
        is String -> trustedImageUrl(value)
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { productImageUrl(value.opt(it)) }
            .firstOrNull()
        is JSONObject -> trustedImageUrl(value.text("contentUrl"))
            ?: trustedImageUrl(value.text("url"))
        else -> null
    }

    fun fetchPage(url: String): String {
        // URL comes exclusively from productUrl, never from the QR payload or HTML.
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "ComponentVault-Android/0.7")
            connection.setRequestProperty("Accept", "text/html")
            val status = connection.responseCode
            AppDiagnostics.record("lookup_international", "http" to status)
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $status")
            }
            val maxBytes = 2 * 1024 * 1024
            val body = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) throw IOException("Product page too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return body.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}

/** Bounded cache also backs off repeated failures from scans of the same package. */
internal class LcscPublicLookup(
    private val fetch: (String) -> String = LcscPublicCatalog::fetchPage,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val at: Long, val metadata: ComponentOfficialMetadata?)
    private val cache = linkedMapOf<String, Entry>()

    @Synchronized
    fun lookup(sku: String): ComponentOfficialMetadata? {
        val normalized = LcscPublicCatalog.normalizeSku(sku) ?: return null
        val timestamp = now()
        cache[normalized]?.let { entry ->
            val ttl = if (entry.metadata == null) 30_000L else 7 * 24 * 60 * 60 * 1000L
            if (timestamp - entry.at in 0 until ttl) {
                AppDiagnostics.record("lookup_international", "cache" to true, "matched" to (entry.metadata != null))
                return entry.metadata
            }
        }
        val metadata = try {
            LcscPublicCatalog.parsePage(normalized, fetch(requireNotNull(LcscPublicCatalog.productUrl(normalized))))
        } catch (error: IOException) {
            AppDiagnostics.record("lookup_international", "cache" to false, "matched" to false, "type" to error.javaClass)
            remember(normalized, Entry(timestamp, null))
            throw error
        }
        AppDiagnostics.record("lookup_international", "cache" to false, "matched" to (metadata != null))
        remember(normalized, Entry(timestamp, metadata))
        return metadata
    }

    private fun remember(sku: String, entry: Entry) {
        cache.remove(sku)
        cache[sku] = entry
        if (cache.size > 64) cache.remove(cache.keys.first())
    }
}

/** Language-selected public source, with no cross-region fallback or quote supplementation. */
internal class LcscCombinedLookup(
    private val international: LcscPublicLookup = LcscPublicLookup(),
    private val edaLookup: (String) -> ComponentOfficialMetadata? = EasyEdaCatalog::lookup,
    private val edaSearch: (String) -> List<LcscDomesticProduct> = EasyEdaCatalog::search,
    private val internationalSearch: (String) -> List<LcscDomesticProduct> = EasyEdaCatalog::searchInternational,
) {
    fun searchPublicCatalog(keyword: String, language: com.componentvault.android.model.AppLanguage): List<LcscDomesticProduct> =
        when (language) {
            com.componentvault.android.model.AppLanguage.ZhCn -> edaSearch(keyword)
            com.componentvault.android.model.AppLanguage.English -> internationalSearch(keyword)
        }
    fun lookup(
        sku: String,
        preferredSource: LcscCatalogSource = LcscCatalogSource.Eda,
    ): ComponentOfficialMetadata? = lookupWithRoute(sku, preferredSource).metadata

    fun lookupWithRoute(
        sku: String,
        preferredSource: LcscCatalogSource,
    ): LcscCatalogLookupResult {
        val normalized = LcscPublicCatalog.normalizeSku(sku)
            ?: return LcscCatalogLookupResult(null, preferredSource, null, emptyList())
        val attempts = mutableListOf<LcscCatalogAttempt>()
        for (source in LcscCatalogRoutePolicy.order(preferredSource)) {
            try {
                val metadata = when (source) {
                    LcscCatalogSource.Domestic -> edaLookup(normalized)
                    LcscCatalogSource.International -> international.lookup(normalized)
                    LcscCatalogSource.Eda -> edaLookup(normalized)
                }
                AppDiagnostics.record(
                    "lookup_${source.name.lowercase(Locale.ROOT)}",
                    "matched" to (metadata != null),
                    "preferred" to (source == preferredSource),
                )
                if (metadata != null) {
                    return LcscCatalogLookupResult(metadata, preferredSource, source, attempts)
                }
                attempts += LcscCatalogAttempt(source, LcscCatalogFailureKind.NoMatch)
            } catch (error: Exception) {
                if (error is InterruptedException || error is CancellationException) throw error
                val failure = LcscCatalogRoutePolicy.classify(error)
                attempts += LcscCatalogAttempt(source, failure)
                AppDiagnostics.record(
                    "lookup_${source.name.lowercase(Locale.ROOT)}",
                    "matched" to false,
                    "failure" to failure,
                    "type" to error.javaClass,
                )
            }
        }
        return LcscCatalogLookupResult(null, preferredSource, null, attempts)
    }
}
