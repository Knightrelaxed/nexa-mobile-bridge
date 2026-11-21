package com.nexa.mobilebridge.core.auth

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import androidx.fragment.app.FragmentActivity
import com.nexa.mobilebridge.core.network.NexaWebSocketClient
import com.nexa.mobilebridge.core.security.BiometricAuthHelper
import com.nexa.mobilebridge.core.security.DeviceKeystoreManager
import com.nexa.mobilebridge.core.security.KeystoreKeyState
import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class IssuedTicket(
    val ticket: String,
    val scope: String,
    val sid: String,
    val expSec: Long
)

sealed interface EnrollmentOutcome {
    data class Success(val deviceId: String, val fingerprintSha256: String, val enrolledAtSec: Long) : EnrollmentOutcome
    data class Failure(val code: String, val message: String) : EnrollmentOutcome
}

sealed interface TicketOutcome {
    data class Success(val ticket: IssuedTicket) : TicketOutcome
    data class KeyInvalidated(val message: String) : TicketOutcome
    data class Failure(val code: String, val message: String) : TicketOutcome
}

/**
 * Coordinates hardware-backed device enrollment and Ed25519 ticket issuance
 * over Connection A (/ws JSON text frames - FR-AUTH-01..06).
 * Uses a pre-registered CompletableDeferred map (A-H8) to eliminate SharedFlow subscription races.
 */
