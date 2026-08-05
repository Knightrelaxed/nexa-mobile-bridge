package com.nexa.mobilebridge.core.util

sealed interface ConnectionState {
    object Connecting : ConnectionState
    object Connected : ConnectionState
    data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : ConnectionState
    data class Disconnected(val reason: String?) : ConnectionState
    data class AuthFailed(val reason: String) : ConnectionState
}
