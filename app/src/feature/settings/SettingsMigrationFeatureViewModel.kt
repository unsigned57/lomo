package com.lomo.app.feature.settings

import com.lomo.domain.usecase.ExportAllNotesArchiveUseCase
import com.lomo.domain.usecase.ExportEncryptedSettingsUseCase
import com.lomo.domain.usecase.ImportAllNotesArchiveUseCase
import com.lomo.domain.usecase.ImportEncryptedSettingsUseCase
import com.lomo.domain.usecase.MigrationArchiveSummary
import com.lomo.domain.usecase.MigrationSettingsSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

enum class SettingsMigrationOperationKind {
    EXPORT_NOTES,
    IMPORT_NOTES,
    EXPORT_SETTINGS,
    IMPORT_SETTINGS,
}

sealed interface SettingsMigrationOperationState {
    data object Idle : SettingsMigrationOperationState

    data class Running(
        val kind: SettingsMigrationOperationKind,
    ) : SettingsMigrationOperationState

    data class Success(
        val kind: SettingsMigrationOperationKind,
        val summary: MigrationArchiveSummary = MigrationArchiveSummary(),
        val settingsSummary: MigrationSettingsSummary = MigrationSettingsSummary(),
    ) : SettingsMigrationOperationState

    data class Error(
        val kind: SettingsMigrationOperationKind,
        val message: String,
    ) : SettingsMigrationOperationState
}

class SettingsMigrationFeatureViewModel(
    private val scope: CoroutineScope,
    private val exportAllNotesArchiveUseCase: ExportAllNotesArchiveUseCase,
    private val importAllNotesArchiveUseCase: ImportAllNotesArchiveUseCase,
    private val exportEncryptedSettingsUseCase: ExportEncryptedSettingsUseCase,
    private val importEncryptedSettingsUseCase: ImportEncryptedSettingsUseCase,
) {
    private val _operationState =
        MutableStateFlow<SettingsMigrationOperationState>(SettingsMigrationOperationState.Idle)
    val operationState: StateFlow<SettingsMigrationOperationState> = _operationState.asStateFlow()

    fun exportNotesArchive(openOutput: () -> OutputStream?) {
        runMigration(SettingsMigrationOperationKind.EXPORT_NOTES) {
            val summary =
                requireNotNull(openOutput()) { "Unable to open export file" }.use { output ->
                    exportAllNotesArchiveUseCase(output)
                }
            SettingsMigrationOperationState.Success(
                kind = SettingsMigrationOperationKind.EXPORT_NOTES,
                summary = summary,
            )
        }
    }

    fun importNotesArchive(openInput: () -> InputStream?) {
        runMigration(SettingsMigrationOperationKind.IMPORT_NOTES) {
            val summary =
                requireNotNull(openInput()) { "Unable to open import file" }.use { input ->
                    importAllNotesArchiveUseCase(input)
                }
            SettingsMigrationOperationState.Success(
                kind = SettingsMigrationOperationKind.IMPORT_NOTES,
                summary = summary,
            )
        }
    }

    fun exportEncryptedSettings(
        password: String,
        openOutput: () -> OutputStream?,
    ) {
        runMigration(SettingsMigrationOperationKind.EXPORT_SETTINGS) {
            val summary =
                requireNotNull(openOutput()) { "Unable to open settings export file" }.use { output ->
                    exportEncryptedSettingsUseCase(output, password)
                }
            SettingsMigrationOperationState.Success(
                kind = SettingsMigrationOperationKind.EXPORT_SETTINGS,
                settingsSummary = summary,
            )
        }
    }

    fun importEncryptedSettings(
        password: String,
        openInput: () -> InputStream?,
    ) {
        runMigration(SettingsMigrationOperationKind.IMPORT_SETTINGS) {
            val summary =
                requireNotNull(openInput()) { "Unable to open settings import file" }.use { input ->
                    importEncryptedSettingsUseCase(input, password)
                }
            SettingsMigrationOperationState.Success(
                kind = SettingsMigrationOperationKind.IMPORT_SETTINGS,
                settingsSummary = summary,
            )
        }
    }

    fun clearOperationState() {
        _operationState.value = SettingsMigrationOperationState.Idle
    }

    private fun runMigration(
        kind: SettingsMigrationOperationKind,
        block: suspend () -> SettingsMigrationOperationState.Success,
    ) {
        _operationState.value = SettingsMigrationOperationState.Running(kind)
        scope.launch {
            _operationState.value =
                runCatching { block() }
                    .getOrElse { throwable ->
                        if (throwable is CancellationException) {
                            throw throwable
                        }
                        SettingsMigrationOperationState.Error(
                            kind = kind,
                            message = throwable.message?.takeIf(String::isNotBlank)
                                ?: "Migration operation failed",
                        )
                    }
        }
    }
}
