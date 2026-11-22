package com.lomo.data.engine

import com.lomo.data.engine.store.toStoreLong
import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.domain.model.DerivedIndexRebuildSummary
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.MediaDirectoryChange
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.RecoveryDiagnosticReport
import com.lomo.domain.model.RecoveryWorkspaceKind
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAttachmentDirectories
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.WorkspaceMount
import com.lomo.domain.model.WorkspaceProcessDuty
import com.lomo.domain.model.canRebuildDerivedIndex
import com.lomo.domain.model.toDiagnosticReport
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.EngineReadinessRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Sole production owner of the Rust engine lifecycle for the process.
 *
 * Construction publishes [EngineReadiness.Opening]. When [ownsNativeEngine] owns the engine, an
 * explicit [requestEngineStart] launches one owned coroutine that opens a persisted workspace
 * engine directly, or a bootstrap engine only when no workspace is configured / restore is
 * skipped. Projection-only processes (Glance) never open native, so widget wakes do not pay
 * restore and rebuild. Native acquisition therefore never runs on the constructing thread and
 * never follows graph resolution alone. A bootstrap engine that cannot
 * be acquired leaves the session in structured `ReadOnlyRecovery` with no adapter rather than
 * failing graph construction. When a Direct/SAF
 * root is selected (or restored once from persisted settings), [activateWorkspace] runs Prepared →
 * RetiringPrevious → Committed: it opens a candidate engine, promotes it once it reaches
 * [EngineReadiness.Ready], and releases the previous owner. A still-verified previous mount
 * publishes [ProjectionFreshness.Revalidating] during prepare; commit transcribes the candidate
 * revision as [ProjectionFreshness.Verified]. Soft Recovery and hard open failure leave the previous
 * engine authoritative and restore Verified when that authority remains. Session-owned
 * recovery authority freezes readiness so a later bootstrap cannot resnapshot Recovery away after
 * cold-restore failure.
 *
 * Workspace switch orchestration is owned by domain
 * [com.lomo.domain.usecase.SwitchRootStorageUseCase]; this session exclusively owns candidate open,
 * projection rebuild, and promotion and does not race-observe selection changes after the initial
 * cold restore.
 */
