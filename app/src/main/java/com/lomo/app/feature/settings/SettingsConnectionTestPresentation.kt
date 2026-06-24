package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.WebDavSyncErrorCode

@Composable
internal fun connectionTestSubtitle(
    provider: SyncBackendType,
    state: RemoteProviderConnectionTestState,
): String =
    when (state) {
        RemoteProviderConnectionTestState.Idle -> ""
        RemoteProviderConnectionTestState.Testing -> stringResource(provider.connectionTestingResId())
        is RemoteProviderConnectionTestState.Success -> stringResource(provider.connectionSuccessResId())
        is RemoteProviderConnectionTestState.Error ->
            stringResource(
                state.provider.connectionFailedResId(),
                connectionErrorMessage(state),
            )
    }

@Composable
private fun connectionErrorMessage(state: RemoteProviderConnectionTestState.Error): String =
    when (state.provider) {
        SyncBackendType.GIT ->
            SettingsErrorPresenter.gitSyncErrorMessage(
                code = enumValueOrDefault(state.providerCode, GitSyncErrorCode.UNKNOWN),
                detail = state.detail,
            )
        SyncBackendType.WEBDAV ->
            SettingsErrorPresenter.webDavSyncErrorMessage(
                code = enumValueOrDefault(state.providerCode, WebDavSyncErrorCode.UNKNOWN),
                detail = state.detail,
            )
        SyncBackendType.S3 ->
            SettingsErrorPresenter.s3SyncErrorMessage(
                code = enumValueOrDefault(state.providerCode, S3SyncErrorCode.UNKNOWN),
                detail = state.detail,
            )
        SyncBackendType.INBOX,
        SyncBackendType.NONE,
        -> state.detail ?: stringResource(R.string.error_unknown)
    }

private fun SyncBackendType.connectionTestingResId(): Int =
    when (this) {
        SyncBackendType.GIT -> R.string.settings_git_test_connection_testing
        SyncBackendType.WEBDAV -> R.string.settings_webdav_test_connection_testing
        SyncBackendType.S3 -> R.string.settings_s3_test_connection_testing
        SyncBackendType.INBOX,
        SyncBackendType.NONE,
        -> R.string.settings_s3_test_connection_testing
    }

private fun SyncBackendType.connectionSuccessResId(): Int =
    when (this) {
        SyncBackendType.GIT -> R.string.settings_git_test_connection_success
        SyncBackendType.WEBDAV -> R.string.settings_webdav_test_connection_success
        SyncBackendType.S3 -> R.string.settings_s3_test_connection_success
        SyncBackendType.INBOX,
        SyncBackendType.NONE,
        -> R.string.settings_s3_test_connection_success
    }

private fun SyncBackendType.connectionFailedResId(): Int =
    when (this) {
        SyncBackendType.GIT -> R.string.settings_git_test_connection_failed
        SyncBackendType.WEBDAV -> R.string.settings_webdav_test_connection_failed
        SyncBackendType.S3 -> R.string.settings_s3_test_connection_failed
        SyncBackendType.INBOX,
        SyncBackendType.NONE,
        -> R.string.settings_s3_test_connection_failed
    }
