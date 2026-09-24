package com.lomo.data.engine.sync

/**
 * Host-facing sync conflict / lease / retry / cycle facts.
 *
 * Mapping-only surface for Sync Center / WorkManager runners. Business rules stay in Rust
 * (`lomo-sync` / `lomo-core` via free-function FFI); Kotlin never re-plans or fabricates
 * status.
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
    val     status: RemoteSyncConflictPathStatus,
)

/** Proven presence of a durable conflict session head. Absent ≠ Present with zero items. */
enum class RemoteSyncConflictSessionState {
    Absent,
    Present,
}

data class RemoteSyncConflictPage(
    val session: RemoteSyncConflictSessionState,
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
    ;

    companion object {
        /**
         * Unique typed map from a Rust-owned disposition name. Unknown wires fail closed as
         * [Never] (no fixed three-retry policy).
         */
        fun fromWire(name: String): RemoteSyncRetryDisposition =
            when (name.trim().lowercase(java.util.Locale.ROOT)) {
                "never" -> Never
                "after_user_action" -> AfterUserAction
                "transient" -> Transient
                else -> Never
            }
    }
}

/**
 * WorkManager-facing retry hint from Rust disposition mapping.
 *
 * [retryAfterMillis] is optional host policy input; free-function mapping may leave it null
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
    /** Mutations held because the provider offered no strong conditional-update validator. */
    val holdCount: Int,
    val openConflictPaths: Int,
    val conflictRevision: Long?,
    /** `never` | `after_user_action` | `transient` */
    val retryDisposition: String,
)

/**
 * Durable cycle record (`cycle_state.rec`) — the sole authority for remote sync status.
 *
 * `hasRecord=false` means the workspace never ran a cycle (`phase`=`idle`, all other fields are
 * wire defaults — honest "never synced", not fabricated zeros). `stateStamp` is the monotonic
 * freshness marker; a stale `running` record left by process death is repaired by the next
 * cycle start inside Rust, so readers always see the last durable fact.
 */
data class RemoteSyncCycleStatus(
    val hasRecord: Boolean,
    val cycleSeq: Long,
    val cycleId: String,
    /** Identity fence the cycle ran under (`generation|dataset|remote-identity`). */
    val fenceKey: String,
    /** `hermetic_fake` | `webdav` | `s3` | `git` (empty when no record). */
    val backendKind: String,
    val sessionId: String,
    val applyRemote: Boolean,
    /** `idle` | `running` | `completed` | `failed` | `cancelled`. */
    val phase: String,
    /** `planning` | `applying` | `finished` | `failed` | `cancelled` | `interrupted`. */
    val stage: String,
    val ensurePresentCount: Int,
    val ensureAbsentCount: Int,
    val pullPresentCount: Int,
    val openConflictCount: Int,
    val holdCount: Int,
    val localEntryCount: Int,
    val remoteListedCount: Int,
    val baselineEntryCount: Int,
    /** Intent pages published before terminal; on `cancelled` this is the cancellation point. */
    val pagesApplied: Int,
    val baselineAdvanced: Boolean,
    /** `never` | `after_user_action` | `transient` (empty while running). */
    val retryDisposition: String,
    val failureCode: String?,
    val failureMessage: String?,
    val cancelRequested: Boolean,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val finishedAtMs: Long?,
    /** Sticky: last apply cycle that completed without a transient/failure outcome. */
    val lastSuccessfulAtMs: Long?,
    /** Monotonic freshness marker (epoch for late-result rejection). */
    val stateStamp: Long,
) {
    companion object {
        const val PHASE_IDLE: String = "idle"
        const val PHASE_RUNNING: String = "running"
        const val PHASE_COMPLETED: String = "completed"
        const val PHASE_FAILED: String = "failed"
        const val PHASE_CANCELLED: String = "cancelled"
        const val STAGE_PLANNING: String = "planning"
        const val STAGE_APPLYING: String = "applying"
        const val STAGE_FINISHED: String = "finished"
        const val STAGE_FAILED: String = "failed"
        const val STAGE_CANCELLED: String = "cancelled"
        const val STAGE_INTERRUPTED: String = "interrupted"
    }
}

/**
 * Real backend probe round-trip facts (`sync_probe_backend`): same adapter construction as a
 * real cycle + capabilities + listing — never an enqueue acceptance.
 */
data class RemoteSyncBackendProbe(
    val backendKind: String,
    val listedEntryCount: Int,
    val snapshotRevisionPresent: Boolean,
    val conditionalWrite: Boolean,
    val conditionalDelete: Boolean,
    val probedAtMs: Long,
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
