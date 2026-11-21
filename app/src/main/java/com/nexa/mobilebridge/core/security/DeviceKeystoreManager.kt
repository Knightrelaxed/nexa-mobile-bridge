package com.nexa.mobilebridge.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

enum class KeystoreKeyState {
    READY,
    INVALIDATED,
    BIOMETRIC_NOT_ENROLLED,
    ERROR
}

data class DeviceKeySnapshot(
    val state: KeystoreKeyState,
    val spkiDerB64: String = "",
    val sha256ColonHex: String = "",
    val sha256Lines4x8: String = "",
    val securityLevelLabel: String = "Hardware TEE",
    val errorMessage: String? = null
)

/**
 * Hardware-backed Android Keystore ECDSA P-256 identity manager (FR-AUTH-02 / PRD Appendix C).
 * - Curve: secp256r1 (P-256), SHA-256 digest, PURPOSE_SIGN
 * - Per-use biometric authentication required (BIOMETRIC_STRONG, no PIN fallback)
 * - Invalidated automatically if a new biometric is enrolled on the OS
 */
@Singleton
class DeviceKeystoreManager @Inject constructor() {

    companion object {
        private const val TAG = "DeviceKeystore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nexa_device_key"

        /**
         * Canonical ticket signing payload matching Node.js TicketService & PRD FR-AUTH-04:
         * "nexa-ticket-v1\n<nonce>\n<scope>\n<sid>"
         */
        fun buildCanonicalTicketMessage(nonce: String, scope: String, sid: String): ByteArray {
            return "nexa-ticket-v1\n$nonce\n$scope\n$sid".toByteArray(Charsets.UTF_8)
        }

        /**
         * Canonical Finance Step-Up payload hash matching Node.js FinanceStepUpService.buildCanonicalActionPayload:
         * SHA-256 hex of compact JSON with exact keys:
         * action_id, action_label, amount, recipient_full, bank, acct_last4, whitelisted
         */
        fun computeCanonicalActionHash(
            actionId: String,
            actionLabel: String,
            amount: Long,
            recipientFull: String,
            bank: String,
            acctLast4: String,
            whitelisted: Boolean
        ): String {
            val canonicalJson = buildJsonObject {
                put("action_id", actionId)
                put("action_label", actionLabel)
                put("amount", amount)
                put("recipient_full", recipientFull)
                put("bank", bank)
                put("acct_last4", acctLast4)
                put("whitelisted", whitelisted)
            }.toString()
            val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
        }

        /**
         * Canonical Finance Step-Up signing payload matching Node.js FinanceStepUpService & PRD 10.6 (FR-AUTH-07):
         * "nexa-action-v1\n<nonce>\n<action_id>\n<canonical_hash>"
         */
        fun buildCanonicalActionMessage(nonce: String, actionId: String, canonicalHash: String): ByteArray {
            return "nexa-action-v1\n$nonce\n$actionId\n$canonicalHash".toByteArray(Charsets.UTF_8)
        }

        /**
         * Compute full 32-octet SHA-256 fingerprint (colon-separated lowercase hex).
         */
        fun computeSha256ColonHex(spkiDerBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(spkiDerBytes)
            return digest.joinToString(":") { b -> "%02x".format(b.toInt() and 0xFF) }
        }

        /**
         * Format 32-octet SHA-256 colon hex into 4 rows x 8 octets for Settings UI & CLI verification.
         */
        fun formatFingerprint4x8(colonHex: String): String {
            val octets = colonHex.split(":").filter { it.isNotBlank() }
            if (octets.isEmpty()) return ""
            return octets.chunked(8).joinToString("\n") { row -> row.joinToString(":") }
        }
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun getOrCreateKeySnapshot(): DeviceKeySnapshot {
        return try {
            val ks = loadKeyStore()
            if (!ks.containsAlias(KEY_ALIAS)) {
                generateKeyPair()
            }
            inspectExistingKey(ks)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "Keystore key permanently invalidated (new biometric enrolled)")
            DeviceKeySnapshot(
                state = KeystoreKeyState.INVALIDATED,
                errorMessage = "Device key was invalidated after a new fingerprint was added. Re-pair this device in Settings."
            )
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            val isNoBio = msg.contains("At least one biometric", ignoreCase = true) ||
                msg.contains("lock screen", ignoreCase = true) ||
                e.cause?.message?.contains("biometric", ignoreCase = true) == true
            Log.w(TAG, "Keystore snapshot error (noBio=$isNoBio): $msg")
            DeviceKeySnapshot(
                state = if (isNoBio) KeystoreKeyState.BIOMETRIC_NOT_ENROLLED else KeystoreKeyState.ERROR,
                errorMessage = if (isNoBio) {
                    "Enable fingerprint unlock in Android Settings first to create a hardware key."
                } else {
                    "Failed to access Android Keystore: $msg"
                }
            )
        }
    }

