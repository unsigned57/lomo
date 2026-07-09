package com.lomo.app.feature.common

import kotlinx.coroutines.flow.SharingStarted

internal const val APP_FLOW_STOP_TIMEOUT_MILLIS = 5_000L

internal fun appWhileSubscribed(): SharingStarted =
    SharingStarted.WhileSubscribed(APP_FLOW_STOP_TIMEOUT_MILLIS)
