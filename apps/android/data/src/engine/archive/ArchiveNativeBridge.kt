package com.lomo.data.engine.archive

import com.lomo.nativebridge.ArchiveExportResultDto as BridgeExport
import com.lomo.nativebridge.StoreRebuildResult as BridgeRebuild

/**
 * True FFI edge for archive v2 operations.
 */
internal interface ArchiveNativeBridge {
    fun archiveExport(
        workspaceRoot: String,
        archivePath: String,
    ): BridgeExport

    fun sessionImportArchive(
        workspaceRoot: String,
        archivePath: String,
        stagingRoot: String,
    ): BridgeRebuild
}
