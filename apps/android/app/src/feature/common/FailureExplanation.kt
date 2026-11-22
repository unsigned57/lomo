package com.lomo.app.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R

@Composable
internal fun failureExplanation(code: String): String? {
    val message = when (code) {
        "saf_grant_not_persisted", "saf_grant_revoked", "workspace_access_denied", "access_denied" ->
            R.string.failure_workspace_grant

        "workspace_root_unavailable",
        "workspace_root_not_directory",
        "workspace_content_unavailable",
        "saf_root_unavailable",
        -> R.string.failure_workspace_unavailable

        "workspace_root_unreadable", "saf_root_unreadable", "metadata_enumeration_incomplete" ->
            R.string.failure_workspace_unreadable

        "workspace_root_unwritable", "saf_root_unwritable" -> R.string.failure_workspace_unwritable

        "workspace_probe_incomplete" -> R.string.failure_workspace_probe

        "metadata_enumeration_loading" -> R.string.failure_workspace_loading

        "metadata_directory_name_conflict" -> R.string.failure_workspace_name_conflict

        "media_root_unavailable" -> R.string.failure_media_root_unavailable

        "workspace_time_zone_unknown" -> R.string.failure_workspace_time_zone

        "storage_date_format_invalid" -> R.string.failure_workspace_date_format

        "memo_date_invalid", "memo_time_invalid", "calendar_epoch_out_of_range" -> R.string.failure_workspace_memo_date

        "file_unavailable" -> R.string.failure_file_unavailable

        "source_not_utf8" -> R.string.failure_document_utf8

        "ir_string_too_large", "render_document_too_large", "inline_render_too_large" ->
            R.string.failure_document_render_limit

        "migration_password_invalid" -> R.string.failure_migration_password

        "migration_envelope_invalid" -> R.string.failure_migration_envelope

        else -> return null
    }
    return stringResource(message)
}
