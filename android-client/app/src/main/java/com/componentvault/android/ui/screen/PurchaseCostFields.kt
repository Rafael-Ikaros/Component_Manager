package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
internal fun PurchaseCostFields(text: String, isUnit: Boolean, quantity: Int?, onText: (String)->Unit, onMode: (Boolean)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(selected=!isUnit,onClick={onMode(false)},label={Text("购买总金额")})
        FilterChip(selected=isUnit,onClick={onMode(true)},label={Text("购买单价")})
    }
    OutlinedTextField(text,onText,label={Text(if(isUnit) "购买单价（CNY）" else "购买总金额（CNY）")},supportingText={Text("留空成本未知；0 表示赠品。")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    val number=text.toBigDecimalOrNull()
    if(number!=null && quantity!=null && quantity>0) Text(if(isUnit) "折算总额（CNY）：${number.multiply(BigDecimal(quantity)).toPlainString()}" else "折算单价（CNY）：${number.divide(BigDecimal(quantity),16,RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()}")
}
internal fun purchaseTotal(text: String, isUnit: Boolean, quantity: Int): String? = text.takeIf(String::isNotBlank)?.let {
    val decimal = runCatching { com.componentvault.android.data.InventoryCost.decimal(it) }.getOrNull()
    if(isUnit && decimal != null) decimal.multiply(BigDecimal(quantity)).stripTrailingZeros().toPlainString() else it
}
