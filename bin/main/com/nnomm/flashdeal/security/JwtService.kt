package com.nnomm.flashdeal.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.nnomm.flashdeal.common.config.AppProperties
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class JwtService(
    private val appProperties: AppProperties,
    private val objectMapper: ObjectMapper
) {

    private val algorithm = "HmacSHA256"

    fun generateToken(customerId: String, expiresInSeconds: Long = 86400): String {
        val now = Instant.now().epochSecond
        val exp = now + expiresInSeconds

        val header = mapOf("alg" to "HS256", "typ" to "JWT")
        val payload = mapOf(
            "sub" to customerId,
            "customerId" to customerId,
            "iat" to now,
            "exp" to exp
        )

        val headerBase64 = base64UrlEncode(objectMapper.writeValueAsBytes(header))
        val payloadBase64 = base64UrlEncode(objectMapper.writeValueAsBytes(payload))

        val contentToSign = "$headerBase64.$payloadBase64"
        val signature = sign(contentToSign, appProperties.jwt.secret)

        return "$contentToSign.$signature"
    }

    fun validateAndExtractCustomerId(token: String): String? {
        val parts = token.split(".")
        if (parts.size != 3) {
            return null
        }

        val headerBase64 = parts[0]
        val payloadBase64 = parts[1]
        val providedSignature = parts[2]

        val contentToSign = "$headerBase64.$payloadBase64"
        val expectedSignature = sign(contentToSign, appProperties.jwt.secret)

        if (!MessageDigest.isEqual(
                expectedSignature.toByteArray(StandardCharsets.UTF_8),
                providedSignature.toByteArray(StandardCharsets.UTF_8)
            )
        ) {
            return null
        }

        return try {
            val payloadJson = String(base64UrlDecode(payloadBase64), StandardCharsets.UTF_8)
            val payload = objectMapper.readValue(payloadJson, Map::class.java)

            val exp = (payload["exp"] as? Number)?.toLong()
            if (exp != null && exp < Instant.now().epochSecond) {
                return null
            }

            (payload["sub"] as? String) ?: (payload["customerId"] as? String)
        } catch (e: Exception) {
            null
        }
    }

    private fun sign(data: String, secret: String): String {
        val secretBytes = secret.toByteArray(StandardCharsets.UTF_8)
        val keySpec = SecretKeySpec(secretBytes, algorithm)
        val mac = Mac.getInstance(algorithm)
        mac.init(keySpec)
        val signatureBytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return base64UrlEncode(signatureBytes)
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun base64UrlDecode(str: String): ByteArray {
        return Base64.getUrlDecoder().decode(str)
    }
}
