package com.lomo.data.engine.archive

import com.lomo.data.engine.CapabilityRegistry
import com.lomo.data.engine.RootCapabilityGrant
import java.io.File

/**
 * Production [ArchivePort] over [ArchiveNativeBridge].
 */
internal class BoltFfiArchivePort(
    private val bridge: ArchiveNativeBridge,
    private val capabilityRegistry: CapabilityRegistry,
) : ArchivePort {
    override fun sessionExportArchive(fileNameHint: String?, mediaRoots: List<String>): ArchiveExportResult {
        val grants = mutableListOf<RootCapabilityGrant>()
        val result = try {
            for (root in mediaRoots.distinct()) {
                grants += if (root.startsWith("content:")) {
                    capabilityRegistry.register(treeUri = root)
                } else {
                    capabilityRegistry.registerDirect(rootPath = File(root))
                }
            }
            bridge.sessionExportArchive(fileNameHint, grants.map { it.capabilityToken })
        } finally {
            grants.forEach { capabilityRegistry.revoke(it.capabilityToken) }
        }
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
