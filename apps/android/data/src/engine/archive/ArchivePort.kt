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
    fun archiveExport(
        workspaceRoot: String,
        archivePath: String,
    ): ArchiveExportResult

    fun archiveImportRebuild(
        workspaceRoot: String,
        archivePath: String,
        stagingRoot: String,
    ): ArchiveImportRebuildResult
}
