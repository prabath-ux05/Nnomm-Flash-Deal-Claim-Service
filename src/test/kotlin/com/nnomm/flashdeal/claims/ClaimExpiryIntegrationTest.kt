package com.nnomm.flashdeal.claims

import com.fasterxml.jackson.databind.ObjectMapper
import com.nnomm.flashdeal.deals.Deal
import com.nnomm.flashdeal.deals.DealRepository
import com.nnomm.flashdeal.payments.PaymentEventRepository
import com.nnomm.flashdeal.payments.PaymentEventStatus
import com.nnomm.flashdeal.payments.PaymentSignatureValidator
import com.nnomm.flashdeal.payments.PaymentWebhookRequest
import com.nnomm.flashdeal.payments.PaymentWebhookService
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@AutoConfigureEmbeddedDatabase(
    type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES,
    provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY
)
@ActiveProfiles("test")
class ClaimExpiryIntegrationTest {

    @Autowired
    private lateinit var claimExpiryService: ClaimExpiryService

    @Autowired
    private lateinit var claimExpiryScheduler: ClaimExpiryScheduler

    @Autowired
    private lateinit var dealRepository: DealRepository

    @Autowired
    private lateinit var claimRepository: ClaimRepository

    @Autowired
    private lateinit var paymentEventRepository: PaymentEventRepository

    @Autowired
    private lateinit var paymentWebhookService: PaymentWebhookService

