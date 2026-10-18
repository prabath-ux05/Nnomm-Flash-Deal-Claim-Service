package com.nnomm.flashdeal.deals

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "deals")
class Deal(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    var title: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Column(name = "price_paise", nullable = false)
    var pricePaise: Long,

    @Column(name = "stock_total", nullable = false)
    var stockTotal: Int,

    @Column(name = "stock_available", nullable = false)
    var stockAvailable: Int,

    @Column(name = "starts_at", nullable = false)
    var startsAt: Instant,

    @Column(name = "ends_at", nullable = false)
    var endsAt: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Version
    @Column(nullable = false)
    var version: Long = 0
) {
    fun isLive(at: Instant = Instant.now()): Boolean {
        return !at.isBefore(startsAt) && !at.isAfter(endsAt)
    }
}
