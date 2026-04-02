package com.lomo.app.feature.settings

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncState
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
    fun s3SyncNowSubtitle(
        state: S3SyncState,
        lastSyncTime: Long,
    ): String =
        when (state) {
            S3SyncState.Connecting ->
                stringResource(R.string.settings_s3_sync_status_connecting)
            S3SyncState.Listing ->
                stringResource(R.string.settings_s3_sync_status_listing)
            S3SyncState.Uploading ->
                stringResource(R.string.settings_s3_sync_status_uploading)
            S3SyncState.Downloading ->
                stringResource(R.string.settings_s3_sync_status_downloading)
            S3SyncState.Deleting ->
                stringResource(R.string.settings_s3_sync_status_deleting)
            S3SyncState.Initializing ->
                stringResource(R.string.settings_s3_sync_status_initializing)
            is S3SyncState.Error ->
                stringResource(
                    R.string.settings_s3_sync_status_error,
                    s3SyncErrorMessage(state.code, state.message),
                )
            S3SyncState.NotConfigured ->
                stringResource(R.string.settings_s3_sync_status_not_configured)
            is S3SyncState.ConflictDetected ->
                stringResource(R.string.settings_s3_sync_status_conflict)
            else ->
                relativeSyncSubtitle(
                    lastSyncTime,
                    R.string.settings_s3_sync_now_subtitle,
                    R.string.settings_s3_sync_never,
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
    fun s3SyncErrorMessage(
        code: S3SyncErrorCode,
        detail: String?,
    ): String =
        when (code) {
            S3SyncErrorCode.NOT_CONFIGURED ->
                stringResource(R.string.settings_s3_not_configured_message)
            S3SyncErrorCode.AUTH_FAILED ->
                sanitizedDetail(detail) ?: stringResource(R.string.settings_s3_auth_failed_message)
            S3SyncErrorCode.BUCKET_ACCESS_FAILED ->
                sanitizedDetail(detail) ?: stringResource(R.string.settings_s3_bucket_access_failed_message)
            S3SyncErrorCode.ENCRYPTION_FAILED ->
                sanitizedDetail(detail) ?: stringResource(R.string.settings_s3_encryption_failed_message)
            S3SyncErrorCode.CONNECTION_FAILED,
            S3SyncErrorCode.UNKNOWN,
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

private fun sanitizedDetail(rawDetail: String?): String? {
    val lines =
        rawDetail
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.toList()
            .orEmpty()
    if (lines.isEmpty()) return null
    return lines.firstOrNull(::isActionableErrorDetailLine)
        ?: lines.firstOrNull { !it.isBareExceptionNoise() && !it.isGenericFailureWrapper() }
}

private fun isActionableErrorDetailLine(line: String): Boolean =
    line.isNotBlank() &&
        !line.isBareExceptionNoise() &&
        !line.isGenericFailureWrapper()

private fun String.isBareExceptionNoise(): Boolean =
    matches(Regex("""[\w.$]+(?:Exception|Error)(?::)?"""))

private fun String.isGenericFailureWrapper(): Boolean {
    val normalized = lowercase()
    return normalized.contains("s3 sync failed") ||
        normalized.contains("s3 connection failed") ||
        normalized.contains("webdav sync failed") ||
        normalized.contains("git sync failed")
}
