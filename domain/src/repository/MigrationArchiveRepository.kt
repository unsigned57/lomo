package com.lomo.domain.repository

import com.lomo.domain.usecase.MigrationArchiveSummary
import com.lomo.domain.usecase.MigrationArchiveImportPlan
import com.lomo.domain.usecase.MigrationSettingsSummary
import java.io.InputStream
import java.io.OutputStream

interface MigrationArchiveRepository {
    suspend fun exportAllNotesArchive(output: OutputStream): MigrationArchiveSummary

    suspend fun inspectAllNotesArchive(input: InputStream): MigrationArchiveImportPlan =
        throw UnsupportedOperationException("Migration archive dry-run inspection is not implemented")

    suspend fun importAllNotesArchive(input: InputStream): MigrationArchiveSummary

    suspend fun exportEncryptedSettings(
        output: OutputStream,
        password: String,
    ): MigrationSettingsSummary

    suspend fun importEncryptedSettings(
        input: InputStream,
        password: String,
    ): MigrationSettingsSummary
}