internal class ManagedEngineSession(
    /**
     * Candidate-open inputs forwarded verbatim into the preparer. `sessionDateFormat` is sampled
     * at every candidate open so a settings change applies to the next session; Rust
     * `DateFormat::from_token` is the sole validator (foreign tokens fail closed at the FFI
     * boundary).
     */
    private val candidateOpen: CandidateOpenContext,
    private val capabilityRegistry: CapabilityRegistry,
    private val directorySettingsRepository: DirectorySettingsRepository,
    private val appScope: CoroutineScope,
    private val invalidation: StoreInvalidationBus,
    private val ownsNativeEngine: WorkspaceProcessDuty = WorkspaceProcessDuty.OWNED,
    /**
     * Blocking-lane dispatcher for candidate acquisition. Native workspace opens perform JNI
     * work and synchronous [PlatformBatchHost] callbacks that issue SAF `ContentResolver`
     * binder calls, so they must never inherit the caller's dispatcher (commonly
     * `Dispatchers.Main`) — the same law [requestEngineStart] already applies via [appScope].
     */
    override val engineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ManagedEngineCapabilities(),
    EngineReadinessRepository,
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val activationMutex = Mutex()
    private val adapterLease = ReentrantReadWriteLock()
    private val _mount = MutableStateFlow(WorkspaceMount.Opening)
    private val activationGeneration = AtomicLong(0)
    private val candidatePreparer =
        WorkspaceCandidatePreparer(
            filesDir = candidateOpen.filesDir,
            capabilityRegistry = capabilityRegistry,
            openAdapter = candidateOpen.openAdapter,
            isContentUri = candidateOpen.isContentUri,
            sessionDateFormat = candidateOpen.sessionDateFormat,
        )

    // behavior-contract: stateful-var-ok: this is process-owned resource/lease/job handle, not a query cache
    private var activeAdapter: RustEngineAdapter? = null

    // behavior-contract: stateful-var-ok: this is process-owned resource/lease/job handle, not a query cache
    private var activeCapabilityToken: String? = null

    // behavior-contract: stateful-var-ok: this is process-owned resource/lease/job handle, not a query cache
    private var mirrorJob: kotlinx.coroutines.Job? = null

    /**
     * When non-null, session readiness is held by recovery authority and adapter mirrors must not
     * overwrite it with bootstrap Awaiting/Opening. Cleared only by a successful Ready install.
     */
    private val recoveryAuthority = AtomicReference<EngineReadiness.ReadOnlyRecovery?>(null)
    private val holdRecoveryAuthority: (EngineReadiness.ReadOnlyRecovery) -> Unit = { recovery ->
        recoveryAuthority.set(recovery)
        publishMount(
            WorkspaceMount(
                readiness = recovery,
                location = _mount.value.location,
                authority = null,
                freshness = ProjectionFreshness.Unavailable,
            ),
        )
    }

    private val engineStartRequested = AtomicBoolean(false)

    /**
     * Explicit engine start: idempotent across repeated requests and no-op for projection-only
     * processes, so a transient caller (tile, widget, recording state read) can never mount the
     * vault merely by resolving the graph.
     */
    override suspend fun requestEngineStart(): EngineReadiness {
        check(!closed.get()) { "Managed engine session is closed" }
        if (!ownsNativeEngine.ownsNativeEngine) {
            return readiness.value
        }
        if (engineStartRequested.compareAndSet(false, true)) {
            appScope.launch {
                startOwnedEngine()
            }
        }
        return mount.first { it.readiness !is EngineReadiness.Opening }.readiness
    }

    override val mount: StateFlow<WorkspaceMount> = _mount.asStateFlow()
    override val readiness: StateFlow<EngineReadiness> =
        ProjectedStateFlow(_mount, WorkspaceMount::readiness)
    override val activeWorkspaceLocation: StateFlow<StorageLocation?> =
        ProjectedStateFlow(_mount, WorkspaceMount::location)
    override val workspaceAuthority: StateFlow<WorkspaceAuthority?> =
        ProjectedStateFlow(_mount, WorkspaceMount::authority)
    override val projectionFreshness: StateFlow<ProjectionFreshness> =
        ProjectedStateFlow(_mount, WorkspaceMount::freshness)

    private fun publishMount(mount: WorkspaceMount) {
        _mount.value = mount
    }

    override suspend fun resnapshot() {
        check(!closed.get()) { "Managed engine session is closed" }
        // The read lease queues behind a retiring writer for the whole workspace close, and the
        // native state read may drive an opening job; neither may run on the caller (Main) thread.
        withContext(engineDispatcher) {
            // Recovery authority is session-owned; do not let bootstrap overwrite it.
            if (recoveryAuthority.get() != null) return@withContext
            adapterLease.read {
                activeAdapter?.resnapshot()
            }
        }
    }

    override suspend fun createRecoveryDiagnosticReport(): RecoveryDiagnosticReport {
        check(!closed.get()) { "Managed engine session is closed" }
        val recovery =
            _mount.value.readiness as? EngineReadiness.ReadOnlyRecovery
                ?: error("Recovery diagnostic export requires ReadOnlyRecovery")
        val location = _mount.value.location ?: directorySettingsRepository.currentRootLocation()
        val workspaceKind =
            when {
                location == null -> RecoveryWorkspaceKind.NONE
                candidateOpen.isContentUri(location.raw) -> RecoveryWorkspaceKind.SAF
                else -> RecoveryWorkspaceKind.DIRECT
            }
        return recovery.toDiagnosticReport(workspaceKind)
    }

    override suspend fun rebuildDerivedIndex(): DerivedIndexRebuildSummary {
        check(!closed.get()) { "Managed engine session is closed" }
        check(ownsNativeEngine.ownsNativeEngine) { "Projection-only process must not open a workspace engine" }
        engineStartRequested.set(true)
        return withContext(engineDispatcher) {
            activationMutex.withLock {
                val recovery =
                    _mount.value.readiness as? EngineReadiness.ReadOnlyRecovery
                        ?: error("Derived-index rebuild requires ReadOnlyRecovery")
                require(recovery.canRebuildDerivedIndex()) {
                    "Recovery ${recovery.code} is not a rebuildable SQLite failure"
                }
                val location =
                    checkNotNull(directorySettingsRepository.currentRootLocation()) {
                        "Derived-index rebuild requires a selected workspace"
                    }

                retireWorkspaceForReopen(location)

                try {
                    val rebuild =
                        candidatePreparer.rebuildProjectionForRecovery(
                            mediaRoots = directorySettingsRepository.mediaRootsFor(null),
                            location = location,
                            batchSize = RECOVERY_REBUILD_BATCH_SIZE,
                        )

                    // Reopen from the repaired projection and promote only a fully Ready candidate. The
                    // previous bootstrap/recovery owner remains non-writable until this atomic install.
                    val prepared = candidatePreparer.prepare(
                        location,
                        directorySettingsRepository.mediaRootsFor(null),
                    )
                    promoteCandidate(candidate = prepared, location = location)
                    DerivedIndexRebuildSummary(
                        memosIndexed = rebuild.memosIndexed,
                        fileCount = rebuild.fileCount,
                        attachmentCount = rebuild.attachmentCount,
                        corruptLomoIsolated = rebuild.corruptLomoIsolated,
                        highWaterRevision = rebuild.highWaterRevision,
                    )
                } catch (failure: Exception) {
                    holdRecoveryAuthority(recoveryFromThrowable(failure))
                    throw failure
                }
            }
        }
    }

    override suspend fun activateWorkspace(location: StorageLocation, mediaChange: MediaDirectoryChange?) {
        check(!closed.get()) { "Managed engine session is closed" }
        check(ownsNativeEngine.ownsNativeEngine) { "Projection-only process must not open a workspace engine" }
        engineStartRequested.set(true)
        require(location.raw.isNotBlank()) { "Workspace location must be non-blank" }
        withContext(engineDispatcher) {
            activationMutex.withLock {
                check(!closed.get()) { "Managed engine session is closed" }
                val current = _mount.value
                if (mediaChange == null && current.location == location &&
                    current.freshness is ProjectionFreshness.Retained
                ) {
                    val adapter = adapterLease.read { checkNotNull(activeAdapter) }
                    revalidateRetainedProjection(adapter, checkNotNull(current.authority))
                    return@withLock
                }
                val previousVerified = WorkspaceMountTransitions.verifiedOrNull(_mount.value)
                val reopening = retireWorkspaceForReopen(location)
                if (!reopening) WorkspaceMountTransitions.revalidating(previousVerified)?.let(::publishMount)
                try {
                    promoteWorkspace(
                        location,
                        directorySettingsRepository.mediaRootsFor(mediaChange),
                        allowRetainedProjection = mediaChange != null,
                    )
                } catch (failure: Exception) {
                    if (reopening) {
                        holdRecoveryAuthority(recoveryFromThrowable(failure))
                    } else {
                        WorkspaceMountTransitions.restoreVerified(_mount.value, previousVerified)
                            ?.let(::publishMount)
                    }
                    throw failure
                }
            }
        }
    }

    /** A second native owner cannot acquire the same workspace's process lock. */
    private fun retireWorkspaceForReopen(location: StorageLocation): Boolean {
        val identity = storageDirectoryIdentity(location.raw)
        val retirement = adapterLease.write {
            val previous = activeAdapter ?: return@write null
            val token = activeCapabilityToken ?: return@write null
            if (capabilityRegistry.resolve(token).stableWorkspaceId != identity) return@write null
            detachActiveAdapterLocked()
            publishMount(
                WorkspaceMount(EngineReadiness.Opening, location, null, ProjectionFreshness.Unavailable),
            )
            AdapterRetirement(token, runCatching(previous::close).exceptionOrNull())
        } ?: return false
        retirement.previousToken?.let(capabilityRegistry::revoke)
        retirement.failure?.let { failure ->
            holdRecoveryAuthority(recoveryFromThrowable(failure))
            throw failure
        }
        return true
    }

    /**
     * Success path opens the persisted workspace engine only. Bootstrap is the no-workspace host,
     * never a paid precondition of a configured vault.
     */
    private suspend fun startOwnedEngine() {
        withContext(engineDispatcher) {
            activationMutex.withLock {
                if (closed.get() || recoveryAuthority.get() != null || _mount.value.authority != null) {
                    return@withContext
                }
                val existing =
                    try {
                        directorySettingsRepository.recoverRootLocation()
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        holdRecoveryAuthority(recoveryFromThrowable(failure))
                        return@withContext
                    }
                if (existing != null && existing.raw.isNotBlank()) {
                    try {
                        val committed = directorySettingsRepository.currentRootLocation()
                        val pending = directorySettingsRepository.pendingRootTransition()
                        if (committed == existing && pending == null) {
                            promoteWorkspace(
                                existing,
                                directorySettingsRepository.mediaRootsFor(null),
                                allowRetainedProjection = true,
                            )
                        }
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        holdRecoveryAuthority(recoveryFromThrowable(failure).copy(retryLocation = existing))
                    }
                }
                if (!closed.get() && recoveryAuthority.get() == null && activeAdapter == null) {
                    installBootstrapLocked()
                }
            }
        }
    }

    /**
     * Explicit switches require a verified candidate before promotion, so an indexing failure
     * can still roll back the selection. Cold restore alone may present a retained projection
     * read-only while revalidating it. A root without a retained projection is indexed during open.
     * Caller holds [activationMutex]; the revalidation job queues behind it.
     */
    private suspend fun promoteWorkspace(
        location: StorageLocation,
        mediaRoots: Map<String, String>,
        allowRetainedProjection: Boolean,
    ) {
        val prepared =
            candidatePreparer.prepare(
                location,
                mediaRoots,
                allowRetainedProjection = allowRetainedProjection,
            )
        val authority = promoteCandidate(candidate = prepared, location = location)
        if (!prepared.projectionVerified && authority != null) {
            appScope.launch(engineDispatcher) {
                activationMutex.withLock { revalidateRetainedProjection(prepared.adapter, authority) }
            }
        }
    }

    /** The activation mutex keeps the same installed adapter throughout retry. */
    private fun revalidateRetainedProjection(adapter: RustEngineAdapter, authority: WorkspaceAuthority) {
        if (adapterLease.read { activeAdapter !== adapter } || closed.get()) return
        val result = try {
            adapterLease.read { adapter.startRebuild(64u) }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            adapterLease.write {
                if (!closed.get() && activeAdapter === adapter) {
                    val recovery = recoveryFromThrowable(failure)
                        .copy(workspaceStage = com.lomo.domain.model.WorkspaceFailureStage.INDEX)
                    recoveryAuthority.set(recovery)
                    publishMount(
                        _mount.value.copy(
                            readiness = recovery,
                            freshness = ProjectionFreshness.Retained(authority.projectionRevision),
                        ),
                    )
                }
            }
            return
        }
        adapterLease.write {
            if (!closed.get() && activeAdapter === adapter) {
                invalidation.reanchorProjection(result.highWaterRevision.toStoreLong("projection_revision"))
                recoveryAuthority.set(null)
                publishMount(
                    _mount.value.copy(
                        readiness = EngineReadiness.Ready,
                        authority = authority.copy(projectionRevision = result.highWaterRevision),
                        freshness = ProjectionFreshness.Verified(result.highWaterRevision),
                    ),
                )
            }
        }
    }

    private fun installBootstrapLocked() {
        runCatching { candidatePreparer.openBootstrap() }
            .onSuccess { candidate ->
                adapterLease.write {
                    if (closed.get() || recoveryAuthority.get() != null || activeAdapter != null) {
                        releaseCandidate(
                            candidate.adapter,
                            candidate.capabilityToken,
                            IllegalStateException("Bootstrap engine was superseded before install"),
                            capabilityRegistry,
                        )
                        return@write
                    }
                    installAdapterLocked(
                        candidate.adapter,
                        candidate.capabilityToken,
                        WorkspaceMount(
                            readiness = candidate.adapter.readiness.value,
                            location = null,
                            authority = null,
                            freshness = ProjectionFreshness.Unavailable,
                        ),
                    )
                }
            }
            .onFailure { error -> holdRecoveryAuthority(recoveryFromThrowable(error)) }
    }

    override suspend fun clearWorkspace() {
        if (closed.get()) return
        check(ownsNativeEngine.ownsNativeEngine) { "Projection-only process must not open a workspace engine" }
        engineStartRequested.set(true)
        withContext(engineDispatcher) {
            activationMutex.withLock {
                if (closed.get()) return@withContext
                // Reselect / failed first selection: open awaiting engine under the activation mutex.
                promoteCandidate(candidate = candidatePreparer.openBootstrap(), location = null)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val retirement =
            adapterLease.write {
                val token = activeCapabilityToken
                val adapter = activeAdapter
                detachActiveAdapterLocked()
                recoveryAuthority.set(null)
                publishMount(
                    WorkspaceMount(
                        readiness = EngineReadiness.ShuttingDown,
                        location = null,
                        authority = null,
                        freshness = ProjectionFreshness.Unavailable,
                    ),
                )
                AdapterRetirement(
                    previousToken = token,
                    failure = adapter?.let { runCatching(it::close).exceptionOrNull() },
                )
            }
        // A failing engine close must not skip capability revoke or the terminal readiness value.
        retirement.previousToken?.let(capabilityRegistry::revoke)
        retirement.failure?.let { throw it }
    }

    override fun rebuildActiveStore(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult =
        withActiveWorkspaceAdapter { adapter -> adapter.startRebuild(batchSize) }

    /**
     * RetiringPrevious → Committed: the outgoing owner is released first and only a complete
     * retirement publishes [candidate] as the committed authority.
     */
    private fun promoteCandidate(
        candidate: ManagedWorkspaceCandidate,
        location: StorageLocation?,
    ): WorkspaceAuthority? {
        val promotion =
            try {
                retirePreviousAndCommit(
                    candidate,
                    location,
                )
            } catch (failure: Exception) {
                // Commitment validation can throw before the adapter lease mutates. Release
                // the candidate unless it already became the active owner, so its native
                // handle and capability token cannot leak with the workspace unprepared.
                if (adapterLease.read { activeAdapter !== candidate.adapter }) {
                    releaseCandidate(
                        candidate.adapter,
                        candidate.capabilityToken,
                        failure,
                        capabilityRegistry,
                    )
                }
                throw failure
            }
        return when (promotion) {
            is AdapterPromotion.Committed -> {
                promotion.previousToken
                    ?.takeIf { it != candidate.capabilityToken }
                    ?.let(capabilityRegistry::revoke)
                promotion.authority
            }

            is AdapterPromotion.CandidateRejected -> {
                rejectWorkspaceCandidate(
                    candidate,
                    promotion.recovery.toActivationFailure(),
                    capabilityRegistry,
                )
            }

            is AdapterPromotion.RetirementFailed -> {
                val failure = promotion.failure
                // The previous owner could not be retired, so two writers could otherwise hold the
                // same workspace. Publish neither and freeze the session in structured recovery.
                promotion.previousToken
                    ?.takeIf { it != candidate.capabilityToken }
                    ?.let(capabilityRegistry::revoke)
                holdRecoveryAuthority(
                    EngineReadiness.ReadOnlyRecovery(
                        category = EngineFailureCategory.INTERNAL,
                        code = "workspace_retire_failed",
                        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                        diagnostic = failure.message ?: "Previous workspace engine could not be retired",
                    ),
                )
                rejectWorkspaceCandidate(candidate, failure, capabilityRegistry)
            }
        }
    }

    private fun retirePreviousAndCommit(
        candidate: ManagedWorkspaceCandidate,
        location: StorageLocation?,
    ): AdapterPromotion = adapterLease.write {
        candidate.adapter.withReadinessAtCommit { candidateReadiness ->
            if (candidate.workspaceId != null && candidateReadiness !is EngineReadiness.Ready) {
                return@withReadinessAtCommit AdapterPromotion.CandidateRejected(
                    workspaceOpenRecovery(candidateReadiness),
                )
            }
            // Commitment validation precedes adapter mutation: mint the generation and
            // re-anchor the publication clock while the outgoing owner still holds the
            // lease. A rejected reanchor then leaves the previous adapter installed and
            // its mount authoritative instead of a candidate with no published authority.
            val authority =
                candidate.workspaceId?.let { id ->
                    WorkspaceAuthority(
                        workspaceId = id,
                        generation = activationGeneration.incrementAndGet(),
                        projectionRevision = checkNotNull(candidate.projectionRevision),
                    )
                }
            if (authority != null) {
                invalidation.reanchor(
                    generation = authority.generation,
                    highWaterRevision = authority.projectionRevision.toStoreLong("projection_revision"),
                )
            }
            val token = activeCapabilityToken
            val previous = activeAdapter
            detachActiveAdapterLocked()
            // Exclusive lease waits for every in-flight workspace call before close.
            val failure = previous?.let { runCatching(it::close).exceptionOrNull() }
            if (failure != null) {
                return@withReadinessAtCommit AdapterPromotion.RetirementFailed(token, failure)
            }
            // Ready install clears any prior recovery authority and becomes sole publisher.
            recoveryAuthority.set(null)
            val media = candidate.capabilityToken?.let(capabilityRegistry::mediaRoots)
            installAdapterLocked(
                candidate.adapter,
                candidate.capabilityToken,
                WorkspaceMount(
                    readiness = candidateReadiness,
                    location = location,
                    authority = authority,
                    attachments = WorkspaceAttachmentDirectories(
                        images = media?.get("images")?.storageLocation(),
                        recordings = media?.get("voice")?.storageLocation(),
                    ),
                    freshness =
                    if (authority == null) {
                        ProjectionFreshness.Unavailable
                    } else {
                        if (candidate.projectionVerified) {
                            ProjectionFreshness.Verified(checkNotNull(candidate.projectionRevision))
                        } else {
                            ProjectionFreshness.Revalidating(checkNotNull(candidate.projectionRevision))
                        }
                    },
                ),
            )
            AdapterPromotion.Committed(token, authority)
        }
    }

    private fun installAdapterLocked(adapter: RustEngineAdapter, capabilityToken: String?, mount: WorkspaceMount) {
        mirrorJob?.cancel()
        activeAdapter = adapter
        activeCapabilityToken = capabilityToken
        publishMount(mount)
        mirrorJob =
            appScope.launch {
                adapter.readiness.collect { value ->
                    adapterLease.read {
                        if (!closed.get() && activeAdapter === adapter && recoveryAuthority.get() == null) {
                            if (value is EngineReadiness.Ready) {
                                val current = _mount.value
                                if (current.authority != null) {
                                    publishMount(current.copy(readiness = value))
                                }
                            } else {
                                publishMount(
                                    WorkspaceMount(
                                        readiness = value,
                                        location = _mount.value.location,
                                        authority = null,
                                        freshness = ProjectionFreshness.Unavailable,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
    }

    /** Drops the outgoing owner before it is closed, so no route can reach a retiring adapter. */
    private fun detachActiveAdapterLocked() {
        mirrorJob?.cancel()
        mirrorJob = null
        activeAdapter = null
        activeCapabilityToken = null
    }

    protected override fun <T> withActiveWorkspaceAdapter(block: (RustEngineAdapter) -> T): T =
        withWorkspaceAdapter(WorkspaceMount::admitsProjectionWrites, block)

    protected override fun <T> withProjectionAdapter(block: (RustEngineAdapter) -> T): T =
        withWorkspaceAdapter(WorkspaceMount::admitsProjectionReads, block)

    private fun <T> withWorkspaceAdapter(admits: (WorkspaceMount) -> Boolean, block: (RustEngineAdapter) -> T): T {
        check(!closed.get()) { "Managed engine session is closed" }
        return adapterLease.read {
            check(admits(_mount.value)) {
                "Workspace projection does not permit this operation"
            }
            val adapter = activeAdapter ?: error("Managed engine session has no active adapter")
            block(adapter)
        }
    }

    /**
     * Installation-level capabilities remain available on the bootstrap Awaiting engine.
     * Capture the owner under the routing lock, then let its native-handle lease protect
     * the call. A long-lived LAN observation must not pin the workspace routing lock:
     * retirement needs to reach port.close(), which wakes that observation before draining it.
     * Workspace-scoped calls retain their routing lease through completion above.
     */
    protected override fun <T> withActiveEngineAdapter(block: (RustEngineAdapter) -> T): T {
        check(!closed.get()) { "Managed engine session is closed" }
        val adapter = adapterLease.read {
            check(!closed.get()) { "Managed engine session is closed" }
            activeAdapter ?: error("Managed engine session has no active adapter")
        }
        return block(adapter)
    }

    protected override fun <T> withInstalledEngineAdapter(unhosted: () -> T, block: (RustEngineAdapter) -> T): T {
        check(!closed.get()) { "Managed engine session is closed" }
        return when (val adapter = adapterLease.read { activeAdapter }) {
            null -> unhosted()
            else -> block(adapter)
        }
    }

    private sealed interface AdapterPromotion {
        data class Committed(val previousToken: String?, val authority: WorkspaceAuthority?) : AdapterPromotion

        data class CandidateRejected(val recovery: EngineReadiness.ReadOnlyRecovery) : AdapterPromotion

        data class RetirementFailed(val previousToken: String?, val failure: Throwable) : AdapterPromotion
    }

    /** Outcome of releasing the outgoing workspace owner during process close. */
    private data class AdapterRetirement(val previousToken: String?, val failure: Throwable?)

    companion object {
        private const val RECOVERY_REBUILD_BATCH_SIZE: UInt = 64u
    }
}

private fun RootCapabilityGrant.storageLocation(): StorageLocation = when (this) {
    is SafCapabilityGrant -> StorageLocation(treeUri)
    is DirectCapabilityGrant -> StorageLocation(canonicalRoot.path)
}

private suspend fun DirectorySettingsRepository.mediaRootsFor(change: MediaDirectoryChange?): Map<String, String> =
    buildMap {
        currentLocation(StorageArea.IMAGE)?.let { put("images", it.raw) }
        currentLocation(StorageArea.VOICE)?.let { put("voice", it.raw) }
        if (change != null) {
            val namespace = when (change.area) {
                StorageArea.IMAGE -> "images"

                StorageArea.VOICE -> "voice"

                StorageArea.ROOT, StorageArea.SYNC_INBOX ->
                    error("Invalid media transition area")
            }
            val candidate = change.candidate
            if (candidate == null) remove(namespace) else put(namespace, candidate.raw)
        }
    }

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ProjectedStateFlow<S, T>(private val source: StateFlow<S>, private val project: (S) -> T) :
    StateFlow<T> {
    override val value: T
        get() = project(source.value)

    override val replayCache: List<T>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        var hasEmitted = false
        var lastValue: Any? = null
        source.collect { state ->
            val projected = project(state)
            if (!hasEmitted || projected != lastValue) {
                hasEmitted = true
                lastValue = projected
                collector.emit(projected)
            }
        }
    }
}
