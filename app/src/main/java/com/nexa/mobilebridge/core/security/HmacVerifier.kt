package com.nexa.mobilebridge.core.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HmacVerifier {
    /**
     * Verifies the HMAC-SHA256 signature from N.E.X.A Server.
     *
     * The server (security.js) generates the signature as follows:
     *   payload = timestamp + level + NEXA_GODMODE_SECRET   (three values directly concatenated)
     *   signature = HMAC-SHA256(key=NEXA_GODMODE_SECRET, message=payload)
     *
     * Example: timestamp=1722780000000, level=3, secret="MYKEY"
     *   payload  = "172278000000033MYKEY"
     *   key      = "MYKEY"
     *   result   = HMAC-SHA256("MYKEY", "172278000000033MYKEY")
     *
     * @param timestamp  Unix epoch milliseconds (Long) — same value sent from server
     * @param level      Escalation level (Int) — same value used by server
     * @param receivedSignature  Hex string received from server in the "signature" field
     * @param secret     NEXA_GODMODE_SECRET — stored in DataStore, set by user in Settings
     * @return true if signatures match, false if tampered or wrong key
     */
    fun verify(timestamp: Long, level: Int, receivedSignature: String, secret: String): Boolean {
        return try {
            // Replicate exact server payload format: timestamp + level + secret (no separator)
            val message = "${timestamp}${level}${secret}"
            val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(keySpec)
            val computed = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            val computedHex = computed.joinToString("") { "%02x".format(it) }
            computedHex == receivedSignature
        } catch (e: Exception) {
            false
        }
    }
}
