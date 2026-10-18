package com.nnomm.flashdeal.common.idempotency

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "idempotency_records")
class IdempotencyRecord(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val key: String,

    @Column(name = "customer_id", nullable = false)
    val customerId: String,

    @Column(name = "deal_id", nullable = false)
    val dealId: UUID,

    @Column(nullable = false)
    val quantity: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: IdempotencyStatus = IdempotencyStatus.IN_PROGRESS,

    @Column(name = "http_status")
    var httpStatus: Int? = null,

    @Column(name = "response_body", columnDefinition = "TEXT")
    var responseBody: String? = null,

    @Column(name = "claim_id")
    var claimId: UUID? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "locked_at", nullable = false)
    var lockedAt: Instant = Instant.now(),

    @Column(name = "recovery_deadline", nullable = false)
    var recoveryDeadline: Instant = Instant.now().plusSeconds(60)
)