@Singleton
class TicketAuthRepository @Inject constructor(
    private val webSocketClient: NexaWebSocketClient,
    private val keystoreManager: DeviceKeystoreManager,
    private val preferencesDataStore: NexaPreferencesDataStore
) {
    companion object {
        private const val AUTH_TIMEOUT_MS = 10_000L
    }

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

    init {
        repoScope.launch {
            webSocketClient.incomingAuthEvents.collect { raw ->
                val reqId = runCatching { JSONObject(raw).optString("req_id", "") }.getOrDefault("")
                if (reqId.isNotEmpty()) {
                    pendingRequests.remove(reqId)?.complete(raw)
                }
            }
        }
    }

    suspend fun submitEnrollment(
        deviceId: String,
        enrollCode: String
    ): EnrollmentOutcome {
        if (!webSocketClient.isConnected()) {
            return EnrollmentOutcome.Failure("OFFLINE", "WebSocket belum terhubung ke server N.E.X.A.")
        }

        val keySnap = keystoreManager.getOrCreateKeySnapshot()
        if (keySnap.state != KeystoreKeyState.READY || keySnap.spkiDerB64.isBlank()) {
            return EnrollmentOutcome.Failure(
                keySnap.state.name,
                keySnap.errorMessage ?: "Kunci Android Keystore belum siap."
            )
        }

        val reqId = "enr_${UUID.randomUUID().toString().take(8)}"
        val payload = JSONObject().apply {
            put("type", "ENROLL_SUBMIT")
            put("req_id", reqId)
            put("device_id", deviceId.trim())
            put("enroll_code", enrollCode.trim())
            put("public_key_spki_der_b64", keySnap.spkiDerB64)
        }.toString()

        val responseRaw = awaitAuthResponse(reqId, payload)
            ?: return EnrollmentOutcome.Failure("TIMEOUT", "Server tidak merespons permintaan pendaftaran dalam 10 detik.")

        val resp = JSONObject(responseRaw)
        return when (resp.optString("type")) {
            "ENROLL_OK" -> {
                val fp = resp.optString("fingerprint_sha256", keySnap.sha256ColonHex)
                val enrolledAt = resp.optLong("enrolled_at", System.currentTimeMillis() / 1000L)
                preferencesDataStore.saveEnrollmentState(
                    status = "ACTIVE",
                    enrolledAtEpochSec = enrolledAt,
                    fingerprint = fp
                )
                EnrollmentOutcome.Success(deviceId.trim(), fp, enrolledAt)
            }
            else -> EnrollmentOutcome.Failure(
                code = resp.optString("code", "ENROLL_ERR"),
                message = resp.optString("message", "Pendaftaran perangkat ditolak oleh server.")
            )
        }
    }

    suspend fun requestTicket(
        activity: FragmentActivity,
        scope: String,
        resumeSid: String = "",
        promptTitle: String = "Verifikasi Sidik Jari N.E.X.A",
        promptSubtitle: String = "Otorisasi akses $scope"
    ): TicketOutcome {
        if (!webSocketClient.isConnected()) {
            return TicketOutcome.Failure("OFFLINE", "WebSocket inti belum terhubung.")
        }
        val config = preferencesDataStore.getConfig()
        val deviceId = config.deviceName.trim().ifBlank { "samsung-a33-tuan" }

        // Step 1: Request 32-byte single-use challenge nonce
        val reqId = "tk_${UUID.randomUUID().toString().take(8)}"
        val reqPayload = JSONObject().apply {
            put("type", "TICKET_REQ")
            put("req_id", reqId)
            put("device_id", deviceId)
            put("scope", scope)
            if (resumeSid.isNotBlank()) put("resume_sid", resumeSid)
        }.toString()

        val challengeRaw = awaitAuthResponse(reqId, reqPayload)
            ?: return TicketOutcome.Failure("TIMEOUT", "Timeout menunggu tantangan nonce dari server.")

        val challengeObj = try {
            JSONObject(challengeRaw)
        } catch (_: Exception) {
            return TicketOutcome.Failure("MALFORMED_CHALLENGE", "Format tantangan nonce dari server tidak valid.")
        }
        if (challengeObj.optString("type") != "CHALLENGE") {
            return TicketOutcome.Failure(
                code = challengeObj.optString("code", "AUTH_ERR"),
                message = challengeObj.optString("message", "Server menolak permintaan tiket.")
            )
        }

        val nonce = challengeObj.optString("nonce").trim()
        val assignedSid = challengeObj.optString("sid", "").trim()
        val assignedScope = challengeObj.optString("scope", "").trim()

        if (nonce.isBlank()) {
            return TicketOutcome.Failure("INVALID_NONCE", "Server mengirim tantangan nonce kosong.")
        }
        // Reject scope escalation mismatch so biometric prompt always matches the signed scope
        if (assignedScope != scope) {
            return TicketOutcome.Failure(
                code = "SCOPE_MISMATCH",
                message = "Scope tantangan server ($assignedScope) tidak cocok dengan permintaan ($scope)."
            )
        }

        // Step 2: Prepare hardware-backed ECDSA P-256 signature & authenticate with BIOMETRIC_STRONG
        val signature = try {
            keystoreManager.prepareSignatureForBiometric()
        } catch (_: KeyPermanentlyInvalidatedException) {
            preferencesDataStore.saveEnrollmentState("INVALIDATED", config.enrolledAtEpochSec, config.enrolledFingerprint)
            return TicketOutcome.KeyInvalidated(
                "Kunci perangkat gugur karena perubahan sidik jari di Android. Jalankan nexa-enroll re-pair."
            )
        } catch (e: Exception) {
            return TicketOutcome.Failure("KEYSTORE_ERR", "Gagal menyiapkan kunci: ${e.message}")
        }

        val canonicalMsg = DeviceKeystoreManager.buildCanonicalTicketMessage(nonce, assignedScope, assignedSid)

        val derSignature = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Result<ByteArray>> { cont ->
                val prompt = BiometricAuthHelper.authenticateAndSign(
                    activity = activity,
                    title = promptTitle,
                    subtitle = promptSubtitle,
                    signature = signature,
                    payloadToSign = canonicalMsg,
                    onSigned = { signedBytes ->
                        if (cont.isActive) cont.resume(Result.success(signedBytes))
                    },
                    onError = { _, errMsg ->
                        if (cont.isActive) cont.resume(Result.failure(IllegalStateException(errMsg)))
                    }
                )
                cont.invokeOnCancellation {
                    prompt?.cancelAuthentication()
                }
            }
        }

        val sigBytes = derSignature.getOrElse { err ->
            return TicketOutcome.Failure("BIOMETRIC_ABORTED", err.message ?: "Verifikasi sidik jari dibatalkan.")
        }

        // Step 3: Send signed challenge response and receive Ed25519 ticket
        val respReqId = "resp_${UUID.randomUUID().toString().take(8)}"
        val respPayload = JSONObject().apply {
            put("type", "CHALLENGE_RESP")
            put("req_id", respReqId)
            put("device_id", deviceId)
            put("nonce", nonce)
            put("signature_der_b64", Base64.encodeToString(sigBytes, Base64.NO_WRAP))
        }.toString()

        val ticketRaw = awaitAuthResponse(respReqId, respPayload)
            ?: return TicketOutcome.Failure("TIMEOUT", "Timeout menunggu penerbitan tiket dari server.")

        val ticketObj = try {
            JSONObject(ticketRaw)
        } catch (_: Exception) {
            return TicketOutcome.Failure("MALFORMED_TICKET", "Format respons tiket dari server tidak valid.")
        }
        return if (ticketObj.optString("type") == "TICKET") {
            val issuedToken = ticketObj.optString("ticket").trim()
            if (issuedToken.isBlank()) {
                return TicketOutcome.Failure("EMPTY_TICKET", "Server mengembalikan tiket kosong.")
            }
            TicketOutcome.Success(
                IssuedTicket(
                    ticket = issuedToken,
                    scope = ticketObj.optString("scope", assignedScope),
                    sid = ticketObj.optString("sid", assignedSid),
                    expSec = ticketObj.optLong("exp", 0L)
                )
            )
        } else {
            TicketOutcome.Failure(
                code = ticketObj.optString("code", "AUTH_ERR"),
                message = ticketObj.optString("message", "Verifikasi tantangan ditolak server.")
            )
        }
    }

    private suspend fun awaitAuthResponse(reqId: String, outboundJson: String): String? {
        val deferred = CompletableDeferred<String>()
        pendingRequests[reqId] = deferred
        return try {
            val sent = webSocketClient.send(outboundJson)
            if (!sent) {
                pendingRequests.remove(reqId)
                null
            } else {
                withTimeoutOrNull(AUTH_TIMEOUT_MS) {
                    deferred.await()
                }
            }
        } finally {
            pendingRequests.remove(reqId)
        }
    }
}
