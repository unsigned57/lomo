package com.lomo.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.share.LanShareUiCoordinator
import com.lomo.domain.model.PreferenceDefaults
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class LanShareAvailabilityViewModel
    @Inject
    constructor(
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
