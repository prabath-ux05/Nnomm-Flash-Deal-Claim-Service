package com.nnomm.flashdeal.payments

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

data class PaymentWebhookRequest(
    @JsonProperty("event_id")
    @JsonAlias("eventId")
    val eventId: String? = null,

    @JsonProperty("claim_id")
    @JsonAlias("claimId")
    val claimId: UUID? = null,

    @JsonProperty("amount_paise")
    @JsonAlias("amountPaise")
    val amountPaise: Long? = null,

    @JsonProperty("status")
    @JsonAlias("payment_status", "paymentStatus")
    val status: String? = null
)

data class PaymentWebhookResponse(
    val eventId: String,
    val claimId: UUID?,
    val status: String,
    val message: String
)
