package com.lomo.data.engine.sync

/**
 * Stage-5 dark (P5-09) host-facing sync conflict / lease / retry facts.
 *
 * Mapping-only surface for Sync Center / WorkManager runners. Business rules stay in Rust
 * (`lomo-sync` / `lomo-core` via dark free-function FFI). **Not** registered in production DI,
 * navigation, or WorkManager until P5-13.
 *
 * Wire invariants:
 * - digests / artifact refs only (no body bytes on list)
 * - remote token is presence-only
 * - secrets appear only as process-local lease ids (never plaintext on the journal/wire)
 * - retry disposition has no fixed three-retry policy
 */

enum class RemoteSyncConflictPathStatus {
    Open,
    ResolvedKeepLocal,
    ResolvedKeepRemote,
    ResolvedMerged,
    SkippedForNow,
}

data class RemoteSyncConflictPath(
    val path: String,
    /** `markdown` | `binary` (named; not enum ordinals). */
    val kind: String,
    val localDigest: String?,
    val remoteDigest: String?,
    val baselineDigest: String?,
    val remoteTokenPresent: Boolean,
    val localArtifactRef: String?,
    val remoteArtifactRef: String?,
    val baselineArtifactRef: String? = null,
    val status: RemoteSyncConflictPathStatus,
)

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
)

data class RemoteSyncConflictResolveResult(
    val sessionId: String,
    val conflictRevision: Long,
    val appliedPaths: List<String>,
)

/** Opaque secret lease id wire — never plaintext secret material. */
data class RemoteSyncSecretLease(
    val leaseId: String,
)

enum class RemoteSyncRetryDisposition {
    Never,
    AfterUserAction,
    Transient,
}

/**
 * WorkManager-facing retry hint from Rust disposition mapping.
 *
 * [retryAfterMillis] is optional host policy input; dark free-function mapping may leave it null
 * (scheduler owns concrete delay).
 */
data class RemoteSyncRetryHint(
    val disposition: RemoteSyncRetryDisposition,
    val retryAfterMillis: Long? = null,
)

/**
 * Coarse plan/readiness cycle summary from Rust-owned `sync_inspect_cycle_plan`.
 *
 * Counts and disposition are conversion-only; Kotlin must not re-plan. No body bytes / secrets.
 */
data class RemoteSyncCyclePlanSummary(
    val sessionId: String,
    /** `first_takeover` | `incremental` */
    val sessionKind: String,
    val sessionRevision: Long,
    val baselineEstablished: Boolean,
    val ensurePresentCount: Int,
    val ensureAbsentCount: Int,
    val pullPresentCount: Int,
    val openConflictCount: Int,
    val openConflictPaths: Int,
    val conflictRevision: Long?,
    /** `never` | `after_user_action` | `transient` */
    val retryDisposition: String,
)

/**
 * Structured dark sync boundary failure (no secret material).
 *
 * Codes/categories come from Rust `EngineFailure` conversion; Kotlin does not invent planner rules.
 */
data class RemoteSyncBoundaryFailure(
    val category: String,
    val code: String,
    val retryDisposition: String,
    val diagnostic: String,
    val operationId: String? = null,
    val jobId: String? = null,
) : Exception("remote sync boundary: category=$category code=$code")