    fun regenerateKeyPair(): DeviceKeySnapshot {
        return try {
            val ks = loadKeyStore()
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS)
            }
            generateKeyPair()
            inspectExistingKey(loadKeyStore())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to regenerate Keystore keypair: ${e.message}")
            DeviceKeySnapshot(
                state = KeystoreKeyState.ERROR,
                errorMessage = "Failed to regenerate hardware key: ${e.message}"
            )
        }
    }

    /**
     * Prepare a SHA256withECDSA Signature bound to the hardware key for BiometricPrompt.CryptoObject.
     * Throws KeyPermanentlyInvalidatedException if OS biometric enrollment changed.
     * A-M5: Never silently generate an unenrolled keypair here (would cause 3x failed signatures -> 15m lockout).
     */
    @Throws(KeyPermanentlyInvalidatedException::class)
    fun prepareSignatureForBiometric(): Signature {
        val ks = loadKeyStore()
        if (!ks.containsAlias(KEY_ALIAS)) {
            throw IllegalStateException("Device key is not paired yet. Open Settings to pair this device.")
        }
        val privateKey = ks.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("Keystore private key missing for alias $KEY_ALIAS")
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        return signature
    }

    private fun resolveKeySecurityLevelLabel(privateKey: PrivateKey): String {
        return try {
            val factory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(privateKey, KeyInfo::class.java)
            when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE (Hardware)"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software"
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "Secure Hardware"
                else -> "Hardware Keystore"
            }
        } catch (_: Exception) {
            "Hardware Keystore"
        }
    }

    private fun inspectExistingKey(ks: KeyStore): DeviceKeySnapshot {
        val cert = ks.getCertificate(KEY_ALIAS)
            ?: return DeviceKeySnapshot(KeystoreKeyState.ERROR, errorMessage = "Key certificate not found.")
        val spkiDer = cert.publicKey.encoded
        val spkiB64 = Base64.encodeToString(spkiDer, Base64.NO_WRAP)
        val shaHex = computeSha256ColonHex(spkiDer)
        val sha4x8 = formatFingerprint4x8(shaHex)

        // Probe private key validity so KeyPermanentlyInvalidatedException is surfaced immediately
        val privateKey = ks.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: return DeviceKeySnapshot(KeystoreKeyState.ERROR, errorMessage = "Private key not found.")
        Signature.getInstance("SHA256withECDSA").initSign(privateKey)
        val secLevel = resolveKeySecurityLevelLabel(privateKey)

        return DeviceKeySnapshot(
            state = KeystoreKeyState.READY,
            spkiDerB64 = spkiB64,
            sha256ColonHex = shaHex,
            sha256Lines4x8 = sha4x8,
            securityLevelLabel = secLevel
        )
    }

    private fun generateKeyPair() {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        kpg.initialize(spec)
        kpg.generateKeyPair()
        Log.i(TAG, "Generated hardware-backed ECDSA P-256 keypair in Android Keystore")
    }
}
