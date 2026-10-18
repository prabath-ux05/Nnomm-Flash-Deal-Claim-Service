package com.nnomm.flashdeal.common.exception

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

data class ApiErrorResponse(
    val code: String,
    val error: String = code,
    val message: String,
    val timestamp: Instant = Instant.now()
)

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(DomainException::class)
    fun handleDomainException(ex: DomainException): ResponseEntity<ApiErrorResponse> {
        val status = when (ex) {
            is UnauthorizedException -> HttpStatus.UNAUTHORIZED
            is DealNotFoundException -> HttpStatus.NOT_FOUND
            is ClaimNotFoundException -> HttpStatus.NOT_FOUND
            is DealNotLiveException -> HttpStatus.BAD_REQUEST
            is InvalidQuantityException -> HttpStatus.BAD_REQUEST
            is InvalidIdempotencyKeyException -> HttpStatus.BAD_REQUEST
            is InvalidWebhookPayloadException -> HttpStatus.BAD_REQUEST
            is SoldOutException -> HttpStatus.CONFLICT
            is ActiveClaimExistsException -> HttpStatus.CONFLICT
            is IdempotencyKeyReusedException -> HttpStatus.CONFLICT
            is RequestInProgressException -> HttpStatus.CONFLICT
            is InvalidClaimStateException -> HttpStatus.CONFLICT
        }

        return ResponseEntity.status(status).body(
            ApiErrorResponse(
                code = ex.code,
                error = ex.code,
                message = ex.message
            )
        )
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<ApiErrorResponse> {
        val message = ex.mostSpecificCause.message ?: ex.message ?: ""
        val (code, errorStatus) = when {
            message.contains("idx_claims_active_customer_deal", ignoreCase = true) ||
            message.contains("uq_active_claim", ignoreCase = true) -> {
                "ACTIVE_CLAIM_EXISTS" to HttpStatus.CONFLICT
            }
            message.contains("chk_deals_stock_available_range", ignoreCase = true) ||
            message.contains("stock_available", ignoreCase = true) -> {
                "SOLD_OUT" to HttpStatus.CONFLICT
            }
            message.contains("uq_idempotency_customer_key", ignoreCase = true) -> {
                "REQUEST_IN_PROGRESS" to HttpStatus.CONFLICT
            }
            else -> {
                "DATA_INTEGRITY_VIOLATION" to HttpStatus.CONFLICT
            }
        }

        return ResponseEntity.status(errorStatus).body(
            ApiErrorResponse(
                code = code,
                error = code,
                message = when (code) {
                    "ACTIVE_CLAIM_EXISTS" -> "Customer already has an active claim for this deal"
                    "SOLD_OUT" -> "Deal is sold out or insufficient stock available"
                    "REQUEST_IN_PROGRESS" -> "A request with this idempotency key is already in progress"
                    else -> "Database constraint violation"
                }
            )
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ApiErrorResponse> {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse(
                code = "INTERNAL_SERVER_ERROR",
                error = "INTERNAL_SERVER_ERROR",
                message = ex.message ?: "An unexpected error occurred"
            )
        )
    }
}
