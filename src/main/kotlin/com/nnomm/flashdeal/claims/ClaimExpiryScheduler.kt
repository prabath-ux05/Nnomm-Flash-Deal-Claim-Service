package com.nnomm.flashdeal.claims

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["app.expiry.enabled"], havingValue = "true", matchIfMissing = true)
class ClaimExpiryScheduler(
    private val claimExpiryService: ClaimExpiryService
) {
    private val logger = LoggerFactory.getLogger(ClaimExpiryScheduler::class.java)

    /**
     * Periodically triggers claim expiry processing based on configurable fixedDelay and initialDelay.
     * When the application restarts, initialDelay (default 0) ensures overdue claims created before or
     * during the restart are processed immediately on startup.
     */
    @Scheduled(
        fixedDelayString = "\${app.expiry.interval-ms:1000}",
        initialDelayString = "\${app.expiry.initial-delay-ms:0}"
    )
    fun processExpiredClaims() {
        try {
            val count = claimExpiryService.processExpiredClaims()
            if (count > 0) {
                logger.info("ClaimExpiryScheduler expired and restored {} overdue claim(s)", count)
            }
        } catch (e: Exception) {
            logger.error("Error occurred during scheduled claim expiry processing", e)
        }
    }
}
