package com.componentvault.android.model

internal data class InventoryConflictReview(val components: List<InventoryConflictComponent>)

internal data class InventoryConflictComponent(
    val id: String,
    val sku: String,
    val name: String,
    val serverQuantity: Int,
    val locations: List<InventoryConflictLocation>,
    val movements: List<InventoryConflictMovement>,
    val blockedReason: String?,
    val serverVersion: String,
    val localVersion: String,
)

internal data class InventoryConflictLocation(val id: String, val name: String, val quantity: Int)

internal data class InventoryConflictMovement(
    val id: String,
    val type: String,
    val quantity: Int,
    val locationId: String?,
    val purchaseAmount: String?,
    val reason: String,
)

internal data class InventoryConflictMovementEdit(val id: String, val quantity: Int, val locationId: String)

internal data class InventoryConflictReplayResult(val operation: OperationResult, val savedVersion: String? = null)
