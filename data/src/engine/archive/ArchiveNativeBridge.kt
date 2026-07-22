package com.lomo.data.engine.archive

import com.lomo.nativebridge.ArchiveExportResultDto as BridgeExport
import com.lomo.nativebridge.ArchiveInspectResultDto as BridgeInspect
import com.lomo.nativebridge.StoreRebuildResult as BridgeRebuild

/**
 * True FFI edge for archive v2 operations.
 */
internal interface ArchiveNativeBridge {
    fun archiveExport(
        workspaceRoot: String,
        archivePath: String,
    ): BridgeExport

    fun archiveInspect(
        archivePath: String,
        stagingRoot: String,
    ): BridgeInspect

    fun archiveImport(
        archivePath: String,
        stagingRoot: String,
    ): BridgeInspect

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
        rebuildBatchSize: UInt,
    ): BridgeRebuild
}
