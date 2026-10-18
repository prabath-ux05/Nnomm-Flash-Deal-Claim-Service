package com.nnomm.flashdeal.claims

import java.time.Instant
import java.util.UUID

data class ClaimRequest(
    val quantity: Int? = null
)

data class ClaimResponse(
    val id: UUID,
    val dealId: UUID,
    val customerId: String,
    val quantity: Int,
    val amountPaise: Long,
    val status: ClaimStatus,
    val expiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt
)

