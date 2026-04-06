package com.lomo.app.feature.settings

import com.lomo.domain.usecase.LanSharePairingCodePolicy
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.repository.LanShareService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

class SettingsLanShareCoordinator(
    private val shareServiceManager: LanShareService,
    scope: CoroutineScope,
) : SettingsLanShareFeatureSupport {
    val lanShareE2eEnabled: StateFlow<Boolean> =
        shareServiceManager
            .lanShareE2eEnabled
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                PreferenceDefaults.LAN_SHARE_E2E_ENABLED,
            )

    val lanSharePairingConfigured: StateFlow<Boolean> =
        shareServiceManager
            .lanSharePairingConfigured
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                false,
            )

    val lanShareDeviceName: StateFlow<String> =
        shareServiceManager
            .lanShareDeviceName
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                "",
            )

    private val _pairingCodeError = MutableStateFlow<String?>(null)
    val pairingCodeError: StateFlow<String?> = _pairingCodeError.asStateFlow()

    suspend fun updateLanShareE2eEnabled(enabled: Boolean) {
        shareServiceManager.setLanShareE2eEnabled(enabled)
    }

    suspend fun updateLanSharePairingCode(pairingCode: String) {
        runCatching {
            shareServiceManager.setLanSharePairingCode(pairingCode)
            _pairingCodeError.value = null
        }.onFailure { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
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

    override fun clearPairingCodeError() {
        _pairingCodeError.value = null
    }
}
