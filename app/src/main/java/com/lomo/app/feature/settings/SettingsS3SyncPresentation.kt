package com.lomo.app.feature.settings

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.lomo.app.R
import java.io.File

@Composable
internal fun s3CredentialSubtitle(configured: Boolean): String =
    stringResource(
        if (configured) {
            R.string.settings_s3_access_key_configured
        } else {
            R.string.settings_s3_access_key_not_set
        },
    )

@Composable
internal fun s3LocalSyncDirectorySubtitle(pathOrUri: String): String =
    pathOrUri
        .takeIf(String::isNotBlank)
        ?.let(::s3LocalSyncDirectoryDisplayName)
        ?: stringResource(R.string.settings_not_set)

@Composable
internal fun s3ConnectionTestSubtitle(state: SettingsS3ConnectionTestState): String =
    when (state) {
        is SettingsS3ConnectionTestState.Idle -> ""
        is SettingsS3ConnectionTestState.Testing ->
            stringResource(R.string.settings_s3_test_connection_testing)
        is SettingsS3ConnectionTestState.Success ->
            stringResource(R.string.settings_s3_test_connection_success)
        is SettingsS3ConnectionTestState.Error ->
            stringResource(R.string.settings_s3_test_connection_failed, state.detail)
    }

private fun s3LocalSyncDirectoryDisplayName(pathOrUri: String): String {
    val trimmed = pathOrUri.trim()
    if (trimmed.isBlank()) {
        return trimmed
    }
    if (trimmed.startsWith("content://")) {
        val decoded = Uri.decode(trimmed.toUri().lastPathSegment.orEmpty())
        val normalized = decoded.substringAfter(':', decoded).trimEnd('/')
        return normalized.substringAfterLast('/').ifBlank { trimmed }
    }
    return File(trimmed).name.takeIf(String::isNotBlank) ?: trimmed
}
