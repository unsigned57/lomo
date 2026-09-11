package com.lomo.data.engine

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.DerivedIndexRebuildSummary
import com.lomo.domain.model.RecoveryDiagnosticReport
import com.lomo.domain.model.RecoveryWorkspaceKind
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.canRebuildDerivedIndex
import com.lomo.domain.model.toDiagnosticReport
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Cold start opens with no workspace (`AwaitingWorkspaceSelection`); a bootstrap engine that cannot
 * be acquired leaves the session in structured `ReadOnlyRecovery` with no adapter rather than
 * failing graph construction. When a Direct/SAF root is selected (or restored once from persisted
 * settings), [activateWorkspace] runs Prepared → RetiringPrevious → Committed: it opens a candidate
 * engine, promotes it once it reaches [EngineReadiness.Ready], and releases the previous owner.
 * SAF projection reconciliation is a separate generation-boundary operation: an empty candidate
 * projection is published as [ProjectionFreshness.Building] and becomes writable only after the
 * Rust-owned atomic rebuild commits. Soft Recovery and hard open failure leave the previous engine
 * authoritative.
 * Session-owned recovery authority freezes readiness so a bootstrap Awaiting engine cannot
 * resnapshot Recovery away after cold-restore failure.
 *
 * Workspace switch orchestration is owned by domain
 * [com.lomo.domain.usecase.SwitchRootStorageUseCase]; this session exclusively owns candidate open,
 * projection rebuild, and promotion and does not race-observe selection changes after the initial
 * cold restore.
 */
