package com.lomo.app.feature.settings

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.WebDavSyncErrorCode
import com.lomo.domain.usecase.LanSharePairingCodePolicy

object SettingsErrorPresenter {
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
    internal fun relativeSyncSubtitle(
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
