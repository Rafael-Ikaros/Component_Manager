package com.componentvault.android.model

data class MovementEntryDraft(
    val componentId: String,
    val movementType: String,
    val quantity: Int,
    val reason: String,
    val note: String,
    val locationId: String? = null,
    val destinationLocationId: String? = null,
    val purchaseAmount: String? = null,
)
