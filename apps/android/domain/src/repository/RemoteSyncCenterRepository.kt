package com.lomo.domain.repository

import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPage
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncConflictResolveResult
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts
import com.lomo.domain.model.RemoteSyncSessionProgress

/**
 * Sync Center repository contract.
 *
 * Coarse conflict list/resolve + config/session projection + optional detail body ports.
 * Implemented in `data` over the durable Rust cycle/conflict records; host tests use fakes.
 * App ViewModels depend on this domain port only (never `com.lomo.data.*`).
 *
 * Markdown detail may load base/local/remote UTF-8 bodies when durable artifact refs resolve.
 * Binary detail never invents text preview bodies (MIME/size/digest/source only).
 */
interface RemoteSyncCenterRepository {
    /**
     * Config snapshot + durable cycle-fact projection (attention count, last verified).
     */
    suspend fun configSummary(workspaceRoot: String): RemoteSyncConfigSummary

    /**
     * Durable cycle-record projection (`cycle_state.rec` is the sole status authority).
     */
    suspend fun sessionProgress(workspaceRoot: String): RemoteSyncSessionProgress

    /**
     * Persists a durable cancel request bound to the running cycle's identity fence
     * (`cancel_request.rec` via `sync_request_cancel`). Returns the post-write progress
     * projection; boundary failures propagate as structured errors.
     */
    suspend fun requestCancel(workspaceRoot: String): RemoteSyncSessionProgress

    fun listConflicts(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ): RemoteSyncConflictPage

    fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult

    /**
     * Markdown conflict detail facts for [path].
     *
     * When durable artifacts are available, bodies are loaded as UTF-8. Missing artifacts leave the
     * corresponding body null (digest-only honesty). Never called for binary paths.
     */
    fun markdownConflictFacts(
        workspaceRoot: String,
        path: RemoteSyncConflictPath,
        mergedDraft: String?,
    ): RemoteSyncMarkdownConflictFacts

    /**
     * Binary conflict detail facts for [path].
     *
     * MIME/size remain null unless a future owner port provides them. Never invents text body
     * previews from artifact bytes.
     */
    fun binaryConflictFacts(
        workspaceRoot: String,
        path: RemoteSyncConflictPath,
    ): RemoteSyncBinaryConflictFacts
}
