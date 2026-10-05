package com.lomo.data.engine

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.DerivedIndexRebuildSummary
import com.lomo.domain.model.RecoveryDiagnosticReport
import com.lomo.domain.model.RecoveryWorkspaceKind
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.WorkspaceMount
import com.lomo.domain.model.WorkspaceProcessDuty
import com.lomo.domain.model.canRebuildDerivedIndex
import com.lomo.domain.model.toDiagnosticReport
import com.lomo.data.engine.store.toStoreLong
import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDateTime
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
    filesDir: File,
    private val capabilityRegistry: CapabilityRegistry,
    openAdapter: (NativeEngineOpenRequest) -> RustEngineAdapter,
    private val directorySettingsRepository: DirectorySettingsRepository,
    private val appScope: CoroutineScope,
    private val isContentUri: (String) -> Boolean,
    private val invalidation: StoreInvalidationBus,
    private val ownsNativeEngine: WorkspaceProcessDuty = WorkspaceProcessDuty.OWNED,
) : ManagedEngineCapabilities(),
    EngineReadinessRepository,
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val activationMutex = Mutex()
    private val adapterLease = ReentrantReadWriteLock()
    private val _readiness = MutableStateFlow<EngineReadiness>(EngineReadiness.Opening)
    private val _activeWorkspaceLocation = MutableStateFlow<StorageLocation?>(null)
    private val _workspaceAuthority = MutableStateFlow<WorkspaceAuthority?>(null)
    private val _projectionFreshness =
        MutableStateFlow<ProjectionFreshness>(ProjectionFreshness.Unavailable)
    private val _mount = MutableStateFlow(WorkspaceMount.Opening)
    private val activationGeneration = AtomicLong(0)
    private val candidatePreparer =
        WorkspaceCandidatePreparer(
            filesDir = filesDir,
            capabilityRegistry = capabilityRegistry,
            openAdapter = openAdapter,
            isContentUri = isContentUri,
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
                location = _activeWorkspaceLocation.value,
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
        return readiness.first { it !is EngineReadiness.Opening }
    }

    override val readiness: StateFlow<EngineReadiness> = _readiness.asStateFlow()
    override val activeWorkspaceLocation: StateFlow<StorageLocation?> =
        _activeWorkspaceLocation.asStateFlow()
    override val workspaceAuthority: StateFlow<WorkspaceAuthority?> =
        _workspaceAuthority.asStateFlow()
    override val projectionFreshness: StateFlow<ProjectionFreshness> =
        _projectionFreshness.asStateFlow()
    override val mount: StateFlow<WorkspaceMount> = _mount.asStateFlow()

    private fun publishMount(mount: WorkspaceMount) {
        _readiness.value = mount.readiness
        _activeWorkspaceLocation.value = mount.location
        _workspaceAuthority.value = mount.authority
        _projectionFreshness.value = mount.freshness
        _mount.value = mount
    }

    override fun resnapshot() {
        check(!closed.get()) { "Managed engine session is closed" }
        // Recovery authority is session-owned; do not let bootstrap overwrite it.
        if (recoveryAuthority.get() != null) return
        adapterLease.read {
            activeAdapter?.resnapshot()
        }
    }

    override suspend fun createRecoveryDiagnosticReport(): RecoveryDiagnosticReport {
        check(!closed.get()) { "Managed engine session is closed" }
        val recovery =
            _readiness.value as? EngineReadiness.ReadOnlyRecovery
                ?: error("Recovery diagnostic export requires ReadOnlyRecovery")
        val location = _activeWorkspaceLocation.value ?: directorySettingsRepository.currentRootLocation()
        val workspaceKind =
            when {
                location == null -> RecoveryWorkspaceKind.NONE
                isContentUri(location.raw) -> RecoveryWorkspaceKind.SAF
                else -> RecoveryWorkspaceKind.DIRECT
            }
        return recovery.toDiagnosticReport(workspaceKind)
    }

    override suspend fun rebuildDerivedIndex(): DerivedIndexRebuildSummary {
        check(!closed.get()) { "Managed engine session is closed" }
        check(ownsNativeEngine.ownsNativeEngine) { "Projection-only process must not open a workspace engine" }
        engineStartRequested.set(true)
        return activationMutex.withLock {
            val recovery =
                _readiness.value as? EngineReadiness.ReadOnlyRecovery
                    ?: error("Derived-index rebuild requires ReadOnlyRecovery")
            require(recovery.canRebuildDerivedIndex()) {
                "Recovery ${recovery.code} is not a rebuildable SQLite failure"
            }
            val location =
                checkNotNull(directorySettingsRepository.currentRootLocation()) {
                    "Derived-index rebuild requires a selected workspace"
                }

            val rebuild =
                candidatePreparer.rebuildProjectionForRecovery(
                    location = location,
                    batchSize = RECOVERY_REBUILD_BATCH_SIZE,
                )

            // Reopen from the repaired projection and promote only a fully Ready candidate. The
            // previous bootstrap/recovery owner remains non-writable until this atomic install.
            val prepared = candidatePreparer.prepare(location)
            promoteCandidate(candidate = prepared, location = location)
            DerivedIndexRebuildSummary(
                memosIndexed = rebuild.memosIndexed,
                fileCount = rebuild.fileCount,
                attachmentCount = rebuild.attachmentCount,
                corruptLomoIsolated = rebuild.corruptLomoIsolated,
                highWaterRevision = rebuild.highWaterRevision,
            )
        }
    }

    override suspend fun activateWorkspace(location: StorageLocation) {
        check(!closed.get()) { "Managed engine session is closed" }
        check(ownsNativeEngine.ownsNativeEngine) { "Projection-only process must not open a workspace engine" }
        engineStartRequested.set(true)
        require(location.raw.isNotBlank()) { "Workspace location must be non-blank" }
        activationMutex.withLock {
            check(!closed.get()) { "Managed engine session is closed" }
            val previousVerified = WorkspaceMountTransitions.verifiedOrNull(_mount.value)
            WorkspaceMountTransitions.revalidating(previousVerified)?.let(::publishMount)
            try {
                val prepared = candidatePreparer.prepare(location)
                promoteCandidate(candidate = prepared, location = location)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) {
                    WorkspaceMountTransitions.restoreVerified(_mount.value, previousVerified)
                        ?.let(::publishMount)
                    throw failure
                }
                WorkspaceMountTransitions.restoreVerified(_mount.value, previousVerified)
                    ?.let(::publishMount)
                throw failure
            }
        }
    }

    /**
     * Success path opens the persisted workspace engine only. Bootstrap is the no-workspace host,
     * never a paid precondition of a configured vault.
     */
    private suspend fun startOwnedEngine() {
        activationMutex.withLock {
            if (closed.get() || recoveryAuthority.get() != null || _workspaceAuthority.value != null) {
                return
            }
            val existing =
                try {
                    directorySettingsRepository.recoverRootLocation()
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    holdRecoveryAuthority(recoveryFromThrowable(failure))
                    return
                }
            if (existing != null && existing.raw.isNotBlank()) {
                try {
                    restoreWorkspaceIfCurrentLocked(existing)
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    holdRecoveryAuthority(recoveryFromThrowable(failure))
                }
            }
            if (!closed.get() && recoveryAuthority.get() == null && activeAdapter == null) {
                installBootstrapLocked()
            }
        }
    }

    private suspend fun restoreWorkspaceIfCurrentLocked(location: StorageLocation) {
        check(!closed.get()) { "Managed engine session is closed" }
        val committed = directorySettingsRepository.currentRootLocation()
        val pending = directorySettingsRepository.pendingRootTransition()
        if (committed != location || pending != null) return
        val prepared = candidatePreparer.prepare(location)
        promoteCandidate(candidate = prepared, location = location)
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
                    installAdapterLocked(candidate.adapter, capabilityToken = candidate.capabilityToken)
                    publishMount(
                        WorkspaceMount(
                            readiness = candidate.adapter.readiness.value,
                            location = null,
                            authority = null,
                            freshness = ProjectionFreshness.Unavailable,
                        ),
                    )
                    startAdapterMirrorLocked(candidate.adapter)
                }
            }
            .onFailure { error -> holdRecoveryAuthority(recoveryFromThrowable(error)) }
    }

    override suspend fun clearWorkspace() {
        if (closed.get()) return
        check(ownsNativeEngine.ownsNativeEngine) { "Projection-only process must not open a workspace engine" }
        engineStartRequested.set(true)
        activationMutex.withLock {
            if (closed.get()) return
            // Reselect / failed first selection: open awaiting engine under the activation mutex.
            promoteCandidate(candidate = candidatePreparer.openBootstrap(), location = null)
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
                rejectPromotion(candidate, WorkspaceActivationException(promotion.recovery))
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
                rejectPromotion(candidate, failure)
            }
        }
    }

    private fun rejectPromotion(
        candidate: ManagedWorkspaceCandidate,
        failure: Throwable,
    ): Nothing {
        releaseCandidate(candidate.adapter, candidate.capabilityToken, failure, capabilityRegistry)
        throw failure
    }

    private fun retirePreviousAndCommit(
        candidate: ManagedWorkspaceCandidate,
        location: StorageLocation?,
    ): AdapterPromotion =
        adapterLease.write {
            candidate.adapter.withReadinessAtCommit { candidateReadiness ->
                if (candidate.workspaceId != null && candidateReadiness !is EngineReadiness.Ready) {
                    return@withReadinessAtCommit AdapterPromotion.CandidateRejected(
                        candidateReadiness as? EngineReadiness.ReadOnlyRecovery
                            ?: workspaceOpenNotReady(candidateReadiness),
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
                installAdapterLocked(candidate.adapter, capabilityToken = candidate.capabilityToken)
                publishMount(
                    WorkspaceMount(
                        readiness = candidateReadiness,
                        location = location,
                        authority = authority,
                        freshness =
                            if (authority == null) {
                                ProjectionFreshness.Unavailable
                            } else {
                                ProjectionFreshness.Verified(checkNotNull(candidate.projectionRevision))
                            },
                    ),
                )
                startAdapterMirrorLocked(candidate.adapter)
                AdapterPromotion.Committed(token, authority)
            }
        }

    private fun installAdapterLocked(
        adapter: RustEngineAdapter,
        capabilityToken: String?,
    ) {
        mirrorJob?.cancel()
        activeAdapter = adapter
        activeCapabilityToken = capabilityToken
    }

    private fun startAdapterMirrorLocked(adapter: RustEngineAdapter) {
        mirrorJob?.cancel()
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
                                        location = _activeWorkspaceLocation.value,
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

    protected override fun <T> withActiveWorkspaceAdapter(block: (RustEngineAdapter) -> T): T {
        check(!closed.get()) { "Managed engine session is closed" }
        return adapterLease.read {
            check(_readiness.value is EngineReadiness.Ready) {
                "Workspace engine is not Ready"
            }
            val adapter = activeAdapter ?: error("Managed engine session has no active adapter")
            block(adapter)
        }
    }

    /** Installation-level capabilities remain available on the bootstrap Awaiting engine. */
    protected override fun <T> withActiveEngineAdapter(block: (RustEngineAdapter) -> T): T {
        check(!closed.get()) { "Managed engine session is closed" }
        return adapterLease.read {
            val adapter = activeAdapter ?: error("Managed engine session has no active adapter")
            block(adapter)
        }
    }

    private sealed interface AdapterPromotion {
        data class Committed(
            val previousToken: String?,
            val authority: WorkspaceAuthority?,
        ) : AdapterPromotion

        data class CandidateRejected(
            val recovery: EngineReadiness.ReadOnlyRecovery,
        ) : AdapterPromotion

        data class RetirementFailed(
            val previousToken: String?,
            val failure: Throwable,
        ) : AdapterPromotion
    }

    /** Outcome of releasing the outgoing workspace owner during process close. */
    private data class AdapterRetirement(
        val previousToken: String?,
        val failure: Throwable?,
    )

    companion object {
        private const val RECOVERY_REBUILD_BATCH_SIZE: UInt = 64u
    }
}