internal class ManagedEngineSession(
    private val filesDir: File,
    private val capabilityRegistry: CapabilityRegistry,
    private val openAdapter: (NativeEngineOpenRequest) -> RustEngineAdapter,
    private val directorySettingsRepository: DirectorySettingsRepository,
    private val appScope: CoroutineScope,
    private val isContentUri: (String) -> Boolean,
) : ManagedEngineCapabilities(),
    EngineReadinessRepository,
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val activationMutex = Mutex()
    private val adapterLease = ReentrantReadWriteLock()
    private val _readiness = MutableStateFlow<EngineReadiness>(EngineReadiness.AwaitingWorkspaceSelection)
    private val _activeWorkspaceLocation = MutableStateFlow<StorageLocation?>(null)
    private val _workspaceAuthority = MutableStateFlow<WorkspaceAuthority?>(null)
    private val _projectionFreshness =
        MutableStateFlow<ProjectionFreshness>(ProjectionFreshness.Unavailable)
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
    // behavior-contract: stateful-var-ok: this is process-owned resource/lease/job handle, not a query cache
    private var projectionRefreshJob: kotlinx.coroutines.Job? = null

    /**
     * When non-null, session readiness is held by recovery authority and adapter mirrors must not
     * overwrite it with bootstrap Awaiting/Opening. Cleared only by a successful Ready install.
     */
    private val recoveryAuthority = AtomicReference<EngineReadiness.ReadOnlyRecovery?>(null)
    private val holdRecoveryAuthority: (EngineReadiness.ReadOnlyRecovery) -> Unit = { recovery ->
        recoveryAuthority.set(recovery)
        _readiness.value = recovery
    }

    init {
        // Bootstrap without a workspace is a resource transaction, not a precondition: when the
        // native library or control root is unusable the graph must still build so Recovery UI
        // exists. A failed bootstrap installs no adapter at all rather than a placeholder.
        runCatching { candidatePreparer.openBootstrap() }
            .onSuccess { candidate ->
                adapterLease.write {
                    installAdapterLocked(candidate.adapter, capabilityToken = candidate.capabilityToken)
                }
            }
            .onFailure { error -> holdRecoveryAuthority(recoveryFromThrowable(error)) }
        appScope.launch {
            // Cold-start restore only. SwitchRootStorageUseCase activates subsequent selections.
            runCatching { directorySettingsRepository.recoverRootLocation() }
                .onSuccess { existing ->
                    if (existing != null && existing.raw.isNotBlank()) {
                        runCatching { restoreWorkspaceIfCurrent(existing) }
                            .onFailure { error -> holdRecoveryAuthority(recoveryFromThrowable(error)) }
                    }
                }
                .onFailure { error -> holdRecoveryAuthority(recoveryFromThrowable(error)) }
        }
    }

    override val readiness: StateFlow<EngineReadiness> = _readiness.asStateFlow()
    override val activeWorkspaceLocation: StateFlow<StorageLocation?> =
        _activeWorkspaceLocation.asStateFlow()
    override val workspaceAuthority: StateFlow<WorkspaceAuthority?> =
        _workspaceAuthority.asStateFlow()
    override val projectionFreshness: StateFlow<ProjectionFreshness> =
        _projectionFreshness.asStateFlow()

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
            val prepared = candidatePreparer.prepare(location, allowBackgroundRefresh = false)
            val authority = promoteCandidate(candidate = prepared, location = location)
            prepared.refreshCandidate?.let { refresh ->
                startProjectionRefresh(refresh, checkNotNull(authority))
            }
            DerivedIndexRebuildSummary(
                memosIndexed = rebuild.memosIndexed,
                fileCount = rebuild.fileCount,
                attachmentCount = rebuild.attachmentCount,
                corruptLomoIsolated = rebuild.corruptLomoIsolated,
                highWaterRevision = rebuild.highWaterRevision,
            )
        }
    }

    override suspend fun retryProjectionBuild() {
        check(!closed.get()) { "Managed engine session is closed" }
        activationMutex.withLock {
            val authority =
                checkNotNull(_workspaceAuthority.value) {
                    "Projection retry requires an active workspace authority"
                }
            check(_projectionFreshness.value is ProjectionFreshness.Failed) {
                "Projection retry requires a failed first projection build"
            }
            val location =
                checkNotNull(_activeWorkspaceLocation.value) {
                    "Projection retry requires an active workspace location"
                }
            check(isContentUri(location.raw)) {
                "Projection retry is only valid for an SAF workspace"
            }
            val adapter =
                adapterLease.read {
                    check(_readiness.value is EngineReadiness.Ready) {
                        "Projection retry requires a Ready engine"
                    }
                    checkNotNull(activeAdapter) {
                        "Projection retry requires an active workspace adapter"
                    }
                }
            startProjectionRefresh(
                candidate =
                    ProjectionRefreshCandidate(
                        adapter = adapter,
                        projectionRevision = authority.projectionRevision,
                    ),
                launchedAuthority = authority,
            )
        }
    }

    override suspend fun activateWorkspace(location: StorageLocation) {
        check(!closed.get()) { "Managed engine session is closed" }
        require(location.raw.isNotBlank()) { "Workspace location must be non-blank" }
        activationMutex.withLock {
            check(!closed.get()) { "Managed engine session is closed" }
            val prepared = candidatePreparer.prepare(location, allowBackgroundRefresh = true)
            val authority = promoteCandidate(candidate = prepared, location = location)
            prepared.refreshCandidate?.let { refresh ->
                startProjectionRefresh(refresh, checkNotNull(authority))
            }
        }
    }

    private suspend fun restoreWorkspaceIfCurrent(location: StorageLocation) {
        activationMutex.withLock {
            check(!closed.get()) { "Managed engine session is closed" }
            val committed = directorySettingsRepository.currentRootLocation()
            val pending = directorySettingsRepository.pendingRootTransition()
            if (committed != location || pending != null) return@withLock
            val prepared = candidatePreparer.prepare(location, allowBackgroundRefresh = true)
            val authority = promoteCandidate(candidate = prepared, location = location)
            prepared.refreshCandidate?.let { refresh ->
                startProjectionRefresh(refresh, checkNotNull(authority))
            }
        }
    }

    override suspend fun clearWorkspace() {
        if (closed.get()) return
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
                _activeWorkspaceLocation.value = null
                AdapterRetirement(
                    previousToken = token,
                    failure = adapter?.let { runCatching(it::close).exceptionOrNull() },
                )
            }
        // A failing engine close must not skip capability revoke or the terminal readiness value.
        retirement.previousToken?.let(capabilityRegistry::revoke)
        _readiness.value = EngineReadiness.ShuttingDown
        retirement.failure?.let { throw it }
    }

    override fun rebuildActiveStore(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult =
        withActiveWorkspaceAdapter { adapter ->
            val location =
                checkNotNull(_activeWorkspaceLocation.value) {
                    "Ready workspace has no active storage location"
                }
            if (isContentUri(location.raw)) {
                adapter.rebuildSafProjectionFromWorkspaceScan()
            } else {
                adapter.startRebuild(batchSize)
            }
        }

    protected override fun applyActiveMemoCommand(
        command: com.lomo.nativebridge.StoreMemoCommand,
        onPublication: (com.lomo.nativebridge.StoreMemoCommit) -> Unit,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.applyMemoCommand(command, onPublication) }

    override fun commitSafPermanentDeleteMany(
        request: com.lomo.nativebridge.StoreMemoBatchDelete,
    ): com.lomo.nativebridge.StoreMemoBatchCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.commitSafPermanentDeleteMany(request) }

    override fun beginSafMemoCreate(
        begin: com.lomo.nativebridge.StoreSafMemoCreateBegin,
    ): com.lomo.nativebridge.StoreSafMemoCreateBeginResult =
        withActiveWorkspaceAdapter { adapter -> adapter.beginSafMemoCreate(begin) }

    override fun rollbackSafMemoCreate(
        operationId: String,
        memoId: String,
    ): com.lomo.nativebridge.StoreSafMemoRollbackResult =
        withActiveWorkspaceAdapter { adapter -> adapter.rollbackSafMemoCreate(operationId, memoId) }

    override fun applyActivePermanentDeleteMany(
        request: com.lomo.nativebridge.StoreMemoBatchDelete,
    ): com.lomo.nativebridge.StoreMemoBatchCommit =
        withActiveWorkspaceAdapter { adapter ->
            val location = checkNotNull(_activeWorkspaceLocation.value) {
                "Ready workspace has no active storage location"
            }
            if (isContentUri(location.raw)) {
                applySafPermanentDeleteManyOnSafAdapter(adapter, request)
            } else {
                adapter.permanentDeleteMany(request)
            }
        }

    /**
     * RetiringPrevious → Committed: the outgoing owner is released first and only a complete
     * retirement publishes [candidate] as the committed authority.
     */
    private fun promoteCandidate(
        candidate: ManagedWorkspaceCandidate,
        location: StorageLocation?,
    ): WorkspaceAuthority? {
        val promotion =
            retirePreviousAndCommit(
                candidate,
                location,
            )
        return when (promotion) {
            is AdapterPromotion.Committed -> {
                promotion.previousToken
                    ?.takeIf { it != candidate.capabilityToken }
                    ?.let(capabilityRegistry::revoke)
                promotion.authority
            }
            is AdapterPromotion.CandidateRejected -> {
                val failure = WorkspaceActivationException(promotion.recovery)
                releaseCandidate(candidate.adapter, candidate.capabilityToken, failure, capabilityRegistry)
                throw failure
            }
            is AdapterPromotion.RetirementFailed -> {
                val failure = promotion.failure
                // The previous owner could not be retired, so two writers could otherwise hold the
                // same workspace. Publish neither and freeze the session in structured recovery.
                promotion.previousToken
                    ?.takeIf { it != candidate.capabilityToken }
                    ?.let(capabilityRegistry::revoke)
                releaseCandidate(candidate.adapter, candidate.capabilityToken, failure, capabilityRegistry)
                holdRecoveryAuthority(
                    EngineReadiness.ReadOnlyRecovery(
                        category = EngineFailureCategory.INTERNAL,
                        code = "workspace_retire_failed",
                        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                        diagnostic = failure.message ?: "Previous workspace engine could not be retired",
                    ),
                )
                throw failure
            }
        }
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
                _activeWorkspaceLocation.value = location
                // A new generation is published only here, once the candidate is the sole owner.
                val authority =
                    candidate.workspaceId?.let { id ->
                        WorkspaceAuthority(
                            workspaceId = id,
                            generation = activationGeneration.incrementAndGet(),
                            projectionRevision = checkNotNull(candidate.projectionRevision),
                        )
                    }
                _workspaceAuthority.value = authority
                _projectionFreshness.value =
                    when {
                        candidate.workspaceId == null -> ProjectionFreshness.Unavailable
                        candidate.refreshProjection && candidate.projectionRevision == 0uL ->
                            ProjectionFreshness.Building(0uL)
                        candidate.refreshProjection ->
                            ProjectionFreshness.Refreshing(checkNotNull(candidate.projectionRevision))
                        else ->
                            ProjectionFreshness.Verified(checkNotNull(candidate.projectionRevision))
                    }
                AdapterPromotion.Committed(token, authority)
            }
        }

    private fun startProjectionRefresh(
        candidate: ProjectionRefreshCandidate,
        launchedAuthority: WorkspaceAuthority,
    ) {
        projectionRefreshJob?.cancel()
        adapterLease.write {
            check(
                activeAdapter === candidate.adapter &&
                    _workspaceAuthority.value == launchedAuthority,
            ) {
                "Projection refresh authority is no longer active"
            }
            _projectionFreshness.value =
                if (candidate.projectionRevision == 0uL) {
                    ProjectionFreshness.Building(baseRevision = 0uL)
                } else {
                    ProjectionFreshness.Refreshing(
                        lastVerifiedRevision = candidate.projectionRevision,
                    )
                }
        }
        projectionRefreshJob =
            appScope.launch(Dispatchers.IO) {
                val result =
                    runCatching {
                        adapterLease.read {
                            check(
                                activeAdapter === candidate.adapter &&
                                    _workspaceAuthority.value == launchedAuthority,
                            ) {
                                "Projection refresh adapter is no longer active"
                            }
                        }
                        // The native port leases each FFI call. Do not hold the session lease across
                        // Android provider I/O: a blocked refresh must not prevent the next
                        // workspace generation from retiring this adapter.
                        candidate.adapter.rebuildSafProjectionFromWorkspaceScan()
                    }
                adapterLease.write {
                    val currentAuthority = _workspaceAuthority.value
                    if (!closed.get() &&
                        activeAdapter === candidate.adapter &&
                        currentAuthority == launchedAuthority
                    ) {
                        result.fold(
                            onSuccess = { rebuild ->
                                _workspaceAuthority.value =
                                    currentAuthority.copy(projectionRevision = rebuild.highWaterRevision)
                                _projectionFreshness.value =
                                    ProjectionFreshness.Verified(rebuild.highWaterRevision)
                            },
                            onFailure = { error ->
                                val reasonCode =
                                    when (error) {
                                        is ProjectionRebuildException -> error.failureCode
                                        is ProjectionScanDeadlineExceededException ->
                                            "projection_scan_deadline_exceeded"
                                        else -> "projection_refresh_failed"
                                    }
                                _projectionFreshness.value =
                                    if (candidate.projectionRevision == 0uL) {
                                        ProjectionFreshness.Failed(
                                            baseRevision = 0uL,
                                            reasonCode = reasonCode,
                                        )
                                    } else {
                                        ProjectionFreshness.Stale(
                                            lastVerifiedRevision = candidate.projectionRevision,
                                            reasonCode = reasonCode,
                                        )
                                    }
                            },
                        )
                    }
                }
            }
    }

    private fun installAdapterLocked(
        adapter: RustEngineAdapter,
        capabilityToken: String?,
    ) {
        mirrorJob?.cancel()
        activeAdapter = adapter
        activeCapabilityToken = capabilityToken
        // Do not publish adapter Awaiting over an active recovery authority (cold-restore hold).
        if (recoveryAuthority.get() == null) {
            _readiness.value = adapter.readiness.value
        }
        mirrorJob =
            appScope.launch {
                adapter.readiness.collect { value ->
                    adapterLease.read {
                        if (!closed.get() && activeAdapter === adapter && recoveryAuthority.get() == null) {
                            _readiness.value = value
                            if (value !is EngineReadiness.Ready) {
                                // Authority is a capability for the committed Ready projection;
                                // invalidate it immediately when the active boundary becomes
                                // unknown so readers cannot keep using a retired generation.
                                _workspaceAuthority.value = null
                            }
                        }
                    }
                }
            }
    }

    /** Drops the outgoing owner before it is closed, so no route can reach a retiring adapter. */
    private fun detachActiveAdapterLocked() {
        projectionRefreshJob?.cancel()
        projectionRefreshJob = null
        mirrorJob?.cancel()
        mirrorJob = null
        activeAdapter = null
        activeCapabilityToken = null
        _workspaceAuthority.value = null
        _projectionFreshness.value = ProjectionFreshness.Unavailable
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
