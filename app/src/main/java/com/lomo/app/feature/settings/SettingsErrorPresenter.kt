package com.lomo.app.feature.settings

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.app.feature.lanshare.LanSharePairingCodePolicy
import com.lomo.domain.model.SyncEngineState

object SettingsErrorPresenter {
    @Composable
    fun gitSyncNowSubtitle(
        state: SyncEngineState,
        lastSyncTime: Long,
        localizeError: (String) -> String = { it },
    ): String =
        when (state) {
            is SyncEngineState.Syncing.Pulling -> stringResource(R.string.settings_git_sync_status_pulling)
            is SyncEngineState.Syncing.Committing -> stringResource(R.string.settings_git_sync_status_committing)
            is SyncEngineState.Syncing.Pushing -> stringResource(R.string.settings_git_sync_status_pushing)
            is SyncEngineState.Initializing -> stringResource(R.string.settings_git_sync_status_initializing)
            is SyncEngineState.Error ->
                stringResource(
                    R.string.settings_git_sync_status_error,
                    localizeError(state.message),
                )
            is SyncEngineState.NotConfigured -> stringResource(R.string.settings_git_sync_status_not_configured)
            else -> {
                if (lastSyncTime > 0) {
                    val relative = DateUtils.getRelativeTimeSpanString(
                        lastSyncTime,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
                    stringResource(R.string.settings_git_sync_now_subtitle, relative)
                } else {
                    stringResource(R.string.settings_git_sync_never)
                }
            }
        }

    @Composable
    fun pairingCodeMessage(raw: String): String {
        return when (LanSharePairingCodePolicy.userMessageKey(raw)) {
            LanSharePairingCodePolicy.UserMessageKey.INVALID_PAIRING_CODE -> {
                stringResource(R.string.share_error_invalid_pairing_code)
            }
            LanSharePairingCodePolicy.UserMessageKey.UNKNOWN -> {
                stringResource(R.string.share_error_unknown)
            }
        }
    }
}
