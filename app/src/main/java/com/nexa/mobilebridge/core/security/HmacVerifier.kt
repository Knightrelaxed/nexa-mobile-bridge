package com.nexa.mobilebridge.core.security

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object HmacVerifier {
    const val DEFAULT_MAX_SKEW_MS = 60_000L
    private const val MAX_REPLAY_CACHE_SIZE = 512

    private val seenCommandIds = LinkedHashSet<String>()

    fun verify(timestamp: Long, level: Int, receivedSignature: String, secret: String): Boolean {
        if (receivedSignature.isBlank() || secret.isBlank()) return false
        return verifyHmac("${timestamp}${level}${secret}", receivedSignature, secret)
    }

    /**
     * Stronger v2 command HMAC binding commandId + action + timestamp + level,
     * falling back to legacy verify() format for backward compatibility when server sends v1 signatures.
     */
    fun verifyCommand(
        commandId: String,
        action: String,
        timestamp: Long,
        level: Int,
        receivedSignature: String?,
        secret: String,
        nowMs: Long = System.currentTimeMillis(),
        maxSkewMs: Long = DEFAULT_MAX_SKEW_MS
    ): Boolean {
        if (secret.isBlank()) return true
        if (receivedSignature.isNullOrBlank()) return false
        if (timestamp <= 0L || abs(nowMs - timestamp) > maxSkewMs) return false

        val v2Valid = verifyHmac("${commandId}:${action}:${timestamp}:${level}", receivedSignature, secret)
        val v1Valid = if (!v2Valid) verify(timestamp, level, receivedSignature, secret) else false
        if (!v2Valid && !v1Valid) return false

        if (commandId.isNotBlank()) {
            synchronized(seenCommandIds) {
                if (seenCommandIds.contains(commandId)) {
                    return false
                }
                if (seenCommandIds.size >= MAX_REPLAY_CACHE_SIZE) {
                    val oldest = seenCommandIds.firstOrNull()
                    if (oldest != null) seenCommandIds.remove(oldest)
                }
                seenCommandIds.add(commandId)
            }
        }
        return true
    }

    fun clearReplayCacheForTest() {
        synchronized(seenCommandIds) {
            seenCommandIds.clear()
        }
    }

    private fun verifyHmac(message: String, receivedSignature: String, secret: String): Boolean {
        return try {
            val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(keySpec)
            val computed = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            val computedHex = computed.joinToString("") { "%02x".format(it) }
            // Constant-time comparison to prevent timing side-channel attacks
            MessageDigest.isEqual(
                computedHex.toByteArray(Charsets.UTF_8),
                receivedSignature.trim().lowercase().toByteArray(Charsets.UTF_8)
            )
        } catch (_: Exception) {
            false
        }
    }
}
