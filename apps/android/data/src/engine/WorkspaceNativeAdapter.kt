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

internal data class WorkspaceReminderReferenceSnapshot(
    val opaqueId: String,
    val revision: String,
    val memoIdentity: String,
    val sourceStart: ULong,
    val sourceEnd: ULong,
    val tokenFingerprint: String,
    val fingerprintOrdinal: UInt,
    /** Resolved durable embedded reminder id; null for legacy or ambiguous-duplicate tokens. */
    val embeddedId: String?,
    val token: String,
    val dueAtLocal: String,
    val repeatCount: UInt,
    val firedCount: UInt,
    val done: Boolean,
    val intervalMinutes: UInt,
    val recurrenceCode: String,
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

internal data class WorkspaceNativeCommandResultSnapshot(
    val path: String,
    val resultFingerprint: String,
    val bytesWritten: ULong,
    val affectedMemo: WorkspaceDocumentMemoFactsSnapshot?,
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
    /** Post-command memo body returned by Rust; absent only for record-only trash facts. */
    val content: String? = null,
)

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
        /** Stable memo identity resolved by Rust against the verified document parse. */
        val identity: String,
        /** Task marker span relative to the memo body, never an absolute file span. */
        val bodyStart: ULong,
        val bodyEnd: ULong,
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
    com.lomo.data.engine.lan.LanNativeBridge,
    com.lomo.data.engine.store.StoreNativeBridge,
    SessionNativeBridge,
    com.lomo.data.engine.media.MediaNativeBridge,
    com.lomo.data.engine.archive.ArchiveNativeBridge {
    fun renderMarkdown(
        content: String,
        schemaVersion: UInt = MarkdownRenderDocument.SCHEMA_VERSION,
    ): MarkdownRenderDocument

    fun driveJob(jobId: String): NativeJobStep

    fun startWorkspaceDocumentCommand(
        path: String,
        expectedState: WorkspaceNativeExpectedState,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong = DEFAULT_JOB_DEADLINE_MILLIS,
    ): String

    fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot

    override fun commitWorkspaceDocumentFacts(
        command: com.lomo.nativebridge.StoreMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection,
    ): com.lomo.nativebridge.StoreMemoCommit

    companion object {
        // Full-workspace import/refresh can list+read many SAF documents in one scan job.
        const val DEFAULT_JOB_DEADLINE_MILLIS: ULong = 120_000uL
    }
}

/** Read/rebuild capability of the Rust-owned workspace projection boundary. */
internal interface WorkspaceProjectionEnginePort {
    fun renderMarkdown(
        content: String,
        schemaVersion: UInt,
    ): MarkdownRenderDocument
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
}

/** One native handle carrying engine lifecycle and workspace/store/media/archive capabilities. */
internal interface WorkspaceNativeEnginePort :
    NativeEnginePort,
    WorkspaceProjectionEnginePort,
    WorkspaceCommandEnginePort,
    com.lomo.data.engine.lan.LanNativeBridge,
    com.lomo.data.engine.store.StoreNativeBridge,
    SessionNativeBridge,
    com.lomo.data.engine.media.MediaNativeBridge,
    com.lomo.data.engine.archive.ArchiveNativeBridge
