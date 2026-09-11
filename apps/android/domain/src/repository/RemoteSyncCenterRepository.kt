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
 * Stage-5 dark Sync Center repository contract (P5-10 / Wave-4 adapter).
 *
 * Coarse conflict list/resolve + config/session presentation shells + optional detail body ports.
 * Implementations live in `data` (dark unregistered) or host-test fakes. **Not** registered in
 * production DI / navigation until P5-13. App ViewModels depend on this domain port only (never
 * `com.lomo.data.*`).
 *
 * Markdown detail may load base/local/remote UTF-8 bodies when durable artifact refs resolve.
 * Binary detail never invents text preview bodies (MIME/size/digest/source only).
 */
interface RemoteSyncCenterRepository {
    fun configSummary(workspaceRoot: String): RemoteSyncConfigSummary

    fun sessionProgress(workspaceRoot: String): RemoteSyncSessionProgress

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
