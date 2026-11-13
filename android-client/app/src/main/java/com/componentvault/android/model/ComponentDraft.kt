package com.componentvault.android.model

data class ComponentDraft(
    val id: String? = null,
    val sku: String,
    val name: String,
    val category: String,
    val packageName: String,
    val location: String,
    val description: String,
    val quantity: Int,
    val minStock: Int,
    val valuationUnitCost: String? = null,
    val valuationAmount: String? = null,
    val purchaseAmount: String? = null,
    val expectedUpdatedAt: String? = null,
)
