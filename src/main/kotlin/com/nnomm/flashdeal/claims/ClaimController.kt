package com.nnomm.flashdeal.claims

import com.nnomm.flashdeal.common.exception.UnauthorizedException
import com.nnomm.flashdeal.security.JwtService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1")
class ClaimController(
    private val claimService: ClaimService,
    private val jwtService: JwtService
) {

    @PostMapping("/deals/{dealId}/claims")
    fun claimDeal(
        @PathVariable("dealId") dealId: UUID,
        @RequestHeader(value = "Authorization", required = false) authHeader: String?,
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) request: ClaimRequest?
    ): ResponseEntity<ClaimResponse> {
        val customerId = extractCustomerId(authHeader)

        val response = claimService.claimDeal(
            dealId = dealId,
            customerId = customerId,
            idempotencyKey = idempotencyKey,
            quantity = request?.quantity
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/claims/{claimId}")
    fun getClaim(
        @PathVariable("claimId") claimId: UUID,
        @RequestHeader(value = "Authorization", required = false) authHeader: String?
    ): ResponseEntity<ClaimResponse> {
        val customerId = extractCustomerId(authHeader)
        val response = claimService.getClaim(claimId, customerId)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/claims")
    fun getCustomerClaims(
        @RequestHeader(value = "Authorization", required = false) authHeader: String?
    ): ResponseEntity<List<ClaimResponse>> {
        val customerId = extractCustomerId(authHeader)
        val response = claimService.getCustomerClaims(customerId)
        return ResponseEntity.ok(response)
    }


    private fun extractCustomerId(authHeader: String?): String {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw UnauthorizedException("Missing or malformed Authorization header")
        }
        val token = authHeader.substringAfter("Bearer ").trim()
        return jwtService.validateAndExtractCustomerId(token)
            ?: throw UnauthorizedException("Invalid or expired authentication token")
    }
}
