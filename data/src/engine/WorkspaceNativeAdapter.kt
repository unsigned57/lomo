package com.lomo.data.engine

import com.lomo.domain.model.markdown.MarkdownRenderDocument

/**
 * Workspace capability adapter owned by data.
 *
 * This adapter reuses the stage-1 engine lease / platform-batch runner and never re-parses Markdown
 * in Kotlin: it only drives jobs and validates/maps generated bridge DTOs into domain contracts.
 *
 * Domain / app / ui-components must not import [com.lomo.nativebridge] types from this surface.
 */
internal class WorkspaceRenderBoundaryException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal data class WorkspaceMemoSummarySnapshot(
    val path: String,
    val identity: String,
    val timePart: String,
    val fingerprint: String,
    val tags: List<String>,
    val attachments: List<String>,
    val reminders: List<WorkspaceReminderReferenceSnapshot>,
    /** Task-list presence from the same workspace parse as [tags]/[attachments]/render IR. */
    val hasTodo: Boolean = false,
    /** External URL presence from the same workspace parse as [tags]/[attachments]/render IR. */
    val hasUrl: Boolean = false,
    val content: String,
    val bodyStart: ULong,
    val bodyEnd: ULong,
    val startLine: UInt,
    val endLine: UInt,
)

internal data class WorkspaceReminderReferenceSnapshot(
    val opaqueId: String,
    val revision: String,
    val memoIdentity: String,
    val sourceStart: ULong,
    val sourceEnd: ULong,
    val tokenFingerprint: String,
    val token: String,
    val dueAtLocal: String,
    val repeatCount: UInt,
    val firedCount: UInt,
    val done: Boolean,
    val intervalMinutes: UInt,
    val recurrenceCode: String,
)

internal data class WorkspaceScanPageSnapshot(
    val items: List<WorkspaceMemoSummarySnapshot>,
    val nextCursor: String?,
)

internal data class SafMemoProjectionReferenceSnapshot(
    val memoId: String,
    val sourcePath: String,
    val fileFingerprint: String,
    val chronologyEpochMs: Long,
    val content: ExchangeArtifactReference,
    val tags: List<String>,
    val attachmentPaths: List<String>,
    val hasTodo: Boolean,
    val hasUrl: Boolean,
    val reminders: List<WorkspaceReminderReferenceSnapshot>,
)

internal data class SafMemoProjectionSnapshot(
    val memoId: String,
    val sourcePath: String,
    val fileFingerprint: String,
    val chronologyEpochMs: Long,
    val body: String,
    val tags: List<String>,
    val attachmentPaths: List<String>,
    val hasTodo: Boolean,
    val hasUrl: Boolean,
    val reminders: List<WorkspaceReminderReferenceSnapshot>,
    val trashedAtMs: Long? = null,
)

internal data class SafTrashProjectionReferenceSnapshot(
    val memoId: String,
    val sourcePath: String,
    val fileFingerprint: String,
    val chronologyEpochMs: Long,
    val trashedAtMs: Long,
    val content: ExchangeArtifactReference,
    val tags: List<String>,
    val attachmentPaths: List<String>,
    val hasTodo: Boolean,
    val hasUrl: Boolean,
    val reminders: List<WorkspaceReminderReferenceSnapshot>,
)

internal data class WorkspaceProjectionScanPageSnapshot(
    val items: List<SafMemoProjectionReferenceSnapshot>,
    val nextCursor: String?,
)

internal data class WorkspaceTrashProjectionScanPageSnapshot(
    val items: List<SafTrashProjectionReferenceSnapshot>,
    val nextCursor: String?,
)

internal data class SafHistoryProjectionReferenceSnapshot(
    val memoId: String,
    val revision: ULong,
    val createdAtMs: Long,
    val fileFingerprint: String,
    val content: ExchangeArtifactReference,
)

internal data class WorkspaceHistoryProjectionScanPageSnapshot(
    val items: List<SafHistoryProjectionReferenceSnapshot>,
    val nextCursor: String?,
)

internal data class WorkspaceNativeCommandResultSnapshot(
    val path: String,
    val resultFingerprint: String,
    val bytesWritten: ULong,
    val affectedMemo: WorkspaceDocumentMemoFactsSnapshot?,
)

