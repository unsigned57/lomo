package com.lomo.data.repository

import android.content.Context
import com.lomo.data.engine.archive.ArchivePort
import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.domain.repository.MigrationArchiveRepository
import com.lomo.domain.usecase.MigrationArchiveImportPlan
import com.lomo.domain.usecase.MigrationArchiveSummary
import com.lomo.domain.usecase.MigrationPasswordException
import com.lomo.domain.usecase.MigrationSettingsSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Production workspace archive edge after P4-10B: path-only [ArchivePort] (Rust archive v2).
 *
 * Encrypted settings/credentials remain Kotlin-owned via [MigrationSettingsStore].
 * Old Kotlin ZIP manifest/dry-run/staging tails are deleted (unsupported archive version at Rust).
 */
class WorkspaceArchiveEdgeRepository
constructor(
    private val context: Context,
    private val archivePort: ArchivePort,
    private val workspaceRoot: WorkspaceFilesystemRoot,
    private val settingsStore: MigrationSettingsStore,
) : MigrationArchiveRepository {
    override suspend fun exportAllNotesArchive(output: OutputStream): MigrationArchiveSummary =
        withContext(Dispatchers.IO) {
            val root = requireDirectWorkspaceRoot()
            val archiveFile =
                File(context.cacheDir, "lomo-archive-export-${UUID.randomUUID()}.zip")
            try {
                val exported = archivePort.archiveExport(root, archiveFile.absolutePath)
                archiveFile.inputStream().use { input -> input.copyTo(output) }
                // Counts are not re-derived in Kotlin; surface schema/entry projection only.
                MigrationArchiveSummary(
                    noteCount = exported.entryCount.toInt().coerceAtLeast(0),
                    trashCount = 0,
                    imageCount = 0,
                    voiceCount = 0,
                )
            } finally {
                archiveFile.delete()
            }
        }

    override suspend fun inspectAllNotesArchive(input: InputStream): MigrationArchiveImportPlan =
        withContext(Dispatchers.IO) {
            val archiveFile = stageCompressedArchive(input)
            val staging =
                File(context.cacheDir, "lomo-archive-inspect-${UUID.randomUUID()}").apply {
                    mkdirs()
                }
            try {
                val inspected =
                    archivePort.archiveInspect(
                        archivePath = archiveFile.absolutePath,
                        stagingRoot = staging.absolutePath,
                    )
                MigrationArchiveImportPlan(
                    summary =
                        MigrationArchiveSummary(
                            noteCount = inspected.entryCount.toInt().coerceAtLeast(0),
                        ),
                    manifestVersion = inspected.schemaVersion,
                )
            } finally {
                archiveFile.delete()
                staging.deleteRecursively()
            }
        }

    override suspend fun importAllNotesArchive(input: InputStream): MigrationArchiveSummary =
        withContext(Dispatchers.IO) {
            val root = requireDirectWorkspaceRoot()
            val archiveFile = stageCompressedArchive(input)
            val staging =
                File(context.cacheDir, "lomo-archive-import-${UUID.randomUUID()}").apply {
                    mkdirs()
                }
            val backup =
                File(context.cacheDir, "lomo-archive-backup-${UUID.randomUUID()}").apply {
                    mkdirs()
                }
            try {
                val rebuild =
                    archivePort.archiveImportActivateRebuild(
                        archivePath = archiveFile.absolutePath,
                        stagingRoot = staging.absolutePath,
                        liveRoot = root,
                        backupRoot = backup.absolutePath,
                        rebuildBatchSize = 256,
                    )
                MigrationArchiveSummary(
                    noteCount = rebuild.memosIndexed.toInt().coerceAtLeast(0),
                    trashCount = 0,
                    imageCount = rebuild.attachmentCount.toInt().coerceAtLeast(0),
                    voiceCount = 0,
                )
            } finally {
                archiveFile.delete()
                staging.deleteRecursively()
                backup.deleteRecursively()
            }
        }

    override suspend fun exportEncryptedSettings(
        output: OutputStream,
        password: String,
    ): MigrationSettingsSummary {
        require(password.isNotBlank()) { "Migration password must not be blank" }
        val snapshot = settingsStore.snapshot()
        val plainText = migrationJson.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        output.write(encryptSettings(plainText = plainText, password = password).toByteArray(Charsets.UTF_8))
        return snapshot.toSummary()
    }

    override suspend fun importEncryptedSettings(
        input: InputStream,
        password: String,
    ): MigrationSettingsSummary {
        require(password.isNotBlank()) { "Migration password must not be blank" }
        val plainText =
            decryptSettings(
                envelopeText = input.readBytes().toString(Charsets.UTF_8),
                password = password,
            )
        val snapshot =
            try {
                migrationJson.decodeFromString<MigrationSettingsSnapshot>(
                    plainText.toString(Charsets.UTF_8),
                )
            } catch (exception: SerializationException) {
                throw MigrationPasswordException("Migration settings file is not valid", exception)
            }
        (settingsStore as? MigrationSettingsRestoreValidator)?.validateRestore(snapshot)
        settingsStore.restore(snapshot)
        return snapshot.toSummary()
    }

    private fun requireDirectWorkspaceRoot(): String =
        workspaceRoot.absolutePathOrNull()
            ?: error("Archive path FFI requires an active Direct workspace root")

    private fun stageCompressedArchive(input: InputStream): File {
        val archiveFile = File.createTempFile("lomo-archive-input-", ".zip", context.cacheDir)
        archiveFile.outputStream().use { destination -> input.copyTo(destination) }
        return archiveFile
    }
}
