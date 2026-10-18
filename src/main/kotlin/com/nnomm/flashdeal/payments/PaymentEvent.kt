package com.nnomm.flashdeal.payments

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "payment_events")
class PaymentEvent(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "event_id", nullable = false, unique = true)
    val eventId: String,

    @Column(name = "claim_id")
    val claimId: UUID?,

    @Column(name = "amount_paise")
    val amountPaise: Long?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentEventStatus,

    @Column(nullable = false, columnDefinition = "TEXT")
    val payload: String,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "processed_at")
    var processedAt: Instant? = null
)

enum class PaymentEventStatus {
    RECEIVED,
    PROCESSING,
    PROCESSED,
    REJECTED,
    FAILED
}
