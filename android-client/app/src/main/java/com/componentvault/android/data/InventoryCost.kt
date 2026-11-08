package com.componentvault.android.data

import java.math.BigDecimal
import java.math.RoundingMode

/** Null denotes unknown CNY cost; explicit zero denotes a free purchase. */
object InventoryCost {
    fun decimal(value: String?, scale: Int = 8): BigDecimal? = value?.takeIf { it.isNotBlank() }?.let {
        require(Regex("[0-9]+(\\.[0-9]+)?").matches(it)) { "金额必须使用十进制数字。" }
        BigDecimal(it).also { number -> require(number.signum() >= 0 && number.scale() <= scale) { "金额必须非负且最多 $scale 位小数。" } }
    }
    fun signed(value: String?): String? = value?.takeIf(String::isNotBlank)?.let {
        require(Regex("-?[0-9]+(\\.[0-9]+)?").matches(it))
        val number = BigDecimal(it); require(number.scale() <= 16); text(number)
    }
    fun text(value: BigDecimal?): String? = value?.stripTrailingZeros()?.toPlainString()
    fun inbound(quantity: Int, average: String?, received: Int, amount: String?): String? {
        require(received > 0)
        val purchase = decimal(amount) ?: return null
        val prior = if (quantity == 0) BigDecimal.ZERO else decimal(average, 16)?.multiply(BigDecimal(quantity)) ?: return null
        return text(prior.add(purchase).divide(BigDecimal(Math.addExact(quantity, received)), 16, RoundingMode.HALF_EVEN))
    }
    fun amount(average: String?, quantity: Int): String? = text(decimal(average, 16)?.multiply(BigDecimal(kotlin.math.abs(quantity.toLong()))))
}
