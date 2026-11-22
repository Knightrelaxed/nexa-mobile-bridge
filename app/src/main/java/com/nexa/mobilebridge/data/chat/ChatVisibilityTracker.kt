package com.nexa.mobilebridge.data.chat

import com.nexa.mobilebridge.NexaMainTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "is the user looking at the chat right now?" so notifications are
 * suppressed while the CHAT tab is visible, plus the unread counter and notification deep-link request.
 */
@Singleton
class ChatVisibilityTracker @Inject constructor() {

    private val _isAppForeground = MutableStateFlow(false)
    private val _visibleTab = MutableStateFlow<NexaMainTab?>(null)
    private val _unreadCount = MutableStateFlow(0)
    private val _openTabRequest = MutableStateFlow<NexaMainTab?>(null)

    val visibleTab: StateFlow<NexaMainTab?> = _visibleTab.asStateFlow()
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()
    val openTabRequest: StateFlow<NexaMainTab?> = _openTabRequest.asStateFlow()

    @Volatile
    var lastExternalIntentLaunchedAtMs: Long = 0L
        private set

    fun markExternalIntentLaunched() {
        lastExternalIntentLaunchedAtMs = System.currentTimeMillis()
    }

    /** True only while the app is in the foreground AND the CHAT tab is the visible surface. */
    val chatVisible: kotlinx.coroutines.flow.Flow<Boolean> =
        combine(_isAppForeground, _visibleTab) { fg, tab -> fg && tab == NexaMainTab.CHAT }
            .distinctUntilChanged()

    fun isChatVisible(): Boolean = _isAppForeground.value && _visibleTab.value == NexaMainTab.CHAT

    fun setAppForeground(foreground: Boolean) {
        _isAppForeground.value = foreground
    }

    /** @param tab visible main tab, or null while a full-screen route (e.g. Settings) covers the shell. */
    fun setVisibleTab(tab: NexaMainTab?) {
        _visibleTab.value = tab
    }

    fun incrementUnread() {
        _unreadCount.value = (_unreadCount.value + 1).coerceAtMost(99)
    }

    fun clearUnread() {
        _unreadCount.value = 0
    }

    fun requestOpenTab(tab: NexaMainTab) {
        _openTabRequest.value = tab
    }

    fun consumeOpenTabRequest() {
        _openTabRequest.value = null
    }
}
