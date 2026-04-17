package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncError
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.WebDavSyncErrorCode

internal fun UnifiedSyncState.canTriggerManualSync(): Boolean =
    this !is UnifiedSyncState.Running

@Composable
internal fun unifiedSyncNowSubtitle(
    provider: SyncBackendType,
    state: UnifiedSyncState,
    lastSyncTime: Long,
): String =
    when (state) {
        UnifiedSyncState.Idle ->
            SettingsErrorPresenter.relativeSyncSubtitle(
                lastSyncTime = lastSyncTime,
                syncedResId = provider.syncedSubtitleResId(),
                neverResId = provider.neverSubtitleResId(),
            )

        is UnifiedSyncState.Running -> stringResource(state.phase.subtitleResId(provider))
        is UnifiedSyncState.Success ->
            SettingsErrorPresenter.relativeSyncSubtitle(
                lastSyncTime = lastSyncTime,
                syncedResId = provider.syncedSubtitleResId(),
                neverResId = provider.neverSubtitleResId(),
            )
        is UnifiedSyncState.Error ->
            stringResource(
                provider.errorSubtitleResId(),
                provider.presentError(state.error),
            )
        is UnifiedSyncState.NotConfigured -> stringResource(provider.notConfiguredSubtitleResId())
        is UnifiedSyncState.ConflictDetected ->
            stringResource(
                if (state.isPreview) {
                    R.string.settings_s3_sync_status_initial_preview
                } else {
                    provider.conflictSubtitleResId()
                },
            )
    }

@Composable
internal fun presentUnifiedSyncError(error: UnifiedSyncError): String =
    error.provider.presentError(error)

@Composable
private fun SyncBackendType.presentError(error: UnifiedSyncError): String =
    when (this) {
        SyncBackendType.GIT ->
            SettingsErrorPresenter.gitSyncErrorMessage(
                code = enumValueOrDefault(error.providerCode, GitSyncErrorCode.UNKNOWN),
                detail = error.message,
            )
        SyncBackendType.WEBDAV ->
            SettingsErrorPresenter.webDavSyncErrorMessage(
                code = enumValueOrDefault(error.providerCode, WebDavSyncErrorCode.UNKNOWN),
                detail = error.message,
            )
        SyncBackendType.S3 ->
            SettingsErrorPresenter.s3SyncErrorMessage(
                code = enumValueOrDefault(error.providerCode, S3SyncErrorCode.UNKNOWN),
                detail = error.message,
            )
        SyncBackendType.INBOX,
        SyncBackendType.NONE,
        -> error.message
    }

private fun UnifiedSyncPhase.subtitleResId(provider: SyncBackendType): Int =
    when (this) {
        UnifiedSyncPhase.INITIALIZING ->
            when (provider) {
                SyncBackendType.GIT -> R.string.settings_git_sync_status_initializing
                SyncBackendType.WEBDAV -> R.string.settings_webdav_sync_status_initializing
                SyncBackendType.S3 -> R.string.settings_s3_sync_status_initializing
                SyncBackendType.INBOX,
                SyncBackendType.NONE,
                -> R.string.settings_s3_sync_status_initializing
            }
        UnifiedSyncPhase.CONNECTING ->
            when (provider) {
                SyncBackendType.WEBDAV -> R.string.settings_webdav_sync_status_connecting
                SyncBackendType.S3 -> R.string.settings_s3_sync_status_connecting
                else -> R.string.settings_s3_sync_status_connecting
            }
        UnifiedSyncPhase.LISTING ->
            when (provider) {
                SyncBackendType.WEBDAV -> R.string.settings_webdav_sync_status_listing
                SyncBackendType.S3 -> R.string.settings_s3_sync_status_listing
                else -> R.string.settings_s3_sync_status_listing
            }
        UnifiedSyncPhase.PULLING -> R.string.settings_git_sync_status_pulling
        UnifiedSyncPhase.COMMITTING -> R.string.settings_git_sync_status_committing
        UnifiedSyncPhase.PUSHING -> R.string.settings_git_sync_status_pushing
        UnifiedSyncPhase.UPLOADING ->
            when (provider) {
                SyncBackendType.WEBDAV -> R.string.settings_webdav_sync_status_uploading
                SyncBackendType.S3 -> R.string.settings_s3_sync_status_uploading
                else -> R.string.settings_s3_sync_status_uploading
            }
        UnifiedSyncPhase.DOWNLOADING ->
            when (provider) {
                SyncBackendType.WEBDAV -> R.string.settings_webdav_sync_status_downloading
                SyncBackendType.S3 -> R.string.settings_s3_sync_status_downloading
                else -> R.string.settings_s3_sync_status_downloading
            }
        UnifiedSyncPhase.DELETING ->
            when (provider) {
                SyncBackendType.WEBDAV -> R.string.settings_webdav_sync_status_deleting
                SyncBackendType.S3 -> R.string.settings_s3_sync_status_deleting
                else -> R.string.settings_s3_sync_status_deleting
            }
    }

private fun SyncBackendType.syncedSubtitleResId(): Int =
    when (this) {
        SyncBackendType.GIT -> R.string.settings_git_sync_now_subtitle
        SyncBackendType.WEBDAV -> R.string.settings_webdav_sync_now_subtitle
        SyncBackendType.S3 -> R.string.settings_s3_sync_now_subtitle
        SyncBackendType.INBOX,
        SyncBackendType.NONE,
        -> R.string.settings_s3_sync_now_subtitle
    }

private fun SyncBackendType.neverSubtitleResId(): Int =
    when (this) {
        SyncBackendType.GIT -> R.string.settings_git_sync_never
        SyncBackendType.WEBDAV -> R.string.settings_webdav_sync_never
        SyncBackendType.S3 -> R.string.settings_s3_sync_never
        SyncBackendType.INBOX,
        SyncBackendType.NONE,
        -> R.string.settings_s3_sync_never
    }

private fun SyncBackendType.errorSubtitleResId(): Int =
    when (this) {
        SyncBackendType.GIT -> R.string.settings_git_sync_status_error
        SyncBackendType.WEBDAV -> R.string.settings_webdav_sync_status_error
        SyncBackendType.S3 -> R.string.settings_s3_sync_status_error
        SyncBackendType.INBOX,
        SyncBackendType.NONE,
        -> R.string.settings_s3_sync_status_error
    }

private fun SyncBackendType.notConfiguredSubtitleResId(): Int =
    when (this) {
        SyncBackendType.GIT -> R.string.settings_git_sync_status_not_configured
        SyncBackendType.WEBDAV -> R.string.settings_webdav_sync_status_not_configured
        SyncBackendType.S3 -> R.string.settings_s3_sync_status_not_configured
        SyncBackendType.INBOX,
        SyncBackendType.NONE,
        -> R.string.settings_s3_sync_status_not_configured
    }

private fun SyncBackendType.conflictSubtitleResId(): Int =
    when (this) {
        SyncBackendType.S3 -> R.string.settings_s3_sync_status_conflict
        SyncBackendType.GIT -> R.string.settings_git_sync_status_error
        SyncBackendType.WEBDAV -> R.string.settings_webdav_sync_status_error
        SyncBackendType.INBOX,
        SyncBackendType.NONE,
        -> R.string.settings_s3_sync_status_conflict
    }

private inline fun <reified T : Enum<T>> enumValueOrDefault(
    raw: String?,
    default: T,
): T = raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default
