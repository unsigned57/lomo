package com.lomo.data.engine.archive

/**
 * Production [ArchivePort] over [ArchiveNativeBridge].
 */
internal class BoltFfiArchivePort(
    private val bridge: ArchiveNativeBridge,
) : ArchivePort {
    override fun sessionExportArchive(
        workspaceRoot: String,
        fileNameHint: String?,
    ): ArchiveExportResult {
        val result = bridge.sessionExportArchive(workspaceRoot, fileNameHint)
        return ArchiveExportResult(
            archivePath = result.archivePath,
            schemaVersion = result.schemaVersion.toInt(),
            entryCount = result.entryCount.toLong(),
        )
    }

    override fun archiveImportRebuild(
        workspaceRoot: String,
        archivePath: String,
        stagingRoot: String,
    ): ArchiveImportRebuildResult {
        val rebuild =
            bridge.sessionImportArchive(
                workspaceRoot,
                archivePath,
                stagingRoot,
            )
        return ArchiveImportRebuildResult(
            memosIndexed = rebuild.memosIndexed.toLong(),
            fileCount = rebuild.fileCount.toLong(),
            attachmentCount = rebuild.attachmentCount.toLong(),
            workspaceDigest = rebuild.workspaceDigest,
            storeDigest = rebuild.storeDigest,
            corruptLomoIsolated = rebuild.corruptLomoIsolated.toLong(),
            highWaterRevision = rebuild.highWaterRevision.toLong(),
        )
    }
}
