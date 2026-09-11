package com.lomo.domain.model

/**
 * Stage-5 dark Sync Center presentation models (P5-10).
 *
 * Host UI / ViewModel state only. Not production DI. Maps from dark remote-sync repository
 * facts (digests + artifact refs; no list body bytes). Binary conflicts never invent text previews.
 */

/** Path-level conflict status from durable session (named wire, not ordinal). */
enum class RemoteSyncConflictPathStatus {
    Open,
    ResolvedKeepLocal,
    ResolvedKeepRemote,
    ResolvedMerged,
    SkippedForNow,
}

/**
 * One conflict path on a durable session page.
 *
 * [kind] is a named wire string: `markdown` | `binary`.
 * Digests / artifact refs only on the list wire — no body bytes.
 */
data class RemoteSyncConflictPath(
    val path: String,
    val kind: String,
    val localDigest: String?,
    val remoteDigest: String?,
    val baselineDigest: String?,
    val remoteTokenPresent: Boolean,
    val localArtifactRef: String?,
    val remoteArtifactRef: String?,
    val baselineArtifactRef: String? = null,
    val status: RemoteSyncConflictPathStatus,
) {
    val isBinary: Boolean
        get() = kind.equals("binary", ignoreCase = true)

    val isMarkdown: Boolean
        get() = kind.equals("markdown", ignoreCase = true)
}

data class RemoteSyncConflictPage(
    val sessionId: String,
    val conflictRevision: Long,
    val items: List<RemoteSyncConflictPath>,
    val nextCursor: Int?,
)

/**
 * One user resolution submission.
 *
 * [kind] is a named wire string: `keep_local` | `keep_remote` | `merged_body` | `skip_for_now`.
 * [mergedBody] is required only for `merged_body`.
 */
data class RemoteSyncConflictResolution(
    val path: String,
    val kind: String,
    val mergedBody: String? = null,
) {
    companion object {
        const val KIND_KEEP_LOCAL: String = "keep_local"
        const val KIND_KEEP_REMOTE: String = "keep_remote"
        const val KIND_MERGED_BODY: String = "merged_body"
        const val KIND_SKIP_FOR_NOW: String = "skip_for_now"
    }
}

data class RemoteSyncConflictResolveResult(
    val sessionId: String,
    val conflictRevision: Long,
    val appliedPaths: List<String>,
)

/** Structured Sync Center boundary failure (no secret material). */
data class RemoteSyncCenterFailure(
    val category: String,
    val code: String,
    val retryDisposition: String,
    val diagnostic: String,
    val operationId: String? = null,
    val jobId: String? = null,
) : Exception("remote sync center: category=$category code=$code")

/**
 * Binary conflict detail facts for Sync Center (MIME / size / digest / source).
 *
 * No text preview fields — binary conflicts must not invent textual content.
 */
data class RemoteSyncBinaryConflictFacts(
    val path: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val localDigest: String?,
    val remoteDigest: String?,
    val baselineDigest: String?,
    val sourceLabel: String,
)

/**
 * Markdown conflict detail shell: base / local / remote digests + optional bodies when available.
 *
 * Dark data adapter loads bodies from durable artifact refs when present; null means digest-only
 * honesty (missing ref). Invalid UTF-8 fails closed at the adapter boundary.
 */
data class RemoteSyncMarkdownConflictFacts(
    val path: String,
    val baseDigest: String?,
    val localDigest: String?,
    val remoteDigest: String?,
    val baseBody: String? = null,
    val localBody: String? = null,
    val remoteBody: String? = null,
    val mergedDraft: String? = null,
)

/** Active backend label for config summary shell (presentation only). */
enum class RemoteSyncBackendLabel {
    None,
    Git,
    WebDav,
    S3,
}

/**
 * Config summary shown in Sync Center and Settings entry (dark shell).
 *
 * Schedule / last verified are presentation stubs until production scheduler cutover.
 */
data class RemoteSyncConfigSummary(
    val backend: RemoteSyncBackendLabel,
    val attentionCount: Int,
    val lastVerifiedAtEpochMillis: Long?,
    val schedulePolicyLabel: String?,
)

/** Session phase surface for Sync Center (presentation shell). */
enum class RemoteSyncSessionPhase {
    Idle,
    Preflight,
    Snapshot,
    Plan,
    Apply,
    Verify,
    ConflictOpen,
    Cancelling,
    Failed,
    Completed,
}

data class RemoteSyncSessionProgress(
    val phase: RemoteSyncSessionPhase,
    val completedActions: Int,
    val totalActions: Int?,
    val canCancel: Boolean,
)
