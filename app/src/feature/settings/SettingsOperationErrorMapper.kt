package com.lomo.app.feature.settings

import com.lomo.app.feature.common.toUserMessage

class SettingsOperationErrorMapper {
    fun map(
        throwable: Throwable,
        fallbackMessage: String,
    ): SettingsOperationError = SettingsOperationError.Message(throwable.toUserMessage(fallbackMessage))
}
