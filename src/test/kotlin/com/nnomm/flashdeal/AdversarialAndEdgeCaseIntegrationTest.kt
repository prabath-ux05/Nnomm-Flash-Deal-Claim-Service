package com.nnomm.flashdeal

import com.fasterxml.jackson.databind.ObjectMapper
import com.nnomm.flashdeal.claims.Claim
import com.nnomm.flashdeal.claims.ClaimRepository
import com.nnomm.flashdeal.claims.ClaimRequest
import com.nnomm.flashdeal.claims.ClaimResponse
import com.nnomm.flashdeal.claims.ClaimStatus
import com.nnomm.flashdeal.common.idempotency.IdempotencyRecord
import com.nnomm.flashdeal.common.idempotency.IdempotencyRepository
import com.nnomm.flashdeal.common.idempotency.IdempotencyStatus
import com.nnomm.flashdeal.deals.Deal
import com.nnomm.flashdeal.deals.DealRepository
import com.nnomm.flashdeal.payments.PaymentEventRepository
import com.nnomm.flashdeal.payments.PaymentSignatureValidator
import com.nnomm.flashdeal.payments.PaymentWebhookRequest
import com.nnomm.flashdeal.security.JwtService
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
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
class AdversarialAndEdgeCaseIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dealRepository: DealRepository

    @Autowired
    private lateinit var claimRepository: ClaimRepository

    @Autowired
    private lateinit var idempotencyRepository: IdempotencyRepository

    @Autowired
    private lateinit var paymentEventRepository: PaymentEventRepository

    @Autowired
    private lateinit var jwtService: JwtService

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
        stockAvailable: Int = 10,
        pricePaise: Long = 50000
    ): Deal {
        val now = Instant.now()
        val deal = Deal(
            id = UUID.randomUUID(),
            title = "Adversarial Test Deal",
            description = "High-load deal",
            pricePaise = pricePaise,
            stockTotal = stockTotal,
            stockAvailable = stockAvailable,
            startsAt = now.minus(1, ChronoUnit.HOURS),
            endsAt = now.plus(1, ChronoUnit.HOURS)
        )
        return dealRepository.save(deal)
    }

    @Test
    fun `1_Concurrent duplicate idempotency requests for the same customer never double-reserve stock`() {
        val deal = createLiveDeal(stockTotal = 10, stockAvailable = 10)
        val customerId = "cust_concurrent_idem"
        val token = jwtService.generateToken(customerId)
        val identicalKey = "idem-key-duplicate-burst"

        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(1)

        val createdCount = AtomicInteger(0)
        val inProgressCount = AtomicInteger(0)

        val tasks = (1..threadCount).map {
            Callable {
                latch.await()
                val result = mockMvc.perform(
                    post("/v1/deals/${deal.id}/claims")
                        .header("Authorization", "Bearer $token")
                        .header("Idempotency-Key", identicalKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 2)))
                ).andReturn()

                val status = result.response.status
                if (status == 201) {
                    createdCount.incrementAndGet()
                } else if (status == 409) {
                    inProgressCount.incrementAndGet()
                }
                Unit
            }
        }


        val futures = tasks.map { executor.submit(it) }
        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        // At least one request succeeded with 201 Created
        assertTrue(createdCount.get() >= 1, "At least 1 request must succeed with 201 Created")
        // All requests returned either 201 Created or 409 Conflict (REQUEST_IN_PROGRESS)
        assertEquals(threadCount, createdCount.get() + inProgressCount.get())

        // Database invariants: exactly 1 claim in DB for this customer
        val claims = claimRepository.findAll().filter { it.customerId == customerId }
        assertEquals(1, claims.size)
        assertEquals(2, claims[0].quantity)

        // Deal stock must be decremented by quantity 2 exactly once (10 - 2 = 8), never double-decremented
        val refreshedDeal = dealRepository.findById(deal.id).orElseThrow()
        assertEquals(8, refreshedDeal.stockAvailable)
    }

    @Test
    fun `2_Customer whose previous claim is EXPIRED can claim the deal again`() {
        val deal = createLiveDeal(stockTotal = 10, stockAvailable = 10)
        val customerId = "cust_reclaim_after_expire"
        val token = jwtService.generateToken(customerId)

        // 1. First claim
        val firstResult = mockMvc.perform(
            post("/v1/deals/${deal.id}/claims")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", "key-first-attempt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 2)))
        )
            .andExpect(status().isCreated)
            .andReturn()

        val firstClaimResponse = objectMapper.readValue(firstResult.response.contentAsString, ClaimResponse::class.java)
        val firstClaim = claimRepository.findById(firstClaimResponse.id).orElseThrow()

        // Simulate claim expiry
        firstClaim.status = ClaimStatus.EXPIRED
        firstClaim.updatedAt = Instant.now()
        claimRepository.save(firstClaim)

        // Restore stock
        val currentDeal = dealRepository.findById(deal.id).orElseThrow()
        currentDeal.stockAvailable = 10
        dealRepository.save(currentDeal)

        // 2. Customer submits a new claim with a new idempotency key
        mockMvc.perform(
            post("/v1/deals/${deal.id}/claims")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", "key-second-attempt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 3)))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.quantity").value(3))
            .andExpect(jsonPath("$.status").value("RESERVED"))

        // Customer now has 2 claims in total: 1 EXPIRED, 1 RESERVED
        val customerClaims = claimRepository.findAll().filter { it.customerId == customerId }
        assertEquals(2, customerClaims.size)

        // Deal stock updated to 10 - 3 = 7
        val refreshedDeal = dealRepository.findById(deal.id).orElseThrow()
        assertEquals(7, refreshedDeal.stockAvailable)
    }

    @Test
    fun `3_Idempotency crash recovery resumes expired IN_PROGRESS request`() {
        val deal = createLiveDeal(stockTotal = 10, stockAvailable = 10)
        val customerId = "cust_crash_recovery"
        val token = jwtService.generateToken(customerId)
        val idempotencyKey = "key-crashed-request"

        // Simulate a crashed request stuck in IN_PROGRESS whose recovery_deadline has passed
        val pastTime = Instant.now().minusSeconds(120)
        val stuckRecord = IdempotencyRecord(
            key = idempotencyKey,
            customerId = customerId,
            dealId = deal.id,
            quantity = 2,
            status = IdempotencyStatus.IN_PROGRESS,
            lockedAt = pastTime,
            recoveryDeadline = pastTime.plusSeconds(60), // Expired 60s ago
            createdAt = pastTime,
            updatedAt = pastTime
        )
        idempotencyRepository.saveAndFlush(stuckRecord)

        // Customer retries the same request
        mockMvc.perform(
            post("/v1/deals/${deal.id}/claims")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 2)))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.quantity").value(2))
            .andExpect(jsonPath("$.status").value("RESERVED"))

        // Record is now COMPLETED
        val completedRecord = idempotencyRepository.findByCustomerIdAndKey(customerId, idempotencyKey).orElseThrow()
        assertEquals(IdempotencyStatus.COMPLETED, completedRecord.status)
        assertEquals(201, completedRecord.httpStatus)


        // Stock was correctly decremented by 2
        val refreshedDeal = dealRepository.findById(deal.id).orElseThrow()
        assertEquals(8, refreshedDeal.stockAvailable)
    }

    @Test
    fun `4_Out-of-order webhook arriving for a NEEDS_REVIEW claim rejects invalid state transition`() {
        val deal = createLiveDeal(pricePaise = 50000)
        val claim = Claim(
            id = UUID.randomUUID(),
            dealId = deal.id,
            customerId = "cust_needs_review_test",
            quantity = 2,
            amountPaise = 100000,
            status = ClaimStatus.NEEDS_REVIEW,
            expiresAt = Instant.now().plusSeconds(600),
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        claimRepository.save(claim)

        val payload = PaymentWebhookRequest(
            eventId = "evt_out_of_order_1",
            claimId = claim.id,
            amountPaise = 100000
        )
        val rawBytes = objectMapper.writeValueAsBytes(payload)
        val signature = paymentSignatureValidator.calculateSignature(rawBytes)

        mockMvc.perform(
            post("/v1/payments/webhook")
                .header("X-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBytes)
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("INVALID_CLAIM_STATE"))

        // Claim remains in NEEDS_REVIEW
        val refreshedClaim = claimRepository.findById(claim.id).orElseThrow()
        assertEquals(ClaimStatus.NEEDS_REVIEW, refreshedClaim.status)
    }

    @Test
    fun `5_Webhook with invalid or non-positive amount is rejected with 400 Bad Request`() {
        val deal = createLiveDeal(pricePaise = 50000)
        val claim = Claim(
            id = UUID.randomUUID(),
            dealId = deal.id,
            customerId = "cust_invalid_amount",
            quantity = 1,
            amountPaise = 50000,
            status = ClaimStatus.RESERVED,
            expiresAt = Instant.now().plusSeconds(600),
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        claimRepository.save(claim)

        // Negative amount
        val payloadNegative = PaymentWebhookRequest(
            eventId = "evt_neg_amount",
            claimId = claim.id,
            amountPaise = -500
        )
        val rawBytes = objectMapper.writeValueAsBytes(payloadNegative)
        val signature = paymentSignatureValidator.calculateSignature(rawBytes)

        mockMvc.perform(
            post("/v1/payments/webhook")
                .header("X-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBytes)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_PAYLOAD"))

        // Zero amount
        val payloadZero = PaymentWebhookRequest(
            eventId = "evt_zero_amount",
            claimId = claim.id,
            amountPaise = 0
        )
        val rawBytesZero = objectMapper.writeValueAsBytes(payloadZero)
        val signatureZero = paymentSignatureValidator.calculateSignature(rawBytesZero)

        mockMvc.perform(
            post("/v1/payments/webhook")
                .header("X-Signature", signatureZero)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBytesZero)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_PAYLOAD"))
    }
}
