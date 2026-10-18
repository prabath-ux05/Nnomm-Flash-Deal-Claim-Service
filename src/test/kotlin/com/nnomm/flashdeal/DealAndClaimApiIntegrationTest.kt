package com.nnomm.flashdeal

import com.nnomm.flashdeal.claims.Claim
import com.nnomm.flashdeal.claims.ClaimRepository
import com.nnomm.flashdeal.claims.ClaimStatus
import com.nnomm.flashdeal.deals.Deal
import com.nnomm.flashdeal.deals.DealRepository
import com.nnomm.flashdeal.security.JwtService
import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(
    type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES,
    provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY
)
@ActiveProfiles("test")
class DealAndClaimApiIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dealRepository: DealRepository

    @Autowired
    private lateinit var claimRepository: ClaimRepository

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var paymentEventRepository: com.nnomm.flashdeal.payments.PaymentEventRepository

    @Autowired
    private lateinit var idempotencyRepository: com.nnomm.flashdeal.common.idempotency.IdempotencyRepository

    @BeforeEach
    fun cleanDatabase() {
        paymentEventRepository.deleteAll()
        idempotencyRepository.deleteAll()
        claimRepository.deleteAll()
        dealRepository.deleteAll()
    }


    private fun createLiveDeal(
        stockTotal: Int = 25,
        stockAvailable: Int = 20,
        pricePaise: Long = 29900
    ): Deal {
        val now = Instant.now()
        val deal = Deal(
            id = UUID.randomUUID(),
            title = "Flash Super Deal",
            description = "Best flash deal today",
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
        customerId: String,
        quantity: Int = 2,
        amountPaise: Long = deal.pricePaise * 2,
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

    @Test
    fun `1_Existing deal lookup returns inventory, pricing, timing, and metadata`() {
        val deal = createLiveDeal(stockTotal = 25, stockAvailable = 20, pricePaise = 29900)

        mockMvc.perform(
            get("/v1/deals/${deal.id}")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(deal.id.toString()))
            .andExpect(jsonPath("$.title").value("Flash Super Deal"))
            .andExpect(jsonPath("$.description").value("Best flash deal today"))
            .andExpect(jsonPath("$.pricePaise").value(29900))
            .andExpect(jsonPath("$.stockTotal").value(25))
            .andExpect(jsonPath("$.stockAvailable").value(20))
            .andExpect(jsonPath("$.startsAt").isNotEmpty)
            .andExpect(jsonPath("$.endsAt").isNotEmpty)
            .andExpect(jsonPath("$.createdAt").isNotEmpty)
            .andExpect(jsonPath("$.updatedAt").isNotEmpty)
    }

    @Test
    fun `2_Unknown deal returns 404 Not Found`() {
        val unknownId = UUID.randomUUID()

        mockMvc.perform(
            get("/v1/deals/$unknownId")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("DEAL_NOT_FOUND"))
    }

    @Test
    fun `3_Existing customer claim lookup returns claim state, timestamps, and amount`() {
        val deal = createLiveDeal(pricePaise = 30000)
        val customerId = "cust_owner_1"
        val claim = createClaim(
            deal = deal,
            customerId = customerId,
            quantity = 2,
            amountPaise = 60000,
            status = ClaimStatus.RESERVED
        )
        val token = jwtService.generateToken(customerId)

        mockMvc.perform(
            get("/v1/claims/${claim.id}")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(claim.id.toString()))
            .andExpect(jsonPath("$.dealId").value(deal.id.toString()))
            .andExpect(jsonPath("$.customerId").value(customerId))
            .andExpect(jsonPath("$.quantity").value(2))
            .andExpect(jsonPath("$.amountPaise").value(60000))
            .andExpect(jsonPath("$.status").value("RESERVED"))
            .andExpect(jsonPath("$.expiresAt").isNotEmpty)
            .andExpect(jsonPath("$.createdAt").isNotEmpty)
            .andExpect(jsonPath("$.updatedAt").isNotEmpty)
    }

    @Test
    fun `4_Customer cannot access another customer claim and unauthenticated is rejected`() {
        val deal = createLiveDeal()
        val ownerCustomerId = "cust_legitimate_owner"
        val attackerCustomerId = "cust_unauthorized_attacker"

        val claim = createClaim(
            deal = deal,
            customerId = ownerCustomerId,
            quantity = 1,
            amountPaise = 29900,
            status = ClaimStatus.RESERVED
        )

        // 1. Another customer with valid JWT attempts to read owner's claim -> 403 Forbidden
        val attackerToken = jwtService.generateToken(attackerCustomerId)
        mockMvc.perform(
            get("/v1/claims/${claim.id}")
                .header("Authorization", "Bearer $attackerToken")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))

        // 2. Unauthenticated request without JWT -> 401 Unauthorized
        mockMvc.perform(
            get("/v1/claims/${claim.id}")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
    }

    @Test
    fun `5_Unknown claim returns 404 Not Found`() {
        val unknownClaimId = UUID.randomUUID()
        val token = jwtService.generateToken("cust_test_any")

        mockMvc.perform(
            get("/v1/claims/$unknownClaimId")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"))
    }

    @Test
    fun `6_PAID claim lookup returns valid status and details`() {
        val deal = createLiveDeal(pricePaise = 45000)
        val customerId = "cust_paid_buyer"
        val claim = createClaim(
            deal = deal,
            customerId = customerId,
            quantity = 2,
            amountPaise = 90000,
            status = ClaimStatus.PAID
        )
        val token = jwtService.generateToken(customerId)

        mockMvc.perform(
            get("/v1/claims/${claim.id}")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(claim.id.toString()))
            .andExpect(jsonPath("$.customerId").value(customerId))
            .andExpect(jsonPath("$.amountPaise").value(90000))
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.quantity").value(2))
    }

    @Test
    fun `7_EXPIRED claim lookup returns valid status and details`() {
        val deal = createLiveDeal(pricePaise = 35000)
        val customerId = "cust_expired_buyer"
        val claim = createClaim(
            deal = deal,
            customerId = customerId,
            quantity = 1,
            amountPaise = 35000,
            status = ClaimStatus.EXPIRED,
            expiresInSeconds = -60
        )
        val token = jwtService.generateToken(customerId)

        mockMvc.perform(
            get("/v1/claims/${claim.id}")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(claim.id.toString()))
            .andExpect(jsonPath("$.customerId").value(customerId))
            .andExpect(jsonPath("$.amountPaise").value(35000))
            .andExpect(jsonPath("$.status").value("EXPIRED"))
            .andExpect(jsonPath("$.quantity").value(1))
    }

    @Test
    fun `8_List customer claims returns only claims belonging to authenticated customer`() {
        val deal1 = createLiveDeal(pricePaise = 20000)
        val deal2 = createLiveDeal(pricePaise = 50000)
        val myCustId = "cust_claims_list_owner"
        val otherCustId = "cust_claims_list_other"

        val claim1 = createClaim(deal = deal1, customerId = myCustId, quantity = 1, status = ClaimStatus.RESERVED)
        val claim2 = createClaim(deal = deal2, customerId = myCustId, quantity = 2, status = ClaimStatus.PAID)
        createClaim(deal = deal1, customerId = otherCustId, quantity = 1, status = ClaimStatus.RESERVED)

        val token = jwtService.generateToken(myCustId)

        mockMvc.perform(
            get("/v1/claims")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.id == '${claim1.id}')].customerId").value(myCustId))
            .andExpect(jsonPath("$[?(@.id == '${claim2.id}')].customerId").value(myCustId))
    }

    @Test
    fun `9_List customer claims requires valid authentication`() {
        mockMvc.perform(
            get("/v1/claims")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
    }
}
