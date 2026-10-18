package com.nnomm.flashdeal.common.exception

sealed class DomainException(
    val code: String,
    override val message: String
) : RuntimeException(message)

class DealNotFoundException(message: String = "Deal not found") :
    DomainException("DEAL_NOT_FOUND", message)

class DealNotLiveException(message: String = "Deal is not currently live") :
    DomainException("DEAL_NOT_LIVE", message)

class InvalidQuantityException(message: String = "Quantity must be between 1 and 3") :
    DomainException("INVALID_QUANTITY", message)

class InvalidIdempotencyKeyException(message: String = "Invalid or missing Idempotency-Key header") :
    DomainException("INVALID_IDEMPOTENCY_KEY", message)

class SoldOutException(message: String = "Deal is sold out or insufficient stock available") :
    DomainException("SOLD_OUT", message)

class ActiveClaimExistsException(message: String = "Customer already has an active claim for this deal") :
    DomainException("ACTIVE_CLAIM_EXISTS", message)

class IdempotencyKeyReusedException(message: String = "Idempotency key reused with different request parameters") :
    DomainException("IDEMPOTENCY_KEY_REUSED", message)

class RequestInProgressException(message: String = "Request with this idempotency key is currently being processed") :
    DomainException("REQUEST_IN_PROGRESS", message)

class UnauthorizedException(message: String = "Missing or invalid authentication token") :
    DomainException("UNAUTHORIZED", message)

class ForbiddenException(message: String = "Customer is not authorized to access this claim") :
    DomainException("FORBIDDEN", message)

class ClaimNotFoundException(message: String = "Claim not found") :
    DomainException("CLAIM_NOT_FOUND", message)

class InvalidClaimStateException(message: String = "Invalid claim state for payment processing") :
    DomainException("INVALID_CLAIM_STATE", message)

class InvalidWebhookPayloadException(message: String = "Invalid webhook payload") :
    DomainException("INVALID_WEBHOOK_PAYLOAD", message)
