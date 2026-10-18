package com.nnomm.flashdeal.payments

import com.fasterxml.jackson.databind.ObjectMapper
import com.nnomm.flashdeal.claims.ClaimRepository
import com.nnomm.flashdeal.claims.ClaimStatus
import com.nnomm.flashdeal.common.exception.ClaimNotFoundException
import com.nnomm.flashdeal.common.exception.InvalidClaimStateException
import com.nnomm.flashdeal.common.exception.InvalidWebhookPayloadException
import com.nnomm.flashdeal.common.exception.UnauthorizedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Instant

@Service
class PaymentWebhookService(
    private val paymentSignatureValidator: PaymentSignatureValidator,
    private val paymentEventRepository: PaymentEventRepository,
    private val claimRepository: ClaimRepository,
    private val objectMapper: ObjectMapper
) {

    @Transactional
    fun processWebhook(
        rawPayload: ByteArray,
        signatureHeader: String?
    ): PaymentWebhookResponse {
        val now = Instant.now()

        // 1. Verify X-Signature using HMAC-SHA256 and WEBHOOK_SECRET
        if (!paymentSignatureValidator.verifySignature(rawPayload, signatureHeader)) {
            throw UnauthorizedException("Invalid or missing webhook signature")
        }

        // 2. Parse & Validate Payload
        val request = try {
            objectMapper.readValue(rawPayload, PaymentWebhookRequest::class.java)
        } catch (e: Exception) {
            throw InvalidWebhookPayloadException("Malformed webhook JSON payload: ${e.message}")
        }

        val eventId = request.eventId
        if (eventId.isNullOrBlank()) {
            throw InvalidWebhookPayloadException("Missing or blank event_id in webhook payload")
        }
        val claimId = request.claimId
            ?: throw InvalidWebhookPayloadException("Missing claim_id in webhook payload")
        val amountPaise = request.amountPaise
            ?: throw InvalidWebhookPayloadException("Missing amount_paise in webhook payload")
        if (amountPaise <= 0) {
            throw InvalidWebhookPayloadException("amount_paise must be positive")
        }

        // 3. Webhook Deduplication Check (Fast-path)
        val existingEventOpt = paymentEventRepository.findByEventId(eventId)
        if (existingEventOpt.isPresent) {
            val existing = existingEventOpt.get()
            return PaymentWebhookResponse(
                eventId = existing.eventId,
                claimId = existing.claimId,
                status = existing.status.name,
                message = "Duplicate webhook event safely ignored"
            )
        }

        // 4. Lock & Validate Referenced Claim
        val claim = claimRepository.findByIdForUpdate(claimId).orElseThrow {
            ClaimNotFoundException("Claim with ID $claimId not found")
        }

        // 5. Double-check event_id after acquiring claim row lock (Concurrency protection across instances)
        val postLockEventOpt = paymentEventRepository.findByEventId(eventId)
        if (postLockEventOpt.isPresent) {
            val existing = postLockEventOpt.get()
            return PaymentWebhookResponse(
                eventId = existing.eventId,
                claimId = existing.claimId,
                status = existing.status.name,
                message = "Duplicate webhook event safely ignored"
            )
        }

        val rawPayloadString = String(rawPayload, StandardCharsets.UTF_8)

        // 6. Enforce: Process state transitions based on webhook status
        val isRefund = request.status?.equals("REFUNDED", ignoreCase = true) == true
        val isFailure = request.status?.let {
            it.equals("FAILED", ignoreCase = true) ||
            it.equals("FAILURE", ignoreCase = true) ||
            it.equals("CANCELLED", ignoreCase = true)
        } == true

        if (isRefund) {
            if (claim.status != ClaimStatus.PAID) {
                throw InvalidClaimStateException("Cannot process refund for claim in status ${claim.status}")
            }
            claim.status = ClaimStatus.REFUND_PENDING
            claim.updatedAt = now
            claimRepository.save(claim)

            val paymentEvent = PaymentEvent(
                eventId = eventId,
                claimId = claim.id,
                amountPaise = amountPaise,
                status = PaymentEventStatus.PROCESSED,
                payload = rawPayloadString,
                processedAt = now
            )
            paymentEventRepository.save(paymentEvent)

            return PaymentWebhookResponse(
                eventId = eventId,
                claimId = claim.id,
                status = ClaimStatus.REFUND_PENDING.name,
                message = "Refund processed successfully"
            )
        }

        if (isFailure) {
            val paymentEvent = PaymentEvent(
                eventId = eventId,
                claimId = claim.id,
                amountPaise = amountPaise,
                status = PaymentEventStatus.REJECTED,
                payload = rawPayloadString,
                errorMessage = "Payment failed at gateway",
                processedAt = now
            )
            paymentEventRepository.save(paymentEvent)

            return PaymentWebhookResponse(
                eventId = eventId,
                claimId = claim.id,
                status = "PAYMENT_FAILED",
                message = "Payment failed at gateway. Claim remains reserved until expiry or retry."
            )
        }

        if (claim.status != ClaimStatus.RESERVED) {
            throw InvalidClaimStateException("Cannot process payment for claim in status ${claim.status}")
        }

        // 7. Validate payment amount against claim amount_paise and execute state transition
        return if (amountPaise == claim.amountPaise) {
            // Valid matching payment -> PAID
            claim.status = ClaimStatus.PAID
            claim.updatedAt = now
            claimRepository.save(claim)

            val paymentEvent = PaymentEvent(
                eventId = eventId,
                claimId = claim.id,
                amountPaise = amountPaise,
                status = PaymentEventStatus.PROCESSED,
                payload = rawPayloadString,
                processedAt = now
            )
            paymentEventRepository.save(paymentEvent)

            PaymentWebhookResponse(
                eventId = eventId,
                claimId = claim.id,
                status = ClaimStatus.PAID.name,
                message = "Payment processed successfully"
            )
        } else {
            // Amount mismatch -> NEEDS_REVIEW
            claim.status = ClaimStatus.NEEDS_REVIEW
            claim.updatedAt = now
            claimRepository.save(claim)

            val paymentEvent = PaymentEvent(
                eventId = eventId,
                claimId = claim.id,
                amountPaise = amountPaise,
                status = PaymentEventStatus.PROCESSED,
                payload = rawPayloadString,
                errorMessage = "Payment amount mismatch: expected ${claim.amountPaise} paise but received $amountPaise paise",
                processedAt = now
            )
            paymentEventRepository.save(paymentEvent)

            PaymentWebhookResponse(
                eventId = eventId,
                claimId = claim.id,
                status = ClaimStatus.NEEDS_REVIEW.name,
                message = "Payment amount mismatch: expected ${claim.amountPaise} paise but received $amountPaise paise. Claim marked as NEEDS_REVIEW."
            )
        }
    }
}
