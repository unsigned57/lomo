package com.lomo.data.repository

import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.domain.repository.MigrationArchiveRepository
import com.lomo.domain.usecase.MigrationArchiveImportPlan
import com.lomo.domain.usecase.MigrationArchiveSummary
import com.lomo.domain.usecase.MigrationPasswordException
import com.lomo.domain.usecase.MigrationSettingsSummary
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MigrationArchiveRepositoryImpl
    @Inject
    constructor(
        private val markdownStorageDataSource: MarkdownStorageDataSource,
        private val workspaceMediaAccess: WorkspaceMediaAccess,
        private val settingsStore: MigrationSettingsStore,
        private val importBudgets: MigrationArchiveImportBudgets,
    ) : MigrationArchiveRepository {
        private val dryRunPlanner = MigrationArchiveDryRunPlanner(importBudgets)

        override suspend fun exportAllNotesArchive(output: OutputStream): MigrationArchiveSummary {
            val mainNotes = markdownEntries(MemoDirectoryType.MAIN)
            val trashNotes = markdownEntries(MemoDirectoryType.TRASH)
            val images =
                workspaceMediaAccess
                    .listFilenames(WorkspaceMediaCategory.IMAGE)
                    .map(::requireSafeArchiveFilename)
                    .sorted()
            val voices =
                workspaceMediaAccess
                    .listFilenames(WorkspaceMediaCategory.VOICE)
                    .map(::requireSafeArchiveFilename)
                    .sorted()
            val summary =
                MigrationArchiveSummary(
                    noteCount = mainNotes.size,
                    trashCount = trashNotes.size,
                    imageCount = images.size,
                    voiceCount = voices.size,
                )

            ZipOutputStream(output).use { zip ->
                zip.writeTextEntry(
                    name = MANIFEST_ENTRY,
                    text =
                        migrationJson.encodeToString(
                            MigrationArchiveManifest(
                                noteCount = summary.noteCount,
                                trashCount = summary.trashCount,
                                imageCount = summary.imageCount,
                                voiceCount = summary.voiceCount,
                            ),
                        ),
                )
                mainNotes.forEach { entry ->
                    zip.writeTextEntry("$MAIN_NOTES_PREFIX${entry.filename}", entry.content)
                }
                trashNotes.forEach { entry ->
                    zip.writeTextEntry("$TRASH_NOTES_PREFIX${entry.filename}", entry.content)
                }
                images.forEach { filename ->
                    zip.writeStreamEntry(
                        name = "$IMAGE_MEDIA_PREFIX$filename",
                        category = WorkspaceMediaCategory.IMAGE,
                        filename = filename,
                    )
                }
                voices.forEach { filename ->
                    zip.writeStreamEntry(
                        name = "$VOICE_MEDIA_PREFIX$filename",
                        category = WorkspaceMediaCategory.VOICE,
                        filename = filename,
                    )
                }
            }
            return summary
        }

        override suspend fun inspectAllNotesArchive(input: InputStream): MigrationArchiveImportPlan {
            val archiveFile = stageCompressedArchive(input)
            return try {
                archiveFile.inputStream().use { archiveInput ->
                    dryRunPlanner.dryRunAllNotesArchive(
                        input = archiveInput,
                        compressedArchiveBytes = archiveFile.length(),
                    )
                }
            } finally {
                archiveFile.delete()
            }
        }

        override suspend fun importAllNotesArchive(input: InputStream): MigrationArchiveSummary {
            val archiveFile = stageCompressedArchive(input)
            return try {
                val plan =
                    archiveFile.inputStream().use { archiveInput ->
                        dryRunPlanner.dryRunAllNotesArchive(
                            input = archiveInput,
                            compressedArchiveBytes = archiveFile.length(),
                        )
                    }
                archiveFile.inputStream().use { commitAllNotesArchive(input = it, summary = plan.summary) }
            } finally {
                archiveFile.delete()
            }
        }

        private fun stageCompressedArchive(input: InputStream): File {
            val archiveFile = File.createTempFile("lomo-migration-import-", ".zip")
            var compressedBytes = 0L
            return try {
                archiveFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        compressedBytes += read.toLong()
                        require(compressedBytes <= importBudgets.maxCompressedArchiveBytes) {
                            "Migration archive compressed byte budget exceeded: " +
                                "$compressedBytes > ${importBudgets.maxCompressedArchiveBytes}"
                        }
                        output.write(buffer, 0, read)
                    }
                }
                archiveFile
            } catch (exception: Exception) {
                archiveFile.delete()
                throw exception
            }
        }

        private suspend fun commitAllNotesArchive(
            input: InputStream,
            summary: MigrationArchiveSummary,
        ): MigrationArchiveSummary {
            val rollbackActions = mutableListOf<MigrationArchiveRollbackAction>()
            try {
                ZipInputStream(input).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { entry ->
                        if (!entry.isDirectory) {
                            commitArchiveEntry(
                                entry = entry,
                                zip = zip,
                                rollbackActions = rollbackActions,
                            )
                        }
                        zip.closeEntry()
                    }
                }
            } catch (exception: CancellationException) {
                rollbackCommittedArchiveEntries(rollbackActions, exception)
                throw exception
            } catch (exception: Exception) {
                rollbackCommittedArchiveEntries(rollbackActions, exception)
                throw exception
            } finally {
                cleanupRollbackSnapshots(rollbackActions)
            }
            return summary
        }

        private suspend fun commitArchiveEntry(
            entry: ZipEntry,
            zip: ZipInputStream,
            rollbackActions: MutableList<MigrationArchiveRollbackAction>,
        ) {
            when {
                entry.name == MANIFEST_ENTRY -> Unit
                entry.name.startsWith(MAIN_NOTES_PREFIX) ->
                    commitMarkdownArchiveEntry(
                        directory = MemoDirectoryType.MAIN,
                        prefix = MAIN_NOTES_PREFIX,
                        entry = entry,
                        zip = zip,
                        rollbackActions = rollbackActions,
                    )
                entry.name.startsWith(TRASH_NOTES_PREFIX) ->
                    commitMarkdownArchiveEntry(
                        directory = MemoDirectoryType.TRASH,
                        prefix = TRASH_NOTES_PREFIX,
                        entry = entry,
                        zip = zip,
                        rollbackActions = rollbackActions,
                    )
                entry.name.startsWith(IMAGE_MEDIA_PREFIX) ->
                    commitMediaArchiveEntry(
                        category = WorkspaceMediaCategory.IMAGE,
                        prefix = IMAGE_MEDIA_PREFIX,
                        entry = entry,
                        zip = zip,
                        rollbackActions = rollbackActions,
                    )
                entry.name.startsWith(VOICE_MEDIA_PREFIX) ->
                    commitMediaArchiveEntry(
                        category = WorkspaceMediaCategory.VOICE,
                        prefix = VOICE_MEDIA_PREFIX,
                        entry = entry,
                        zip = zip,
                        rollbackActions = rollbackActions,
                    )
                else -> throw IllegalArgumentException("Unsupported migration archive entry: ${entry.name}")
            }
        }

        private suspend fun commitMarkdownArchiveEntry(
            directory: MemoDirectoryType,
            prefix: String,
            entry: ZipEntry,
            zip: ZipInputStream,
            rollbackActions: MutableList<MigrationArchiveRollbackAction>,
        ) {
            commitMarkdownEntry(
                directory = directory,
                filename = entry.requireArchiveFilename(prefix),
                content = zip.readBytes().toString(Charsets.UTF_8),
                rollbackActions = rollbackActions,
            )
        }

        private suspend fun commitMediaArchiveEntry(
            category: WorkspaceMediaCategory,
            prefix: String,
            entry: ZipEntry,
            zip: ZipInputStream,
            rollbackActions: MutableList<MigrationArchiveRollbackAction>,
        ) {
            commitMediaEntry(
                category = category,
                filename =
                    entry.requireArchiveMediaFilename(
                        prefix = prefix,
                        category = category,
                    ),
                rollbackActions = rollbackActions,
            ) { output -> zip.copyTo(output) }
        }

        private suspend fun commitMarkdownEntry(
            directory: MemoDirectoryType,
            filename: String,
            content: String,
            rollbackActions: MutableList<MigrationArchiveRollbackAction>,
        ) {
            rollbackActions +=
                MigrationArchiveRollbackAction.Markdown(
                    directory = directory,
                    filename = filename,
                    previousContent = markdownStorageDataSource.readFileIn(directory, filename),
                )
            markdownStorageDataSource.saveFileIn(
                directory = directory,
                filename = filename,
                content = content,
            )
        }

        private suspend fun commitMediaEntry(
            category: WorkspaceMediaCategory,
            filename: String,
            rollbackActions: MutableList<MigrationArchiveRollbackAction>,
            source: suspend (OutputStream) -> Unit,
        ) {
            val previousSnapshot =
                workspaceMediaAccess.readFileToSnapshotFile(
                    category = category,
                    filename = filename,
                )
            rollbackActions +=
                MigrationArchiveRollbackAction.Media(
                    category = category,
                    filename = filename,
                    previousSnapshot = previousSnapshot,
                )
            workspaceMediaAccess.writeFileFromStream(
                category = category,
                filename = filename,
                source = source,
            )
        }

        private suspend fun rollbackCommittedArchiveEntries(
            rollbackActions: List<MigrationArchiveRollbackAction>,
            originalFailure: Throwable,
        ) {
            rollbackActions.asReversed().forEach { action ->
                runCatching {
                    when (action) {
                        is MigrationArchiveRollbackAction.Markdown -> {
                            if (action.previousContent == null) {
                                markdownStorageDataSource.deleteFileIn(action.directory, action.filename)
                            } else {
                                markdownStorageDataSource.saveFileIn(
                                    directory = action.directory,
                                    filename = action.filename,
                                    content = action.previousContent,
                                )
                            }
                        }
                        is MigrationArchiveRollbackAction.Media -> {
                            if (action.previousSnapshot == null) {
                                workspaceMediaAccess.deleteFile(action.category, action.filename)
                            } else {
                                workspaceMediaAccess.writeFileFromStream(
                                    category = action.category,
                                    filename = action.filename,
                                ) { output ->
                                    action.previousSnapshot.inputStream().use { input ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }
                    }
                }.onFailure(originalFailure::addSuppressed)
            }
        }

        private fun cleanupRollbackSnapshots(
            rollbackActions: List<MigrationArchiveRollbackAction>,
        ) {
            rollbackActions.forEach { action ->
                if (action is MigrationArchiveRollbackAction.Media) {
                    action.previousSnapshot?.delete()
                }
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
            val plainText = decryptSettings(
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

        private suspend fun markdownEntries(directory: MemoDirectoryType): List<MarkdownArchiveEntry> =
            markdownStorageDataSource
                .listMetadataIn(directory)
                .sortedBy { it.filename }
                .mapNotNull { metadata ->
                    markdownStorageDataSource.readFileIn(directory, metadata.filename)?.let { content ->
                        MarkdownArchiveEntry(
                            filename = requireSafeArchiveFilename(metadata.filename),
                            content = content,
                        )
                    }
                }

        private suspend fun ZipOutputStream.writeStreamEntry(
            name: String,
            category: WorkspaceMediaCategory,
            filename: String,
        ) {
            putNextEntry(ZipEntry(name))
            try {
                check(
                    workspaceMediaAccess.readFileToStream(
                        category = category,
                        filename = filename,
                        destination = this,
                    ),
                ) { "Workspace media file disappeared during archive export: $filename" }
            } finally {
                closeEntry()
            }
        }
    }

private data class MarkdownArchiveEntry(
    val filename: String,
    val content: String,
)

private sealed interface MigrationArchiveRollbackAction {
    data class Markdown(
        val directory: MemoDirectoryType,
        val filename: String,
        val previousContent: String?,
    ) : MigrationArchiveRollbackAction

    data class Media(
        val category: WorkspaceMediaCategory,
        val filename: String,
        val previousSnapshot: File?,
    ) : MigrationArchiveRollbackAction
}

private suspend fun WorkspaceMediaAccess.readFileToSnapshotFile(
    category: WorkspaceMediaCategory,
    filename: String,
): File? {
    val snapshot = File.createTempFile("lomo-migration-media-snapshot-", ".bin")
    val found =
        try {
            snapshot.outputStream().use { destination ->
                readFileToStream(
                    category = category,
                    filename = filename,
                    destination = destination,
                )
            }
        } catch (exception: Exception) {
            snapshot.delete()
            throw exception
        }
    if (!found) {
        snapshot.delete()
        return null
    }
    return snapshot
}
