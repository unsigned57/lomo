package com.lomo.data.engine.archive

/**
 * Production archive v2 surface (P4-10B) over BoltFFI path-only archive commands.
 * Settings/credentials encryption stays on a separate Kotlin owner.
 */
data class ArchiveExportResult(
    val archivePath: String,
    val schemaVersion: Int,
    val entryCount: Long,
)

data class ArchiveImportRebuildResult(
    val memosIndexed: Long,
    val fileCount: Long,
    val attachmentCount: Long,
    val workspaceDigest: String,
    val storeDigest: String,
    val corruptLomoIsolated: Long,
    val highWaterRevision: Long,
)

interface ArchivePort {
    /**
     * Session-owned export under the Rust transaction lock. The returned
     * [ArchiveExportResult.archivePath] points into the session-env export root; callers may
     * only influence the artifact name through a bare file-name stem.
     */
    fun sessionExportArchive(
        workspaceRoot: String,
        fileNameHint: String?,
    ): ArchiveExportResult

    fun archiveImportRebuild(
        workspaceRoot: String,
        archivePath: String,
        stagingRoot: String,
    ): ArchiveImportRebuildResult
}
