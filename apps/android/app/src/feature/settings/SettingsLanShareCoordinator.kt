package com.lomo.app.feature.settings

import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.repository.LanShareService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SettingsLanShareCoordinator(
    private val shareServiceManager: LanShareService,
    scope: CoroutineScope,
) {
    val lanShareEnabled: StateFlow<Boolean> =
        shareServiceManager
            .lanShareEnabled
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                PreferenceDefaults.LAN_SHARE_ENABLED,
            )

    val lanShareDeviceName: StateFlow<String> =
        shareServiceManager
            .lanShareDeviceName
            .stateIn(
                scope,
                settingsWhileSubscribed(),
                "",
            )

    suspend fun updateLanShareEnabled(enabled: Boolean) {
        shareServiceManager.setLanShareEnabled(enabled)
    }

    suspend fun updateLanShareDeviceName(deviceName: String) {
        shareServiceManager.setLanShareDeviceName(deviceName)
    }
}
