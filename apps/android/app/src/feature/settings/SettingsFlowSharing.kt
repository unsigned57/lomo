package com.lomo.app.feature.settings

import kotlinx.coroutines.flow.SharingStarted

internal const val SETTINGS_FLOW_STOP_TIMEOUT_MILLIS = 5_000L

internal fun settingsWhileSubscribed(): SharingStarted =
    SharingStarted.WhileSubscribed(SETTINGS_FLOW_STOP_TIMEOUT_MILLIS)
