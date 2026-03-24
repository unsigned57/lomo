package com.lomo.app.feature.settings

import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.WebDavSyncErrorCode

sealed interface SettingsOperationError {
    data class Message(
        val text: String,
    ) : SettingsOperationError

    data class GitSync(
        val code: GitSyncErrorCode,
        val detail: String? = null,
    ) : SettingsOperationError

    data class WebDavSync(
        val code: WebDavSyncErrorCode,
        val detail: String? = null,
    ) : SettingsOperationError
}
