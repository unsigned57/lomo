package com.lomo.app.feature.settings

import com.lomo.app.feature.common.recordApplicationFailure
import com.lomo.app.feature.common.toUserMessage
import com.lomo.domain.model.EngineDiagnosticsRecorder
import java.util.Locale

class SettingsOperationErrorMapper(private val diagnostics: EngineDiagnosticsRecorder) {
    fun map(throwable: Throwable, fallbackMessage: String): SettingsOperationError {
        diagnostics.recordApplicationFailure("settings.update", throwable)
        return SettingsOperationError.Message(throwable.toUserMessage(fallbackMessage))
    }

    fun workspace(throwable: Throwable): SettingsOperationError {
        val failure = diagnostics.recordApplicationFailure("settings.storage", throwable)
        return SettingsOperationError.Message(
            text = failure.code,
            workspaceFailureCode = failure.code,
        )
    }

    fun migration(operation: SettingsMigrationOperationKind, throwable: Throwable): String {
        val failure =
            diagnostics.recordApplicationFailure(
                "migration.${operation.name.lowercase(Locale.ROOT)}",
                throwable,
            )
        return failure.code
    }
}
