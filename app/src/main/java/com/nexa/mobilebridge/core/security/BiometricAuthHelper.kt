package com.nexa.mobilebridge.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.Signature

/**
 * BiometricPrompt wrapper enforcing BIOMETRIC_STRONG only (no PIN/pattern fallback, FR-AUTH-02).
 * Includes Activity lifecycle and availability guards + returns BiometricPrompt handle for clean cancellation (A-H8).
 */
object BiometricAuthHelper {

    fun isStrongBiometricAvailable(activity: FragmentActivity): Boolean {
        val bm = BiometricManager.from(activity)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Authenticate using CryptoObject-bound Signature for ticket signing or Finance Step-Up.
     */
    fun authenticateAndSign(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String = "Cancel",
        signature: Signature,
        payloadToSign: ByteArray,
        onSigned: (derSignatureBytes: ByteArray) -> Unit,
        onError: (code: Int, message: String) -> Unit
    ): BiometricPrompt? {
        if (activity.isFinishing || activity.isDestroyed) {
            onError(-2, "Screen closed before biometric verification started.")
            return null
        }
        if (!isStrongBiometricAvailable(activity)) {
            onError(-3, "Fingerprint authentication is not set up on this device.")
            return null
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                try {
                    val unlockedSig = result.cryptoObject?.signature
                        ?: throw IllegalStateException("CryptoObject signature unavailable")
                    unlockedSig.update(payloadToSign)
                    val signedBytes = unlockedSig.sign()
                    onSigned(signedBytes)
                } catch (e: Exception) {
                    onError(-1, "Signing failed: ${e.message}")
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errorCode, errString.toString())
            }

            override fun onAuthenticationFailed() {
                // System prompt stays open for retry
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        return try {
            val prompt = BiometricPrompt(activity, executor, callback)
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
            prompt
        } catch (e: Exception) {
            onError(-4, "Failed to open biometric prompt: ${e.message}")
            null
        }
    }

    /**
     * Guard sensitive UI actions (domain unlock, 10s token reveal) with BIOMETRIC_STRONG.
     */
    fun authenticateGuard(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String = "Cancel",
        onSuccess: () -> Unit,
        onError: (message: String) -> Unit
    ): BiometricPrompt? {
        if (activity.isFinishing || activity.isDestroyed) {
            onError("Screen is closing.")
            return null
        }
        if (!isStrongBiometricAvailable(activity)) {
            onError("Fingerprint authentication is not available on this device.")
            return null
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        return try {
            val prompt = BiometricPrompt(activity, executor, callback)
            prompt.authenticate(promptInfo)
            prompt
        } catch (e: Exception) {
            onError("Failed to open biometric prompt: ${e.message}")
            null
        }
    }
}
