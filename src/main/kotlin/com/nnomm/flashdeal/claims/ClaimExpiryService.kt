package com.nnomm.flashdeal.claims

import com.nnomm.flashdeal.common.config.AppProperties
import org.springframework.stereotype.Service

@Service
class ClaimExpiryService(
    private val batchProcessor: ClaimExpiryBatchProcessor,
    private val appProperties: AppProperties
) {
    /**
     * Drains all overdue RESERVED claims by processing them in configurable batches
     * until no more overdue claims remain.
     * Returns the total count of claims expired across all batches.
     */
    fun processExpiredClaims(batchSize: Int = appProperties.expiry.batchSize): Int {
        var totalProcessed = 0
        while (true) {
            val processed = batchProcessor.processBatch(batchSize)
            totalProcessed += processed
            if (processed < batchSize) {
                break
            }
        }
        return totalProcessed
    }

    /**
     * Processes a single batch of overdue RESERVED claims atomically.
     * Returns the number of claims expired in this batch.
     */
    fun processExpiredBatch(batchSize: Int = appProperties.expiry.batchSize): Int {
        return batchProcessor.processBatch(batchSize)
    }
}
