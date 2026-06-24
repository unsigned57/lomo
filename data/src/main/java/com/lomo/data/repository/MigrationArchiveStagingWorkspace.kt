package com.lomo.data.repository

import com.lomo.data.source.MemoDirectoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import javax.inject.Inject

sealed interface StagedMigrationArchiveEntry {
    val filename: String
    val file: File

    data class Markdown(
        val directory: MemoDirectoryType,
        override val filename: String,
        override val file: File,
    ) : StagedMigrationArchiveEntry

    data class Media(
        val category: WorkspaceMediaCategory,
        override val filename: String,
        override val file: File,
    ) : StagedMigrationArchiveEntry
}

interface MigrationArchiveStagingWorkspace {
    val entries: List<StagedMigrationArchiveEntry>

    suspend fun stageMarkdown(
        directory: MemoDirectoryType,
        filename: String,
        source: InputStream,
    )

    suspend fun stageMedia(
        category: WorkspaceMediaCategory,
        filename: String,
        source: InputStream,
    )

    suspend fun cleanup()
}

interface MigrationArchiveStagingWorkspaceFactory {
    fun create(): MigrationArchiveStagingWorkspace
}

class FileMigrationArchiveStagingWorkspaceFactory
    @Inject
    constructor() : MigrationArchiveStagingWorkspaceFactory {
        override fun create(): MigrationArchiveStagingWorkspace =
            FileMigrationArchiveStagingWorkspace(
                root = Files.createTempDirectory("lomo-migration-stage-").toFile(),
            )
    }

private class FileMigrationArchiveStagingWorkspace(
    private val root: File,
) : MigrationArchiveStagingWorkspace {
    private val stagedEntries = mutableListOf<StagedMigrationArchiveEntry>()

    override val entries: List<StagedMigrationArchiveEntry>
        get() = stagedEntries.toList()

    override suspend fun stageMarkdown(
        directory: MemoDirectoryType,
        filename: String,
        source: InputStream,
    ) {
        val safeFilename = requireSafeArchiveFilename(filename)
        val target = markdownTarget(directory, safeFilename)
        stageEntry(target, source)
        stagedEntries +=
            StagedMigrationArchiveEntry.Markdown(
                directory = directory,
                filename = safeFilename,
                file = target,
            )
    }

    override suspend fun stageMedia(
        category: WorkspaceMediaCategory,
        filename: String,
        source: InputStream,
    ) {
        val safeFilename = requireSafeArchiveMediaFilename(filename, category)
        val target = mediaTarget(category, safeFilename)
        stageEntry(target, source)
        stagedEntries +=
            StagedMigrationArchiveEntry.Media(
                category = category,
                filename = safeFilename,
                file = target,
            )
    }

    override suspend fun cleanup() {
        withContext(Dispatchers.IO) {
            root.deleteRecursively()
        }
    }

    private suspend fun stageEntry(
        target: File,
        source: InputStream,
    ) {
        withContext(Dispatchers.IO) {
            val parent = checkNotNull(target.parentFile) { "Migration archive staging target requires parent" }
            if (!parent.exists() && !parent.mkdirs()) {
                error("Failed to create migration archive staging directory: ${parent.absolutePath}")
            }
            try {
                target.outputStream().use { output ->
                    source.copyTo(output, bufferSize = MIGRATION_ARCHIVE_STAGING_BUFFER_BYTES)
                }
            } catch (exception: Exception) {
                target.delete()
                throw exception
            }
        }
    }

    private fun markdownTarget(
        directory: MemoDirectoryType,
        filename: String,
    ): File {
        val directoryName =
            when (directory) {
                MemoDirectoryType.MAIN -> "notes/main"
                MemoDirectoryType.TRASH -> "notes/trash"
            }
        return File(root, "$directoryName/$filename")
    }

    private fun mediaTarget(
        category: WorkspaceMediaCategory,
        filename: String,
    ): File {
        val directoryName =
            when (category) {
                WorkspaceMediaCategory.IMAGE -> "media/images"
                WorkspaceMediaCategory.VOICE -> "media/voice"
            }
        return File(root, "$directoryName/$filename")
    }
}

private const val MIGRATION_ARCHIVE_STAGING_BUFFER_BYTES = 64 * 1024
