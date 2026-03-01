package com.lomo.app.feature.settings

import com.lomo.app.feature.common.toUserMessage
import com.lomo.domain.usecase.GitSyncErrorUseCase

class SettingsOperationErrorMapper(
    private val gitSyncErrorUseCase: GitSyncErrorUseCase,
) {
    fun map(
        throwable: Throwable,
        fallbackMessage: String,
    ): String = throwable.toUserMessage(fallbackMessage, gitSyncErrorUseCase::sanitizeUserFacingMessage)
}
