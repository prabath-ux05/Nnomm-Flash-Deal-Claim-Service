package com.nnomm.flashdeal.payments

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/payments")
class PaymentWebhookController(
    private val paymentWebhookService: PaymentWebhookService
) {

    @PostMapping("/webhook")
    fun handleWebhook(
        @RequestHeader(value = "X-Signature", required = false) signatureHeader: String?,
        @RequestBody(required = false) rawPayload: ByteArray?
    ): ResponseEntity<PaymentWebhookResponse> {
        val response = paymentWebhookService.processWebhook(
            rawPayload = rawPayload ?: ByteArray(0),
            signatureHeader = signatureHeader
        )
        return ResponseEntity.ok(response)
    }
}
