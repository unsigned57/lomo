package com.lomo.app.feature.settings

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.model.WebDavSyncErrorCode
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.usecase.LanSharePairingCodePolicy

object SettingsErrorPresenter {
    @Composable
    fun gitSyncNowSubtitle(
        state: SyncEngineState,
        lastSyncTime: Long,
    ): String =
        when (state) {
            is SyncEngineState.Syncing.Pulling ->
                stringResource(R.string.settings_git_sync_status_pulling)
            is SyncEngineState.Syncing.Committing ->
                stringResource(R.string.settings_git_sync_status_committing)
            is SyncEngineState.Syncing.Pushing ->
                stringResource(R.string.settings_git_sync_status_pushing)
            is SyncEngineState.Initializing ->
                stringResource(R.string.settings_git_sync_status_initializing)
            is SyncEngineState.Error ->
                stringResource(
                    R.string.settings_git_sync_status_error,
                    gitSyncErrorMessage(state.code, state.message),
                )
            is SyncEngineState.NotConfigured ->
                stringResource(R.string.settings_git_sync_status_not_configured)
            else ->
                relativeSyncSubtitle(
                    lastSyncTime,
                    R.string.settings_git_sync_now_subtitle,
                    R.string.settings_git_sync_never,
                )
        }

    @Composable
    fun webDavSyncNowSubtitle(
        state: WebDavSyncState,
        lastSyncTime: Long,
    ): String =
        when (state) {
            WebDavSyncState.Connecting ->
                stringResource(R.string.settings_webdav_sync_status_connecting)
            WebDavSyncState.Listing ->
                stringResource(R.string.settings_webdav_sync_status_listing)
            WebDavSyncState.Uploading ->
                stringResource(R.string.settings_webdav_sync_status_uploading)
            WebDavSyncState.Downloading ->
                stringResource(R.string.settings_webdav_sync_status_downloading)
            WebDavSyncState.Deleting ->
                stringResource(R.string.settings_webdav_sync_status_deleting)
            WebDavSyncState.Initializing ->
                stringResource(R.string.settings_webdav_sync_status_initializing)
            is WebDavSyncState.Error ->
                stringResource(
                    R.string.settings_webdav_sync_status_error,
                    webDavSyncErrorMessage(state.code, state.message),
                )
            WebDavSyncState.NotConfigured ->
                stringResource(R.string.settings_webdav_sync_status_not_configured)
            else ->
                relativeSyncSubtitle(
                    lastSyncTime,
                    R.string.settings_webdav_sync_now_subtitle,
                    R.string.settings_webdav_sync_never,
                )
        }

    @Composable
    fun pairingCodeMessage(raw: String): String =
        when (LanSharePairingCodePolicy.userMessageKey(raw)) {
            LanSharePairingCodePolicy.UserMessageKey.INVALID_PAIRING_CODE ->
                stringResource(R.string.share_error_invalid_pairing_code)
            LanSharePairingCodePolicy.UserMessageKey.UNKNOWN -> stringResource(R.string.share_error_unknown)
        }

    @Composable
    fun gitSyncErrorMessage(
        code: GitSyncErrorCode,
        detail: String?,
    ): String =
        when (code) {
            GitSyncErrorCode.NOT_CONFIGURED ->
                stringResource(R.string.settings_git_not_configured_message)
            GitSyncErrorCode.PAT_REQUIRED ->
                stringResource(R.string.settings_git_pat_required_message)
            GitSyncErrorCode.DIRECT_PATH_REQUIRED ->
                stringResource(R.string.settings_git_sync_direct_path_required)
            GitSyncErrorCode.REMOTE_URL_NOT_CONFIGURED ->
                stringResource(R.string.settings_git_repository_url_not_configured)
            GitSyncErrorCode.MEMO_DIRECTORY_NOT_CONFIGURED ->
                stringResource(R.string.settings_git_memo_directory_not_configured)
            GitSyncErrorCode.NOT_A_GIT_REPOSITORY ->
                stringResource(R.string.settings_git_repository_not_initialized)
            GitSyncErrorCode.CONFLICT ->
                stringResource(R.string.settings_git_sync_conflict_summary)
            GitSyncErrorCode.UNKNOWN ->
                sanitizedDetail(detail) ?: stringResource(R.string.error_unknown)
        }

    @Composable
    fun webDavSyncErrorMessage(
        code: WebDavSyncErrorCode,
        detail: String?,
    ): String =
        when (code) {
            WebDavSyncErrorCode.NOT_CONFIGURED ->
                stringResource(R.string.settings_webdav_not_configured_message)
            WebDavSyncErrorCode.CONNECTION_FAILED,
            WebDavSyncErrorCode.UNKNOWN,
            -> sanitizedDetail(detail) ?: stringResource(R.string.error_unknown)
        }

    @Composable
    private fun relativeSyncSubtitle(
        lastSyncTime: Long,
        syncedResId: Int,
        neverResId: Int,
    ): String {
        if (lastSyncTime <= 0) return stringResource(neverResId)
        val relative =
            DateUtils
                .getRelativeTimeSpanString(
                    lastSyncTime,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
        ).toString()
        return stringResource(syncedResId, relative)
    }
}

private fun sanitizedDetail(rawDetail: String?): String? =
    rawDetail
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.contains("Exception") }
