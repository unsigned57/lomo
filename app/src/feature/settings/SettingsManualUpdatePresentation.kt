package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R

@Composable
internal fun manualUpdateSubtitle(state: SettingsManualUpdateState): String =
    when (state) {
        SettingsManualUpdateState.Idle -> stringResource(R.string.settings_check_updates_now_subtitle)
        SettingsManualUpdateState.Checking -> stringResource(R.string.settings_checking_updates)
        SettingsManualUpdateState.UpToDate -> stringResource(R.string.settings_app_up_to_date)
        is SettingsManualUpdateState.UpdateAvailable ->
            stringResource(R.string.settings_update_available_subtitle, state.dialogState.version)
        is SettingsManualUpdateState.Error ->
            state.message
                ?.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.settings_check_updates_failed_with_reason, it) }
                ?: stringResource(R.string.settings_check_updates_failed)
    }
