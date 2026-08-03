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
) : IllegalArgumentException(message)

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

internal data class SafMemoProjectionSnapshot(
    val memoId: String,
    val sourcePath: String,
    val fileFingerprint: String,
    val body: String,
    val tags: List<String>,
    val attachmentPaths: List<String>,
    val hasTodo: Boolean,
    val hasUrl: Boolean,
)

internal data class WorkspaceNativeCommandResultSnapshot(
    val path: String,
    val resultFingerprint: String,
    val bytesWritten: ULong,
)

internal interface WorkspaceMarkdownOwner {
    fun scanWorkspace(rootPath: String? = null): List<WorkspaceMemoSummarySnapshot>

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
    data class Append(
        val timePart: String,
        val content: String,
    ) : WorkspaceNativeCommandSpec

    data class Replace(
        val identity: String,
        val content: String,
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

/**
 * Sole data-owned adapter for dark-build workspace FFI capabilities.
 *
 * Implementations hold the generated engine handle only through [NativeEnginePort] + lease rules.
 */
internal interface WorkspaceNativeAdapter :
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
        expectedFingerprint: String,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong = DEFAULT_JOB_DEADLINE_MILLIS,
    ): String

    fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot

    companion object {
        // Full-workspace import/refresh can list+read many SAF documents in one scan job.
        const val DEFAULT_JOB_DEADLINE_MILLIS: ULong = 120_000uL
    }
}

/** One native handle carrying engine lifecycle and workspace/store/media/archive capabilities. */
internal interface WorkspaceNativeEnginePort :
    NativeEnginePort,
    com.lomo.data.engine.lan.LanNativeBridge,
    com.lomo.data.engine.store.StoreNativeBridge,
    com.lomo.data.engine.media.MediaNativeBridge,
    com.lomo.data.engine.archive.ArchiveNativeBridge {
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

    fun rebuildSafStoreProjection(
        memos: List<SafMemoProjectionSnapshot>,
    ): com.lomo.nativebridge.StoreRebuildResult

    fun startWorkspaceDocumentCommand(
        path: String,
        expectedFingerprint: String,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String

    fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot
}
