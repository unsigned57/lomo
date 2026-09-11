package com.lomo.data.engine.archive

/**
 * Production [ArchivePort] over [ArchiveNativeBridge].
 */
internal class BoltFfiArchivePort(
    private val bridge: ArchiveNativeBridge,
) : ArchivePort {
    override fun archiveExport(
        workspaceRoot: String,
        archivePath: String,
    ): ArchiveExportResult {
        val result = bridge.archiveExport(workspaceRoot, archivePath)
        return ArchiveExportResult(
            archivePath = result.archivePath,
            schemaVersion = result.schemaVersion.toInt(),
            entryCount = result.entryCount.toLong(),
        )
    }

    override fun archiveInspect(
        archivePath: String,
        stagingRoot: String,
    ): ArchiveInspectResult {
        val result = bridge.archiveInspect(archivePath, stagingRoot)
        return ArchiveInspectResult(
            stagingRoot = result.stagingRoot,
            schemaVersion = result.schemaVersion.toInt(),
            entryCount = result.entryCount.toLong(),
        )
    }

    override fun archiveImport(
        archivePath: String,
        stagingRoot: String,
    ): ArchiveInspectResult {
        val result = bridge.archiveImport(archivePath, stagingRoot)
        return ArchiveInspectResult(
            stagingRoot = result.stagingRoot,
            schemaVersion = result.schemaVersion.toInt(),
            entryCount = result.entryCount.toLong(),
        )
    }

    override fun archiveActivate(
        stagingRoot: String,
        liveRoot: String,
        backupRoot: String,
    ) {
        bridge.archiveActivate(stagingRoot, liveRoot, backupRoot)
    }

    override fun archiveImportActivateRebuild(
        archivePath: String,
        stagingRoot: String,
        liveRoot: String,
        backupRoot: String,
        rebuildBatchSize: Int,
    ): ArchiveImportActivateRebuildResult {
        val rebuild =
            bridge.archiveImportActivateRebuild(
                archivePath,
                stagingRoot,
                liveRoot,
                backupRoot,
                rebuildBatchSize.toUInt(),
            )
        return ArchiveImportActivateRebuildResult(
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
