package com.lomo.app.feature.settings

import com.lomo.app.feature.lanshare.LanSharePairingCodePolicy
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.model.PreferenceDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

class SettingsLanShareCoordinator(
    private val shareServiceManager: LanShareService,
    scope: CoroutineScope,
) {
    val lanShareE2eEnabled: StateFlow<Boolean> =
        shareServiceManager
            .lanShareE2eEnabled
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                PreferenceDefaults.LAN_SHARE_E2E_ENABLED,
            )

    val lanSharePairingConfigured: StateFlow<Boolean> =
        shareServiceManager
            .lanSharePairingConfigured
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                false,
            )

    val lanShareDeviceName: StateFlow<String> =
        shareServiceManager
            .lanShareDeviceName
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5000),
                "",
            )

    private val _pairingCodeError = MutableStateFlow<String?>(null)
    val pairingCodeError: StateFlow<String?> = _pairingCodeError.asStateFlow()

    suspend fun updateLanShareE2eEnabled(enabled: Boolean) {
        shareServiceManager.setLanShareE2eEnabled(enabled)
    }

    suspend fun updateLanSharePairingCode(pairingCode: String) {
        try {
            shareServiceManager.setLanSharePairingCode(pairingCode)
            _pairingCodeError.value = null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Exception) {
            _pairingCodeError.value = LanSharePairingCodePolicy.saveFailureMessage(throwable)
        }
    }

    suspend fun clearLanSharePairingCode() {
        shareServiceManager.clearLanSharePairingCode()
        _pairingCodeError.value = null
    }

    suspend fun updateLanShareDeviceName(deviceName: String) {
        shareServiceManager.setLanShareDeviceName(deviceName)
    }

    fun clearPairingCodeError() {
        _pairingCodeError.value = null
    }
}
