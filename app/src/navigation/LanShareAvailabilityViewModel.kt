package com.lomo.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.share.LanShareUiCoordinator
import com.lomo.domain.model.PreferenceDefaults

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class LanShareAvailabilityViewModel(
    lanShareUiCoordinator: LanShareUiCoordinator,
) : ViewModel() {
        val lanShareEnabled: StateFlow<Boolean> =
            lanShareUiCoordinator
                .lanShareEnabled
                .stateIn(
                    scope = viewModelScope,
                    started = appWhileSubscribed(),
                    initialValue = PreferenceDefaults.LAN_SHARE_ENABLED,
                )
    }
