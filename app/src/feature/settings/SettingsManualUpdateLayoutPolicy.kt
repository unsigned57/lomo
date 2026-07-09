package com.lomo.app.feature.settings

private const val MANUAL_UPDATE_SUBTITLE_MIN_LINES = 2

internal fun manualUpdateSubtitleMinLines(state: SettingsManualUpdateState): Int {
    return when (state) {
        SettingsManualUpdateState.Idle,
        SettingsManualUpdateState.Checking,
        SettingsManualUpdateState.UpToDate,
        is SettingsManualUpdateState.UpdateAvailable,
        is SettingsManualUpdateState.Error,
        -> MANUAL_UPDATE_SUBTITLE_MIN_LINES
    }
}
