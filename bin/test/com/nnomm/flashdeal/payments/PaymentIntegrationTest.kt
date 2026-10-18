package com.nnomm.flashdeal.payments

import com.fasterxml.jackson.databind.ObjectMapper
import com.nnomm.flashdeal.claims.Claim
import com.nnomm.flashdeal.claims.ClaimRepository
import com.nnomm.flashdeal.claims.ClaimStatus
import com.nnomm.flashdeal.common.idempotency.IdempotencyRepository
import com.nnomm.flashdeal.deals.Deal
import com.nnomm.flashdeal.deals.DealRepository
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(
    type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES,
    provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY
)
@ActiveProfiles("test")
class PaymentIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dealRepository: DealRepository

    @Autowired
    private lateinit var claimRepository: ClaimRepository

    @Autowired
    private lateinit var paymentEventRepository: PaymentEventRepository

    @Autowired
    private lateinit var idempotencyRepository: IdempotencyRepository

    @Autowired
    private lateinit var paymentSignatureValidator: PaymentSignatureValidator

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun cleanDatabase() {
        paymentEventRepository.deleteAll()
        idempotencyRepository.deleteAll()
        claimRepository.deleteAll()
        dealRepository.deleteAll()
    }

    private fun createLiveDeal(
        stockTotal: Int = 10,
        stockAvailable: Int = 8,
        pricePaise: Long = 49900
    ): Deal {
        val now = Instant.now()
        val deal = Deal(
            id = UUID.randomUUID(),
            title = "Flash Deal Test",
            description = "Special Deal for payments",
            pricePaise = pricePaise,
            stockTotal = stockTotal,
            stockAvailable = stockAvailable,
            startsAt = now.minus(1, ChronoUnit.HOURS),
            endsAt = now.plus(1, ChronoUnit.HOURS)
        )
        return dealRepository.save(deal)
    }

    private fun createClaim(
        deal: Deal,
        customerId: String = "cust_test_1",
        quantity: Int = 2,
        amountPaise: Long = 99800,
        status: ClaimStatus = ClaimStatus.RESERVED,
        expiresInSeconds: Long = 600
    ): Claim {
        val now = Instant.now()
        val claim = Claim(
            id = UUID.randomUUID(),
            dealId = deal.id,
            customerId = customerId,
            quantity = quantity,
            amountPaise = amountPaise,
            status = status,
            expiresAt = now.plusSeconds(expiresInSeconds),
            createdAt = now,
            updatedAt = now
        )
        return claimRepository.save(claim)
    }

    private fun sendSignedWebhook(
        payload: PaymentWebhookRequest,
        customSignature: String? = null
    ): org.springframework.test.web.servlet.ResultActions {
        val rawBytes = objectMapper.writeValueAsBytes(payload)
        val signature = customSignature ?: paymentSignatureValidator.calculateSignature(rawBytes)

        val requestBuilder = post("/v1/payments/webhook")
            .contentType(MediaType.APPLICATION_JSON)
            .content(rawBytes)

        if (signature.isNotEmpty()) {
            requestBuilder.header("X-Signature", signature)
        }

        return mockMvc.perform(requestBuilder)
    }

    @Test
    fun `1_Valid payment transitions claim from RESERVED to PAID`() {
        val deal = createLiveDeal(pricePaise = 50000)
        val claim = createClaim(deal = deal, quantity = 2, amountPaise = 100000, status = ClaimStatus.RESERVED)
        val eventId = "evt_valid_1"

        val payload = PaymentWebhookRequest(
            eventId = eventId,
            claimId = claim.id,
            amountPaise = 100000
        )

        sendSignedWebhook(payload)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eventId").value(eventId))
            .andExpect(jsonPath("$.claimId").value(claim.id.toString()))
            .andExpect(jsonPath("$.status").value("PAID"))

        // Verify DB updates
        val updatedClaim = claimRepository.findById(claim.id).orElseThrow()
        assertEquals(ClaimStatus.PAID, updatedClaim.status)

        val paymentEvent = paymentEventRepository.findByEventId(eventId).orElseThrow()
        assertEquals(PaymentEventStatus.PROCESSED, paymentEvent.status)
        assertEquals(100000L, paymentEvent.amountPaise)
        assertEquals(claim.id, paymentEvent.claimId)
    }

    @Test
    fun `2_Invalid or missing signature returns 401 Unauthorized`() {
        val deal = createLiveDeal()
        val claim = createClaim(deal = deal, status = ClaimStatus.RESERVED)
        val eventId = "evt_invalid_sig_1"

        val payload = PaymentWebhookRequest(
            eventId = eventId,
            claimId = claim.id,
            amountPaise = claim.amountPaise
        )

        // Test with invalid signature
        sendSignedWebhook(payload, customSignature = "wrong_or_tampered_signature")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))

        // Test with missing signature header
        val rawBytes = objectMapper.writeValueAsBytes(payload)
        mockMvc.perform(
            post("/v1/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBytes)
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))

        // Assert no payment event was persisted
        assertTrue(paymentEventRepository.findByEventId(eventId).isEmpty)

        // Assert claim remained RESERVED
        val freshClaim = claimRepository.findById(claim.id).orElseThrow()
        assertEquals(ClaimStatus.RESERVED, freshClaim.status)
    }

    @Test
    fun `3_Unknown claim returns 404 Not Found`() {
        val nonExistentClaimId = UUID.randomUUID()
        val eventId = "evt_unknown_claim_1"

        val payload = PaymentWebhookRequest(
            eventId = eventId,
            claimId = nonExistentClaimId,
            amountPaise = 50000
        )

        sendSignedWebhook(payload)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"))
    }

    @Test
    fun `4_Amount mismatch transitions claim to NEEDS_REVIEW`() {
        val deal = createLiveDeal(pricePaise = 50000)
        val claim = createClaim(deal = deal, quantity = 2, amountPaise = 100000, status = ClaimStatus.RESERVED)
        val eventId = "evt_mismatch_1"

        // Webhook sends 80000 instead of expected 100000
        val payload = PaymentWebhookRequest(
            eventId = eventId,
            claimId = claim.id,
            amountPaise = 80000
        )

        sendSignedWebhook(payload)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eventId").value(eventId))
            .andExpect(jsonPath("$.claimId").value(claim.id.toString()))
            .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))

        // Verify claim status in DB is NEEDS_REVIEW
        val updatedClaim = claimRepository.findById(claim.id).orElseThrow()
        assertEquals(ClaimStatus.NEEDS_REVIEW, updatedClaim.status)

        // Verify payment event recorded with error message
        val paymentEvent = paymentEventRepository.findByEventId(eventId).orElseThrow()
        assertEquals(PaymentEventStatus.PROCESSED, paymentEvent.status)
        assertNotNull(paymentEvent.errorMessage)
        assertTrue(paymentEvent.errorMessage!!.contains("mismatch"))
    }

    @Test
    fun `5_Duplicate webhook is safely ignored without double-processing`() {
        val deal = createLiveDeal(pricePaise = 50000)
        val claim = createClaim(deal = deal, quantity = 1, amountPaise = 50000, status = ClaimStatus.RESERVED)
        val eventId = "evt_duplicate_1"

        val payload = PaymentWebhookRequest(
            eventId = eventId,
            claimId = claim.id,
            amountPaise = 50000
        )

        // First delivery: processes payment
        sendSignedWebhook(payload)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PAID"))

        val claimAfterFirst = claimRepository.findById(claim.id).orElseThrow()
        assertEquals(ClaimStatus.PAID, claimAfterFirst.status)
        val eventCountAfterFirst = paymentEventRepository.count()

        // Second delivery with identical event_id: safely ignored/replayed
        sendSignedWebhook(payload)
            .andExpect(status().isOk)

        // Claim still PAID, no extra payment events recorded
        val claimAfterSecond = claimRepository.findById(claim.id).orElseThrow()
        assertEquals(ClaimStatus.PAID, claimAfterSecond.status)
        assertEquals(eventCountAfterFirst, paymentEventRepository.count())
    }

    @Test
    fun `6_Concurrent duplicate webhook deliveries across instances handled safely`() {
        val deal = createLiveDeal(pricePaise = 40000)
        val claim = createClaim(deal = deal, quantity = 2, amountPaise = 80000, status = ClaimStatus.RESERVED)
        val eventId = "evt_concurrent_dup_1"

        val payload = PaymentWebhookRequest(
            eventId = eventId,
            claimId = claim.id,
            amountPaise = 80000
        )

        val rawBytes = objectMapper.writeValueAsBytes(payload)
        val signature = paymentSignatureValidator.calculateSignature(rawBytes)

        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val successCount = AtomicInteger(0)

        val tasks = (1..threadCount).map {
            Callable {
                startLatch.await()
                try {
                    val result = mockMvc.perform(
                        post("/v1/payments/webhook")
                            .header("X-Signature", signature)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(rawBytes)
                    ).andReturn()

                    if (result.response.status == 200) {
                        successCount.incrementAndGet()
                    }
                } catch (e: Exception) {
                    // Ignored or logged
                }
            }
        }

        val futures = tasks.map { executor.submit(it) }
        startLatch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        // All concurrent requests must return 200 OK (one processes, remainder are safely deduplicated)
        assertEquals(threadCount, successCount.get())

        // Database checks: exactly 1 payment event, claim is PAID
        val updatedClaim = claimRepository.findById(claim.id).orElseThrow()
        assertEquals(ClaimStatus.PAID, updatedClaim.status)

        val paymentEvents = paymentEventRepository.findAll().filter { it.eventId == eventId }
        assertEquals(1, paymentEvents.size)
    }

    @Test
    fun `7_Webhook for an already PAID claim rejects invalid state transition`() {
        val deal = createLiveDeal(pricePaise = 50000)
        // Claim is already in status PAID
        val claim = createClaim(deal = deal, quantity = 1, amountPaise = 50000, status = ClaimStatus.PAID)
        val eventId = "evt_for_paid_claim_1"

        val payload = PaymentWebhookRequest(
            eventId = eventId,
            claimId = claim.id,
            amountPaise = 50000
        )

        sendSignedWebhook(payload)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("INVALID_CLAIM_STATE"))

        // Claim remains in status PAID (no invalid transition)
        val freshClaim = claimRepository.findById(claim.id).orElseThrow()
        assertEquals(ClaimStatus.PAID, freshClaim.status)
    }

    @Test
    fun `8_Webhook for an EXPIRED claim rejects invalid state transition`() {
        val deal = createLiveDeal(pricePaise = 50000)
        // Claim is in status EXPIRED
        val claim = createClaim(deal = deal, quantity = 1, amountPaise = 50000, status = ClaimStatus.EXPIRED)
        val eventId = "evt_for_expired_claim_1"

        val payload = PaymentWebhookRequest(
            eventId = eventId,
            claimId = claim.id,
            amountPaise = 50000
        )

        sendSignedWebhook(payload)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("INVALID_CLAIM_STATE"))

        // Claim remains in status EXPIRED (no invalid transition to PAID)
        val freshClaim = claimRepository.findById(claim.id).orElseThrow()
        assertEquals(ClaimStatus.EXPIRED, freshClaim.status)
    }
}
