package com.lomo.domain.usecase

import com.lomo.domain.repository.MigrationArchiveRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import java.io.InputStream
import java.io.OutputStream

data class MigrationArchiveSummary(
    val noteCount: Int = 0,
    val trashCount: Int = 0,
    val imageCount: Int = 0,
    val voiceCount: Int = 0,
)

data class MigrationArchiveImportPlan(
    val summary: MigrationArchiveSummary,
    val manifestVersion: Int,
)

data class MigrationSettingsSummary(
    val settingCount: Int = 0,
    val sensitiveSettingCount: Int = 0,
)

class MigrationPasswordException(
    message: String = "Unable to decrypt migration settings with the supplied password",
    cause: Throwable? = null,
) : Exception(message, cause)

class ExportAllNotesArchiveUseCase(
    private val repository: MigrationArchiveRepository,
) {
    suspend operator fun invoke(output: OutputStream): MigrationArchiveSummary =
        repository.exportAllNotesArchive(output)
}

class InspectAllNotesArchiveUseCase(
    private val repository: MigrationArchiveRepository,
) {
    suspend operator fun invoke(input: InputStream): MigrationArchiveImportPlan =
        repository.inspectAllNotesArchive(input)
}

class ImportAllNotesArchiveUseCase(
    private val repository: MigrationArchiveRepository,
    private val workspaceStateResolver: WorkspaceStateResolver,
) {
    suspend operator fun invoke(input: InputStream): MigrationArchiveSummary {
        val summary = repository.importAllNotesArchive(input)
        workspaceStateResolver.rebuildFromCurrentWorkspace()
        return summary
    }
}

class ExportEncryptedSettingsUseCase(
    private val repository: MigrationArchiveRepository,
) {
    suspend operator fun invoke(
        output: OutputStream,
        password: String,
    ): MigrationSettingsSummary = repository.exportEncryptedSettings(output, password)
}

class ImportEncryptedSettingsUseCase(
    private val repository: MigrationArchiveRepository,
) {
    suspend operator fun invoke(
        input: InputStream,
        password: String,
    ): MigrationSettingsSummary = repository.importEncryptedSettings(input, password)
}
