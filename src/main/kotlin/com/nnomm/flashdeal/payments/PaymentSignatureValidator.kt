package com.nnomm.flashdeal.payments

import com.nnomm.flashdeal.common.config.AppProperties
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class PaymentSignatureValidator(
    private val appProperties: AppProperties
) {

    fun verifySignature(payload: ByteArray, signatureHeader: String?): Boolean {
        val secret = appProperties.webhook.secret
        return verify(payload, signatureHeader, secret)
    }

    fun calculateSignature(payload: ByteArray, secret: String = appProperties.webhook.secret): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(secretKeySpec)
        val hash = mac.doFinal(payload)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun verify(payload: ByteArray, signatureHeader: String?, secret: String): Boolean {
        if (signatureHeader.isNullOrBlank() || secret.isBlank()) {
            return false
        }
        val cleanSignature = signatureHeader.trim().removePrefix("sha256=").trim()
        val expectedSignature = calculateSignature(payload, secret)

        return MessageDigest.isEqual(
            cleanSignature.lowercase().toByteArray(StandardCharsets.UTF_8),
            expectedSignature.lowercase().toByteArray(StandardCharsets.UTF_8)
        )
    }
}
