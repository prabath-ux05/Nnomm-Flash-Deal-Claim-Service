package com.nnomm.flashdeal.deals

import java.time.Instant
import java.util.UUID

data class DealResponse(
    val id: UUID,
    val title: String,
    val description: String?,
    val pricePaise: Long,
    val stockTotal: Int,
    val stockAvailable: Int,
    val startsAt: Instant,
    val endsAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant
)
