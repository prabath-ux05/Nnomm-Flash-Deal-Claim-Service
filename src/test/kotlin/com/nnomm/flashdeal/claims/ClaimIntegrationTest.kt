package com.nnomm.flashdeal.claims

import com.fasterxml.jackson.databind.ObjectMapper
import com.nnomm.flashdeal.deals.Deal
import com.nnomm.flashdeal.deals.DealRepository
import com.nnomm.flashdeal.security.JwtService
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
class ClaimIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dealRepository: DealRepository

    @Autowired
    private lateinit var claimRepository: ClaimRepository

    @Autowired
    private lateinit var idempotencyRepository: com.nnomm.flashdeal.common.idempotency.IdempotencyRepository

    @Autowired
    private lateinit var paymentEventRepository: com.nnomm.flashdeal.payments.PaymentEventRepository

    @Autowired
    private lateinit var jwtService: JwtService

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
        pricePaise: Long = 49900
    ): Deal {
        val now = Instant.now()
        val deal = Deal(
            id = UUID.randomUUID(),
            title = "Special Flash Deal",
            description = "Limited offer deal",
            pricePaise = pricePaise,
            stockTotal = stockTotal,
            stockAvailable = stockAvailable,
            startsAt = now.minus(1, ChronoUnit.HOURS),
            endsAt = now.plus(1, ChronoUnit.HOURS)
        )
        return dealRepository.save(deal)
    }

    @Test
    fun `1_Successful claim reserves stock and returns 201 Created`() {
        val deal = createLiveDeal(stockTotal = 10, stockAvailable = 10, pricePaise = 50000)
        val customerId = "customer_1"
        val token = jwtService.generateToken(customerId)
        val idempotencyKey = "key-success-1"

        mockMvc.perform(
            post("/v1/deals/${deal.id}/claims")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 2)))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isNotEmpty)
            .andExpect(jsonPath("$.dealId").value(deal.id.toString()))
            .andExpect(jsonPath("$.customerId").value(customerId))
            .andExpect(jsonPath("$.quantity").value(2))
            .andExpect(jsonPath("$.amountPaise").value(100000))
            .andExpect(jsonPath("$.status").value("RESERVED"))
            .andExpect(jsonPath("$.expiresAt").isNotEmpty)

        val updatedDeal = dealRepository.findById(deal.id).get()
        assertEquals(8, updatedDeal.stockAvailable)
    }

    @Test
    fun `2_Sold out returns 409 Conflict when available stock is insufficient`() {
        val deal = createLiveDeal(stockTotal = 5, stockAvailable = 1)
        val customerId = "customer_soldout"
        val token = jwtService.generateToken(customerId)

        mockMvc.perform(
            post("/v1/deals/${deal.id}/claims")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", "key-sold-out")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 2)))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SOLD_OUT"))

        val refreshedDeal = dealRepository.findById(deal.id).get()
        assertEquals(1, refreshedDeal.stockAvailable)
    }

    @Test
    fun `3_Quantity validation rejects quantities not between 1 and 3`() {
        val deal = createLiveDeal(stockTotal = 10, stockAvailable = 10)
        val token = jwtService.generateToken("customer_qty")

        // Quantity 0
        mockMvc.perform(
            post("/v1/deals/${deal.id}/claims")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", "key-qty-0")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 0)))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"))

        // Quantity 4
        mockMvc.perform(
            post("/v1/deals/${deal.id}/claims")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", "key-qty-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 4)))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"))
    }

    @Test
    fun `4_Deal not live returns 400 Bad Request`() {
        val now = Instant.now()
        val expiredDeal = dealRepository.save(
            Deal(
                id = UUID.randomUUID(),
                title = "Expired Deal",
                pricePaise = 10000,
                stockTotal = 5,
                stockAvailable = 5,
                startsAt = now.minus(2, ChronoUnit.HOURS),
                endsAt = now.minus(1, ChronoUnit.HOURS)
            )
        )

        val token = jwtService.generateToken("customer_not_live")

        mockMvc.perform(
            post("/v1/deals/${expiredDeal.id}/claims")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", "key-expired")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 1)))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("DEAL_NOT_LIVE"))
    }

    @Test
    fun `5_Same customer and same idempotency key replays original result without double decrement`() {
        val deal = createLiveDeal(stockTotal = 10, stockAvailable = 10)
        val customerId = "customer_idempotent"
        val token = jwtService.generateToken(customerId)
        val key = "key-idempotency-test"

        // First call
        val firstResult = mockMvc.perform(
            post("/v1/deals/${deal.id}/claims")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 2)))
        )
            .andExpect(status().isCreated)
            .andReturn()

        val firstResponse = objectMapper.readValue(firstResult.response.contentAsString, ClaimResponse::class.java)

        // Second call with identical customer, key, and request
        val secondResult = mockMvc.perform(
            post("/v1/deals/${deal.id}/claims")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 2)))
        )
            .andExpect(status().isCreated)
            .andReturn()

        val secondResponse = objectMapper.readValue(secondResult.response.contentAsString, ClaimResponse::class.java)

        // Verified: exactly the same claim ID replayed
        assertEquals(firstResponse.id, secondResponse.id)

        // Verified: stock only decremented once (10 - 2 = 8)
        val refreshedDeal = dealRepository.findById(deal.id).get()
        assertEquals(8, refreshedDeal.stockAvailable)
    }

    @Test
    fun `6_Same key with different request returns IDEMPOTENCY_KEY_REUSED`() {
        val deal1 = createLiveDeal(stockTotal = 10, stockAvailable = 10)
        val customerId = "customer_reuse"
        val token = jwtService.generateToken(customerId)
        val key = "reused-key"

        // Initial claim for quantity 1
        mockMvc.perform(
            post("/v1/deals/${deal1.id}/claims")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 1)))
        )
            .andExpect(status().isCreated)

        // Subsequent claim using same key with different quantity (2 instead of 1)
        mockMvc.perform(
            post("/v1/deals/${deal1.id}/claims")
                .header("Authorization", "Bearer $token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 2)))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
    }

    @Test
    fun `7_Concurrent customers claiming the last stock never oversell`() {
        val stockAvailable = 5
        val deal = createLiveDeal(stockTotal = 10, stockAvailable = stockAvailable)
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(1)

        val successCount = AtomicInteger(0)
        val soldOutCount = AtomicInteger(0)

        val tasks = (1..threadCount).map { i ->
            Callable {
                latch.await()
                val customerId = "cust_concurrent_$i"
                val token = jwtService.generateToken(customerId)
                val key = "key_concurrent_$i"

                val result = mockMvc.perform(
                    post("/v1/deals/${deal.id}/claims")
                        .header("Authorization", "Bearer $token")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 1)))
                ).andReturn()

                val status = result.response.status
                if (status == 201) {
                    successCount.incrementAndGet()
                } else if (status == 409) {
                    soldOutCount.incrementAndGet()
                }
                Unit
            }
        }

        val futures = tasks.map { executor.submit(it) }
        latch.countDown() // Release all threads simultaneously
        futures.forEach { it.get() }
        executor.shutdown()

        // Exactly 5 claims must succeed, and 5 must receive SOLD_OUT
        assertEquals(5, successCount.get())
        assertEquals(5, soldOutCount.get())

        // Stock available must be exactly 0, never negative
        val updatedDeal = dealRepository.findById(deal.id).get()
        assertEquals(0, updatedDeal.stockAvailable)
    }

    @Test
    fun `8_Same customer making concurrent claims with different keys enforces one active claim`() {
        val deal = createLiveDeal(stockTotal = 20, stockAvailable = 20)
        val customerId = "customer_multi_attempt"
        val token = jwtService.generateToken(customerId)

        val threadCount = 5
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(1)

        val successCount = AtomicInteger(0)
        val activeClaimRejectedCount = AtomicInteger(0)

        val tasks = (1..threadCount).map { i ->
            Callable {
                latch.await()
                val key = "unique-key-$i"

                val result = mockMvc.perform(
                    post("/v1/deals/${deal.id}/claims")
                        .header("Authorization", "Bearer $token")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ClaimRequest(quantity = 1)))
                ).andReturn()

                val status = result.response.status
                if (status == 201) {
                    successCount.incrementAndGet()
                } else if (status == 409) {
                    activeClaimRejectedCount.incrementAndGet()
                }
                Unit
            }
        }

        val futures = tasks.map { executor.submit(it) }
        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        // Exactly 1 claim must succeed, and 4 must be rejected
        assertEquals(1, successCount.get())
        assertEquals(4, activeClaimRejectedCount.get())

        // Database must contain only 1 claim for this customer
        val claims = claimRepository.findAll().filter { it.customerId == customerId }
        assertEquals(1, claims.size)
    }
}
