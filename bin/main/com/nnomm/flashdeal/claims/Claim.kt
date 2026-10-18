package com.nnomm.flashdeal.claims

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "claims")
class Claim(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "deal_id", nullable = false)
    val dealId: UUID,

    @Column(name = "customer_id", nullable = false)
    val customerId: String,

    @Column(nullable = false)
    val quantity: Int,

    @Column(name = "amount_paise", nullable = false)
    val amountPaise: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ClaimStatus = ClaimStatus.RESERVED,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Version
    @Column(nullable = false)
    var version: Long = 0
)
