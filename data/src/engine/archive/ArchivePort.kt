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

data class ArchiveInspectResult(
    val stagingRoot: String,
    val schemaVersion: Int,
    val entryCount: Long,
)

data class ArchiveImportActivateRebuildResult(
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

    fun archiveInspect(
        archivePath: String,
        stagingRoot: String,
    ): ArchiveInspectResult

    fun archiveImport(
        archivePath: String,
        stagingRoot: String,
    ): ArchiveInspectResult

    fun archiveActivate(
        stagingRoot: String,
        liveRoot: String,
        backupRoot: String,
    )

    fun archiveImportActivateRebuild(
        archivePath: String,
        stagingRoot: String,
        liveRoot: String,
        backupRoot: String,
        rebuildBatchSize: Int,
    ): ArchiveImportActivateRebuildResult
}