    @Autowired
    private lateinit var paymentSignatureValidator: PaymentSignatureValidator

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun cleanDatabase() {
        paymentEventRepository.deleteAll()
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
            title = "Flash Deal Expiry Test",
            description = "Special flash deal for expiry testing",
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
        customerId: String = "cust_${UUID.randomUUID().toString().take(8)}",
        quantity: Int = 2,
        amountPaise: Long = deal.pricePaise * 2,
        status: ClaimStatus = ClaimStatus.RESERVED,
        expiresInSeconds: Long = -10
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
            createdAt = now.minusSeconds(60),
            updatedAt = now.minusSeconds(60)
        )
        return claimRepository.save(claim)
    }

    @Test
    fun `1_Expired RESERVED claim restores stock`() {
        // Initial state: Deal has 10 total stock, 7 available (3 reserved for claim)
        val deal = createLiveDeal(stockTotal = 10, stockAvailable = 7)
        val claim = createClaim(
            deal = deal,
            quantity = 3,
            status = ClaimStatus.RESERVED,
            expiresInSeconds = -10
        )

        val processedCount = claimExpiryService.processExpiredClaims()
        assertEquals(1, processedCount)

        // Verify claim status transitioned: RESERVED -> EXPIRED
        val refreshedClaim = claimRepository.findById(claim.id).orElseThrow()
        assertEquals(ClaimStatus.EXPIRED, refreshedClaim.status)
        assertTrue(refreshedClaim.updatedAt.isAfter(refreshedClaim.createdAt))

        // Verify stock is restored atomically: 7 + 3 = 10
        val refreshedDeal = dealRepository.findById(deal.id).orElseThrow()
        assertEquals(10, refreshedDeal.stockAvailable)
    }

    @Test
    fun `2_Non-expired claim is untouched`() {
        // Initial state: Deal has 10 total stock, 7 available (3 reserved for claim expiring in 10 minutes)
        val deal = createLiveDeal(stockTotal = 10, stockAvailable = 7)
        val claim = createClaim(
            deal = deal,
            quantity = 3,
            status = ClaimStatus.RESERVED,
            expiresInSeconds = 600
        )

        val processedCount = claimExpiryService.processExpiredClaims()
        assertEquals(0, processedCount)

        // Verify claim remains RESERVED
        val refreshedClaim = claimRepository.findById(claim.id).orElseThrow()
        assertEquals(ClaimStatus.RESERVED, refreshedClaim.status)

        // Verify deal stock remains untouched at 7
        val refreshedDeal = dealRepository.findById(deal.id).orElseThrow()
        assertEquals(7, refreshedDeal.stockAvailable)
    }

    @Test
    fun `3_PAID claim is untouched`() {
        val deal = createLiveDeal(stockTotal = 10, stockAvailable = 7)

        // Create claims with past expires_at but in statuses PAID, NEEDS_REVIEW, and REFUND_PENDING
        val paidClaim = createClaim(
            deal = deal,
            customerId = "cust_paid",
            quantity = 1,
            status = ClaimStatus.PAID,
            expiresInSeconds = -30
        )
        val needsReviewClaim = createClaim(
            deal = deal,
            customerId = "cust_needs_review",
            quantity = 1,
            status = ClaimStatus.NEEDS_REVIEW,
            expiresInSeconds = -30
        )
        val refundPendingClaim = createClaim(
            deal = deal,
            customerId = "cust_refund_pending",
            quantity = 1,
            status = ClaimStatus.REFUND_PENDING,
            expiresInSeconds = -30
        )

        val processedCount = claimExpiryService.processExpiredClaims()
        assertEquals(0, processedCount)

        // Verify none of the claims were expired
        assertEquals(ClaimStatus.PAID, claimRepository.findById(paidClaim.id).orElseThrow().status)
        assertEquals(ClaimStatus.NEEDS_REVIEW, claimRepository.findById(needsReviewClaim.id).orElseThrow().status)
        assertEquals(ClaimStatus.REFUND_PENDING, claimRepository.findById(refundPendingClaim.id).orElseThrow().status)

        // Stock remains untouched
        val refreshedDeal = dealRepository.findById(deal.id).orElseThrow()
        assertEquals(7, refreshedDeal.stockAvailable)
    }

    @Test
    fun `4_Multiple expired claims restore correct quantities`() {
        val deal1 = createLiveDeal(stockTotal = 20, stockAvailable = 10)
        val deal2 = createLiveDeal(stockTotal = 15, stockAvailable = 8)

        // Deal 1 claims
        val claim1A = createClaim(deal = deal1, quantity = 2, status = ClaimStatus.RESERVED, expiresInSeconds = -20)
        val claim1B = createClaim(deal = deal1, quantity = 3, status = ClaimStatus.RESERVED, expiresInSeconds = -10)
        val claim1CActive = createClaim(deal = deal1, quantity = 1, status = ClaimStatus.RESERVED, expiresInSeconds = 600)

        // Deal 2 claims
        val claim2A = createClaim(deal = deal2, quantity = 3, status = ClaimStatus.RESERVED, expiresInSeconds = -15)
        val claim2B = createClaim(deal = deal2, quantity = 2, status = ClaimStatus.RESERVED, expiresInSeconds = -5)
        val claim2CPaid = createClaim(deal = deal2, quantity = 2, status = ClaimStatus.PAID, expiresInSeconds = -30)

        val processedCount = claimExpiryService.processExpiredClaims()
        // Exactly 4 expired RESERVED claims processed (1A, 1B, 2A, 2B)
        assertEquals(4, processedCount)

        // Deal 1: 10 + 2 + 3 = 15
        val refreshedDeal1 = dealRepository.findById(deal1.id).orElseThrow()
        assertEquals(15, refreshedDeal1.stockAvailable)

        // Deal 2: 8 + 3 + 2 = 13
        val refreshedDeal2 = dealRepository.findById(deal2.id).orElseThrow()
        assertEquals(13, refreshedDeal2.stockAvailable)


        // Claims states
        assertEquals(ClaimStatus.EXPIRED, claimRepository.findById(claim1A.id).orElseThrow().status)
        assertEquals(ClaimStatus.EXPIRED, claimRepository.findById(claim1B.id).orElseThrow().status)
        assertEquals(ClaimStatus.RESERVED, claimRepository.findById(claim1CActive.id).orElseThrow().status)

        assertEquals(ClaimStatus.EXPIRED, claimRepository.findById(claim2A.id).orElseThrow().status)
        assertEquals(ClaimStatus.EXPIRED, claimRepository.findById(claim2B.id).orElseThrow().status)
        assertEquals(ClaimStatus.PAID, claimRepository.findById(claim2CPaid.id).orElseThrow().status)
    }

    @Test
    fun `5_Two instances cannot expire the same claim twice`() {
        val deal = createLiveDeal(stockTotal = 10, stockAvailable = 7)
        val claim = createClaim(
            deal = deal,
            quantity = 3,
            status = ClaimStatus.RESERVED,
            expiresInSeconds = -10
        )

        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(1)
        val processedSum = AtomicInteger(0)

        val tasks = (1..threadCount).map {
            Callable {
                latch.await()
                val count = claimExpiryService.processExpiredBatch(batchSize = 10)
                processedSum.addAndGet(count)
            }
        }

        val futures = tasks.map { executor.submit(it) }
        latch.countDown() // Release all concurrent instances simultaneously
        futures.forEach { it.get() }
        executor.shutdown()

        // Across all 10 concurrent threads, the claim must be expired exactly once
        assertEquals(1, processedSum.get())

        val refreshedClaim = claimRepository.findById(claim.id).orElseThrow()
        assertEquals(ClaimStatus.EXPIRED, refreshedClaim.status)

        // Stock must be restored exactly once (7 + 3 = 10), never more
        val refreshedDeal = dealRepository.findById(deal.id).orElseThrow()
        assertEquals(10, refreshedDeal.stockAvailable)
    }

    @Test
    fun `6_Payment and expiry racing on the same claim leaves exactly one valid final state with correct inventory`() {
        val trials = 10
        val executor = Executors.newFixedThreadPool(4)

        for (trial in 1..trials) {
            val deal = createLiveDeal(stockTotal = 10, stockAvailable = 8, pricePaise = 50000)
            val claim = createClaim(
                deal = deal,
                customerId = "cust_race_$trial",
                quantity = 2,
                amountPaise = 100000,
                status = ClaimStatus.RESERVED,
                expiresInSeconds = -1
            )

            val eventId = "evt_race_${UUID.randomUUID()}"
            val payload = PaymentWebhookRequest(
                eventId = eventId,
                claimId = claim.id,
                amountPaise = 100000
            )
            val rawBytes = objectMapper.writeValueAsBytes(payload)
            val signature = paymentSignatureValidator.calculateSignature(rawBytes)

            val latch = CountDownLatch(1)

            val paymentTask = Callable {
                latch.await()
                try {
                    paymentWebhookService.processWebhook(rawBytes, signature)
                    "PAID"
                } catch (e: Exception) {
                    "PAYMENT_REJECTED: ${e.message}"
                }
            }

            val expiryTask = Callable {
                latch.await()
                try {
                    val count = claimExpiryService.processExpiredBatch(batchSize = 10)
                    "EXPIRY_PROCESSED_$count"
                } catch (e: Exception) {
                    "EXPIRY_FAILED: ${e.message}"
                }
            }

            val paymentFuture = executor.submit(paymentTask)
            val expiryFuture = executor.submit(expiryTask)

            latch.countDown()

            val paymentResult = paymentFuture.get()
            expiryFuture.get()

            val refreshedClaim = claimRepository.findById(claim.id).orElseThrow()
            val refreshedDeal = dealRepository.findById(deal.id).orElseThrow()

            // Verify: exactly one valid final state
            when (refreshedClaim.status) {
                ClaimStatus.PAID -> {
                    // Payment won the race: claim is PAID, inventory remains 8 (not restored)
                    assertEquals(8, refreshedDeal.stockAvailable, "When PAID, stock must remain decremented at 8")
                    val event = paymentEventRepository.findByEventId(eventId).orElseThrow()
                    assertEquals(PaymentEventStatus.PROCESSED, event.status)
                }
                ClaimStatus.EXPIRED -> {
                    // Expiry won the race: claim is EXPIRED, inventory restored to 10
                    assertEquals(10, refreshedDeal.stockAvailable, "When EXPIRED, stock must be restored to 10")
                    assertTrue(
                        paymentResult.contains("PAYMENT_REJECTED") || paymentResult.contains("INVALID_CLAIM_STATE"),
                        "Payment must be rejected when claim is EXPIRED"
                    )
                }
                else -> {
                    fail("Unexpected claim status after race: ${refreshedClaim.status}")
                }
            }
        }

        executor.shutdown()
    }

    @Test
    fun `7_Expired claims must still be processed after an application restart`() {
        // Simulate overdue claims that expired while the application was offline/restarting
        val deal = createLiveDeal(stockTotal = 10, stockAvailable = 6)
        val claim1 = createClaim(deal = deal, quantity = 2, status = ClaimStatus.RESERVED, expiresInSeconds = -100)
        val claim2 = createClaim(deal = deal, quantity = 2, status = ClaimStatus.RESERVED, expiresInSeconds = -50)

        // The ClaimExpiryScheduler triggers immediately on application startup (initialDelay = 0)
        claimExpiryScheduler.processExpiredClaims()

        val refreshedClaim1 = claimRepository.findById(claim1.id).orElseThrow()
        val refreshedClaim2 = claimRepository.findById(claim2.id).orElseThrow()

        assertEquals(ClaimStatus.EXPIRED, refreshedClaim1.status)
        assertEquals(ClaimStatus.EXPIRED, refreshedClaim2.status)

        val refreshedDeal = dealRepository.findById(deal.id).orElseThrow()
        assertEquals(10, refreshedDeal.stockAvailable)
    }
}
