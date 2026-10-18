package com.nnomm.flashdeal.payments

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional
import java.util.UUID

@Repository
interface PaymentEventRepository : JpaRepository<PaymentEvent, UUID> {
    fun findByEventId(eventId: String): Optional<PaymentEvent>
    fun existsByEventId(eventId: String): Boolean
}
