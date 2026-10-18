package com.nnomm.flashdeal.claims

import com.fasterxml.jackson.databind.ObjectMapper
import com.nnomm.flashdeal.common.config.AppProperties
import com.nnomm.flashdeal.common.exception.ActiveClaimExistsException
import com.nnomm.flashdeal.common.exception.ClaimNotFoundException
import com.nnomm.flashdeal.common.exception.DealNotFoundException
import com.nnomm.flashdeal.common.exception.DealNotLiveException
import com.nnomm.flashdeal.common.exception.ForbiddenException
import com.nnomm.flashdeal.common.exception.IdempotencyKeyReusedException
import com.nnomm.flashdeal.common.exception.InvalidIdempotencyKeyException
import com.nnomm.flashdeal.common.exception.InvalidQuantityException
import com.nnomm.flashdeal.common.exception.RequestInProgressException
import com.nnomm.flashdeal.common.exception.SoldOutException

import com.nnomm.flashdeal.common.idempotency.IdempotencyRecord
import com.nnomm.flashdeal.common.idempotency.IdempotencyRepository
import com.nnomm.flashdeal.common.idempotency.IdempotencyStatus
import com.nnomm.flashdeal.deals.DealRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class ClaimService(
    private val dealRepository: DealRepository,
    private val claimRepository: ClaimRepository,
    private val idempotencyRepository: IdempotencyRepository,
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper
) {

    private val idempotencyKeyPattern = Regex("^[a-zA-Z0-9_.-]{1,255}$")

    @Transactional
    fun claimDeal(
        dealId: UUID,
        customerId: String,
        idempotencyKey: String?,
        quantity: Int?
    ): ClaimResponse {
        val now = Instant.now()

        // 1. Validate Idempotency-Key
        if (idempotencyKey.isNullOrBlank() || !idempotencyKeyPattern.matches(idempotencyKey)) {
            throw InvalidIdempotencyKeyException()
        }
        val validatedKey = idempotencyKey

        // 2. Validate Quantity 1-3
        if (quantity == null || quantity < 1 || quantity > 3) {
            throw InvalidQuantityException()
        }

        // 3. Process Idempotency Record
        val existingRecordOpt = idempotencyRepository.findByCustomerIdAndKeyForUpdate(customerId, validatedKey)
        val idempotencyRecord = if (existingRecordOpt.isPresent) {
            val record = existingRecordOpt.get()

            // Verify payload matching: Same key + different deal/quantity must return IDEMPOTENCY_KEY_REUSED
            if (record.dealId != dealId || record.quantity != quantity) {
                throw IdempotencyKeyReusedException()
            }

            // If already completed, replay the original response
            if (record.status == IdempotencyStatus.COMPLETED && record.responseBody != null) {
                return objectMapper.readValue(record.responseBody, ClaimResponse::class.java)
            }

            // If in progress and not expired, reject concurrent duplicate attempt
            if (record.status == IdempotencyStatus.IN_PROGRESS && record.recoveryDeadline.isAfter(now)) {
                throw RequestInProgressException()
            }

            // Crash recovery: Previous execution timed out / crashed; reset lease and continue
            record.status = IdempotencyStatus.IN_PROGRESS
            record.lockedAt = now
            record.recoveryDeadline = now.plusSeconds(60)
            record.updatedAt = now
            idempotencyRepository.saveAndFlush(record)
        } else {
            val newRecord = IdempotencyRecord(
                key = validatedKey,
                customerId = customerId,
                dealId = dealId,
                quantity = quantity,
                status = IdempotencyStatus.IN_PROGRESS,
                lockedAt = now,
                recoveryDeadline = now.plusSeconds(60),
                createdAt = now,
                updatedAt = now
            )
            idempotencyRepository.saveAndFlush(newRecord)
        }

        // 4. Enforce One Active Claim per Customer per Deal
        val activeClaim = claimRepository.findActiveClaim(dealId, customerId)
        if (activeClaim.isPresent) {
            throw ActiveClaimExistsException()
        }

        // 5. Lock and verify the deal
        val deal = dealRepository.findByIdForUpdate(dealId).orElseThrow {
            DealNotFoundException()
        }

        // Verify the deal is currently live
        if (!deal.isLive(now)) {
            throw DealNotLiveException()
        }

        // 6. Atomically reserve stock
        if (deal.stockAvailable < quantity) {
            throw SoldOutException()
        }

        deal.stockAvailable -= quantity
        deal.updatedAt = now
        dealRepository.save(deal)

        // 7. Calculate amount using integer paise and create claim with status RESERVED
        val amountPaise = deal.pricePaise * quantity
        val expiresAt = now.plusSeconds(appProperties.claims.ttlSeconds)

        val claim = Claim(
            id = UUID.randomUUID(),
            dealId = dealId,
            customerId = customerId,
            quantity = quantity,
            amountPaise = amountPaise,
            status = ClaimStatus.RESERVED,
            expiresAt = expiresAt,
            createdAt = now,
            updatedAt = now
        )
        claimRepository.save(claim)

        // 8. Persist idempotency completion and response
        val response = ClaimResponse(
            id = claim.id,
            dealId = claim.dealId,
            customerId = claim.customerId,
            quantity = claim.quantity,
            amountPaise = claim.amountPaise,
            status = claim.status,
            expiresAt = claim.expiresAt,
            createdAt = claim.createdAt,
            updatedAt = claim.updatedAt
        )

        idempotencyRecord.status = IdempotencyStatus.COMPLETED
        idempotencyRecord.httpStatus = 201
        idempotencyRecord.responseBody = objectMapper.writeValueAsString(response)
        idempotencyRecord.claimId = claim.id
        idempotencyRecord.updatedAt = now
        idempotencyRepository.save(idempotencyRecord)

        return response
    }

    @Transactional(readOnly = true)
    fun getClaim(claimId: UUID, customerId: String): ClaimResponse {
        val claim = claimRepository.findById(claimId).orElseThrow {
            ClaimNotFoundException("Claim with ID $claimId not found")
        }

        if (claim.customerId != customerId) {
            throw ForbiddenException("Customer is not authorized to access this claim")
        }

        return ClaimResponse(
            id = claim.id,
            dealId = claim.dealId,
            customerId = claim.customerId,
            quantity = claim.quantity,
            amountPaise = claim.amountPaise,
            status = claim.status,
            expiresAt = claim.expiresAt,
            createdAt = claim.createdAt,
            updatedAt = claim.updatedAt
        )
    }

    @Transactional(readOnly = true)
    fun getCustomerClaims(customerId: String): List<ClaimResponse> {
        return claimRepository.findByCustomerId(customerId).map { claim ->
            ClaimResponse(
                id = claim.id,
                dealId = claim.dealId,
                customerId = claim.customerId,
                quantity = claim.quantity,
                amountPaise = claim.amountPaise,
                status = claim.status,
                expiresAt = claim.expiresAt,
                createdAt = claim.createdAt,
                updatedAt = claim.updatedAt
            )
        }
    }
}


