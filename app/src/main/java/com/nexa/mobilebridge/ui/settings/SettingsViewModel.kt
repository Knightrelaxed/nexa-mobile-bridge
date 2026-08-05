package com.nexa.mobilebridge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val authToken: String = "",
    val hmacSecret: String = "",
    val telemetryInterval: Int = 60,
    val isSaved: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: NexaPreferencesDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.getConfig().let { config ->
                _uiState.update {
                    it.copy(
                        serverUrl = config.serverUrl,
                        authToken = config.authToken,
                        hmacSecret = config.hmacSecret
                    )
                }
            }
        }
    }

    fun onServerUrlChanged(url: String) {
        _uiState.update { it.copy(serverUrl = url, isSaved = false) }
    }

    fun onAuthTokenChanged(token: String) {
        _uiState.update { it.copy(authToken = token, isSaved = false) }
    }

    fun onHmacSecretChanged(secret: String) {
        _uiState.update { it.copy(hmacSecret = secret, isSaved = false) }
    }

    fun saveSettings() {
        viewModelScope.launch {
            dataStore.saveConfig(
                url = _uiState.value.serverUrl,
                token = _uiState.value.authToken,
                secret = _uiState.value.hmacSecret
            )
            _uiState.update { it.copy(isSaved = true) }
        }
    }
}
