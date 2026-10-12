package com.lomo.data.engine.archive

import com.lomo.nativebridge.ArchiveExportResultDto as BridgeExport
import com.lomo.nativebridge.StoreRebuildResult as BridgeRebuild

/**
 * True FFI edge for archive v2 operations.
 */
internal interface ArchiveNativeBridge {
    /**
     * Session-owned export: the artifact path is derived inside Rust from the frozen session
     * exchange environment; [fileNameHint] may contribute only a bare file-name stem.
     */
    fun sessionExportArchive(
        workspaceRoot: String,
        fileNameHint: String?,
    ): BridgeExport

    fun sessionImportArchive(
        workspaceRoot: String,
        archivePath: String,
        stagingRoot: String,
    ): BridgeRebuild
}
