package com.componentvault.android.model

import java.math.BigDecimal
import java.time.Instant

data class ReferencePriceSnapshot(
    val unitPrice: String,
    val currency: String,
    val minimumQuantity: Int? = null,
    val sourceUrl: String,
    val queriedAt: String,
) {
    companion object {
        fun create(price: String?, currency: String?, minimumQuantity: Int?, sourceUrl: String?, queriedAt: String?): ReferencePriceSnapshot? {
            val amount = price?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return null
            val code = currency?.trim()?.takeIf { Regex("[A-Z]{3}").matches(it) } ?: return null
            val url = sourceUrl?.takeIf { runCatching { java.net.URI(it).let { uri -> uri.scheme == "https" && !uri.host.isNullOrBlank() } }.getOrDefault(false) } ?: return null
            val at = queriedAt?.takeIf { runCatching { Instant.parse(it) }.isSuccess } ?: return null
            return ReferencePriceSnapshot(amount.stripTrailingZeros().toPlainString(), code, minimumQuantity?.takeIf { it > 0 }, url, at)
        }
    }
}

object ReferencePriceNotes {
    private val prefixes = listOf("参考单价：", "参考币种：", "参考起订量：", "价格来源：", "价格查询时间：")

    fun merge(notes: List<String>, price: ReferencePriceSnapshot?): List<String> {
        if (price == null) return notes
        return notes.flatMap { it.lines() }.filterNot { line -> prefixes.any { line.startsWith(it) } } + buildList {
            add("参考单价：${price.unitPrice}")
            add("参考币种：${price.currency}")
            price.minimumQuantity?.let { add("参考起订量：$it") }
            add("价格来源：${price.sourceUrl}")
            add("价格查询时间：${price.queriedAt}")
        }
    }

    fun parse(description: String): ReferencePriceSnapshot? {
        val lines = description.lines()
        fun value(prefix: String) = lines.lastOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()
        return ReferencePriceSnapshot.create(value(prefixes[0]), value(prefixes[1]), value(prefixes[2])?.toIntOrNull(), value(prefixes[3]), value(prefixes[4]))
    }

    fun display(
        description: String,
        language: AppLanguage = AppLanguage.ZhCn,
        formatTimestamp: (String) -> String = { it },
    ): String? = parse(description)?.let {
        val chinese = language == AppLanguage.ZhCn
        buildString {
            append("${it.currency} ${it.unitPrice}")
            append(if (chinese) " / 件（参考）" else " / piece (reference)")
            append(it.minimumQuantity?.let { quantity ->
                if (chinese) " · 起订 $quantity 件" else " · Minimum $quantity pieces"
            } ?: if (chinese) " · 适用起订量未提供" else " · Applicable minimum quantity not provided")
            append(if (chinese) "\n查询时间：" else "\nQuery time: ")
            append(formatTimestamp(it.queriedAt))
            append(if (chinese) "\n来源：" else "\nSource: ")
            append(it.sourceUrl)
        }
    }
}