internal data class WorkspaceNativeTrashCommandResultSnapshot(
    val path: String,
    /** Fingerprint of the document after the verified command completed. */
    val resultFingerprint: String,
    /** Verified memo pre-image; its fingerprint may differ after permanent delete rewrites the document. */
    val affectedMemo: WorkspaceDocumentMemoFactsSnapshot,
    val trashedAtMs: Long?,
)

internal data class WorkspaceDocumentMemoFactsSnapshot(
    val path: String,
    val identity: String,
    val timePart: String,
    val fingerprint: String,
    val tags: List<String>,
    val attachments: List<String>,
    val reminders: List<WorkspaceReminderReferenceSnapshot>,
    val hasTodo: Boolean,
    val hasUrl: Boolean,
)

internal interface WorkspaceMarkdownOwner {
    fun scanWorkspace(rootPath: String? = null): Sequence<WorkspaceMemoSummarySnapshot>

    fun replaceMemo(
        rootPath: String?,
        filename: String,
        identity: String,
        content: String,
    ): Boolean

    fun removeMemo(
        rootPath: String?,
        filename: String,
        identity: String,
    ): Boolean
}

internal sealed interface WorkspaceNativeCommandSpec {
    data class Create(
        val timePart: String,
        val content: String,
        val history: WorkspaceNativeHistoryWrite? = null,
    ) : WorkspaceNativeCommandSpec

    data class Append(
        val timePart: String,
        val content: String,
        val history: WorkspaceNativeHistoryWrite? = null,
    ) : WorkspaceNativeCommandSpec

    data class Replace(
        val identity: String,
        val content: String,
        val history: WorkspaceNativeHistoryWrite? = null,
    ) : WorkspaceNativeCommandSpec

    data class Remove(
        val identity: String,
    ) : WorkspaceNativeCommandSpec

    data class ToggleTask(
        val sourceStart: ULong,
        val sourceEnd: ULong,
    ) : WorkspaceNativeCommandSpec

    data class RewriteReminder(
        val reminder: WorkspaceReminderReferenceSnapshot,
        val replacement: String,
    ) : WorkspaceNativeCommandSpec
}

internal data class WorkspaceNativeHistoryWrite(
    val revision: ULong,
    val createdAtMs: Long,
)

internal sealed interface WorkspaceNativeTrashCommandSpec {
    data class Trash(
        val identity: String,
        val chronologyEpochMs: Long,
    ) : WorkspaceNativeTrashCommandSpec

    data class Restore(
        val identity: String,
    ) : WorkspaceNativeTrashCommandSpec

    data class PermanentDelete(
        val identity: String,
    ) : WorkspaceNativeTrashCommandSpec
}

internal sealed interface WorkspaceNativeExpectedState {
    data object Absent : WorkspaceNativeExpectedState

    data class Match(val fingerprint: String) : WorkspaceNativeExpectedState
}

/**
 * Sole data-owned adapter for dark-build workspace FFI capabilities.
 *
 * Implementations hold the generated engine handle only through [NativeEnginePort] + lease rules.
 */
internal interface WorkspaceNativeAdapter :
    WorkspaceDurableRecordScanPort,
    com.lomo.data.engine.lan.LanNativeBridge,
    com.lomo.data.engine.store.StoreNativeBridge,
    com.lomo.data.engine.media.MediaNativeBridge,
    com.lomo.data.engine.archive.ArchiveNativeBridge {
    fun renderMarkdown(
        content: String,
        schemaVersion: UInt = MarkdownRenderDocument.SCHEMA_VERSION,
    ): MarkdownRenderDocument

    fun startWorkspaceScan(
        pageSize: UInt,
        cursor: String? = null,
        rootPath: String? = null,
        deadlineMillis: ULong = DEFAULT_JOB_DEADLINE_MILLIS,
    ): String

    fun driveJob(jobId: String): NativeJobStep

    fun readWorkspaceScanPage(jobId: String): WorkspaceScanPageSnapshot

    fun startWorkspaceDocumentCommand(
        path: String,
        expectedState: WorkspaceNativeExpectedState,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong = DEFAULT_JOB_DEADLINE_MILLIS,
    ): String

    fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot

    fun startWorkspaceTrashCommand(
        path: String,
        expectedFingerprint: String,
        command: WorkspaceNativeTrashCommandSpec,
        deadlineMillis: ULong = DEFAULT_JOB_DEADLINE_MILLIS,
    ): String

    fun readWorkspaceTrashCommandResult(jobId: String): WorkspaceNativeTrashCommandResultSnapshot

    companion object {
        // Full-workspace import/refresh can list+read many SAF documents in one scan job.
        const val DEFAULT_JOB_DEADLINE_MILLIS: ULong = 120_000uL
    }
}

