package com.nnomm.flashdeal.common.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val jwt: JwtProperties = JwtProperties(),
    val webhook: WebhookProperties = WebhookProperties(),
    val claims: ClaimsProperties = ClaimsProperties()
) {
    data class JwtProperties(
        val secret: String = ""
    )

    data class WebhookProperties(
        val secret: String = ""
    )

    data class ClaimsProperties(
        val ttlSeconds: Long = 600
    )
}
