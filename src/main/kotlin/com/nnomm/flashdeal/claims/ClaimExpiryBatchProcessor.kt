package com.nnomm.flashdeal.claims

import com.nnomm.flashdeal.deals.DealRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class ClaimExpiryBatchProcessor(
    private val claimRepository: ClaimRepository,
    private val dealRepository: DealRepository
) {
    private val logger = LoggerFactory.getLogger(ClaimExpiryBatchProcessor::class.java)

    /**
     * Atomically selects and locks overdue RESERVED claims using PostgreSQL FOR UPDATE SKIP LOCKED,
     * restores their quantities to stock_available on the corresponding deals,
     * and transitions claim statuses from RESERVED -> EXPIRED.
     *
     * The entire batch runs within a single atomic database transaction.
     */
    @Transactional
    fun processBatch(batchSize: Int): Int {
        val expiredClaims = claimRepository.findExpiredClaimsForUpdate(batchSize)
        if (expiredClaims.isEmpty()) {
            return 0
        }

        // Strictly process only RESERVED claims (never touch PAID, NEEDS_REVIEW, or REFUND_PENDING)
        val reservedClaims = expiredClaims.filter { it.status == ClaimStatus.RESERVED }
        if (reservedClaims.isEmpty()) {
            return 0
        }

        val now = Instant.now()

        // 1. Group claims by dealId and sort by dealId to guarantee deterministic global lock ordering
        // and eliminate any possibility of deadlocks across concurrent worker threads or instances.
        val claimsByDeal = reservedClaims.groupBy { it.dealId }
        val sortedDealIds = claimsByDeal.keys.sorted()

        // 2. Lock deals in order and restore stock_available
        for (dealId in sortedDealIds) {
            val claimsForDeal = claimsByDeal[dealId] ?: continue
            val totalQuantityToRestore = claimsForDeal.sumOf { it.quantity }

            val deal = dealRepository.findByIdForUpdate(dealId).orElseThrow {
                IllegalStateException("Deal $dealId not found during expiry stock restoration")
            }

            deal.stockAvailable = minOf(deal.stockTotal, deal.stockAvailable + totalQuantityToRestore)
            deal.updatedAt = now
            dealRepository.save(deal)
        }

        // 3. Atomically transition claim status: RESERVED -> EXPIRED
        for (claim in reservedClaims) {
            claim.status = ClaimStatus.EXPIRED
            claim.updatedAt = now
            claimRepository.save(claim)
        }

        logger.debug("Successfully expired {} claim(s) and restored stock", reservedClaims.size)
        return reservedClaims.size
    }
}