/** Bounded verified scans for durable record trees under the active workspace. */
internal interface WorkspaceDurableRecordScanPort {
    fun startWorkspaceTrashScan(
        pageSize: UInt,
        cursor: String? = null,
        deadlineMillis: ULong = WorkspaceNativeAdapter.DEFAULT_JOB_DEADLINE_MILLIS,
    ): String

    fun readWorkspaceTrashProjectionScanPage(jobId: String): WorkspaceTrashProjectionScanPageSnapshot

    fun startWorkspaceHistoryScan(
        pageSize: UInt,
        cursor: String? = null,
        deadlineMillis: ULong = WorkspaceNativeAdapter.DEFAULT_JOB_DEADLINE_MILLIS,
    ): String

    fun readWorkspaceHistoryProjectionScanPage(jobId: String): WorkspaceHistoryProjectionScanPageSnapshot
}

/** Streaming sink whose finish atomically publishes one complete SAF projection. */
internal interface SafProjectionRebuildSink {
    fun beginSafProjectionRebuild(): String

    fun appendSafProjectionRebuildPage(
        rebuildId: String,
        memos: List<SafMemoProjectionReferenceSnapshot>,
    )

    fun appendSafTrashProjectionRebuildPage(
        rebuildId: String,
        memos: List<SafTrashProjectionReferenceSnapshot>,
    )

    fun appendSafHistoryProjectionRebuildPage(
        rebuildId: String,
        revisions: List<SafHistoryProjectionReferenceSnapshot>,
    )

    fun finishSafProjectionRebuild(rebuildId: String): com.lomo.nativebridge.StoreRebuildResult

    fun abortSafProjectionRebuild(rebuildId: String)
}

/** Read/rebuild capability of the Rust-owned workspace projection boundary. */
internal interface WorkspaceProjectionEnginePort :
    WorkspaceDurableRecordScanPort,
    SafProjectionRebuildSink {
    fun renderMarkdown(
        content: String,
        schemaVersion: UInt,
    ): MarkdownRenderDocument

    fun startWorkspaceScan(
        pageSize: UInt,
        cursor: String?,
        rootPath: String?,
        deadlineMillis: ULong,
    ): String

    fun readWorkspaceScanPage(jobId: String): WorkspaceScanPageSnapshot

    fun readWorkspaceProjectionScanPage(jobId: String): WorkspaceProjectionScanPageSnapshot

}

/** Durable workspace mutation capability; every result is parsed and verified by Rust. */
internal interface WorkspaceCommandEnginePort {
    fun startWorkspaceDocumentCommand(
        path: String,
        expectedState: WorkspaceNativeExpectedState,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String

    fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot

    fun startWorkspaceTrashCommand(
        path: String,
        expectedFingerprint: String,
        command: WorkspaceNativeTrashCommandSpec,
        deadlineMillis: ULong,
    ): String

    fun readWorkspaceTrashCommandResult(jobId: String): WorkspaceNativeTrashCommandResultSnapshot
}

/** One native handle carrying engine lifecycle and workspace/store/media/archive capabilities. */
internal interface WorkspaceNativeEnginePort :
    NativeEnginePort,
    WorkspaceProjectionEnginePort,
    WorkspaceCommandEnginePort,
    com.lomo.data.engine.lan.LanNativeBridge,
    com.lomo.data.engine.store.StoreNativeBridge,
    com.lomo.data.engine.media.MediaNativeBridge,
    com.lomo.data.engine.archive.ArchiveNativeBridge
