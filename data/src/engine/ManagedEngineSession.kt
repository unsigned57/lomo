package com.lomo.data.engine

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.DerivedIndexRebuildSummary
import com.lomo.domain.model.Recurrence
import com.lomo.domain.model.RecoveryDiagnosticReport
import com.lomo.domain.model.RecoveryWorkspaceKind
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.ReminderReference
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.canRebuildDerivedIndex
import com.lomo.domain.model.toDiagnosticReport
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.repository.MarkdownReminderRepository
import com.lomo.domain.model.MarkdownWorkspaceCommandException
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
import java.util.UUID
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
 * engine, promotes it only after it reaches [EngineReadiness.Ready] and the previous owner has been
 * released. Soft Recovery and hard open failure leave the previous engine authoritative.
 * Session-owned recovery authority freezes readiness so a bootstrap Awaiting engine cannot
 * resnapshot Recovery away after cold-restore failure.
 *
 * Workspace switch activation is owned by domain [com.lomo.domain.usecase.SwitchRootStorageUseCase];
 * this session does not race-observe selection changes after the initial cold restore.
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
    MarkdownWorkspaceRepository,
    MarkdownReminderRepository,
    WorkspaceMarkdownOwner,
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val activationMutex = Mutex()
    private val adapterLease = ReentrantReadWriteLock()
    private val _readiness = MutableStateFlow<EngineReadiness>(EngineReadiness.AwaitingWorkspaceSelection)
    private val _activeWorkspaceLocation = MutableStateFlow<StorageLocation?>(null)
    private val _workspaceAuthority = MutableStateFlow<WorkspaceAuthority?>(null)
    private val activationGeneration = AtomicLong(0)
    private var activeAdapter: RustEngineAdapter? = null
    private var activeCapabilityToken: String? = null
    private var mirrorJob: kotlinx.coroutines.Job? = null

    /**
     * When non-null, session readiness is held by recovery authority and adapter mirrors must not
     * overwrite it with bootstrap Awaiting/Opening. Cleared only by a successful Ready install.
     */
    private val recoveryAuthority = AtomicReference<EngineReadiness.ReadOnlyRecovery?>(null)

    init {
        // Bootstrap without a workspace is a resource transaction, not a precondition: when the
        // native library or control root is unusable the graph must still build so Recovery UI
        // exists. A failed bootstrap installs no adapter at all rather than a placeholder.
        runCatching { openAdapter(NativeEngineOpenRequest.forAppFilesDir(filesDir)) }
            .onSuccess { adapter -> adapterLease.write { installAdapterLocked(adapter, capabilityToken = null) } }
            .onFailure { error -> holdRecoveryAuthority(recoveryFromThrowable(error)) }
        appScope.launch {
            // Cold-start restore only. SwitchRootStorageUseCase activates subsequent selections.
            val existing = directorySettingsRepository.currentRootLocation()
            if (existing != null && existing.raw.isNotBlank()) {
                runCatching { activateWorkspace(existing) }
                    .onFailure { error ->
                        // Hard/soft open failure: freeze Recovery so bootstrap cannot resnapshot it away.
                        holdRecoveryAuthority(
                            recoveryFromThrowable(error),
                        )
                    }
            }
        }
    }

    override val readiness: StateFlow<EngineReadiness> = _readiness.asStateFlow()
    override val activeWorkspaceLocation: StateFlow<StorageLocation?> =
        _activeWorkspaceLocation.asStateFlow()
    override val workspaceAuthority: StateFlow<WorkspaceAuthority?> =
        _workspaceAuthority.asStateFlow()

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

            val repairSelection = selectionFor(location)
            val repairAdapter = openWorkspaceAdapter(repairSelection)
            val rebuild = rebuildAndReleaseRepairAdapter(repairAdapter, repairSelection.capabilityToken)

            // Reopen from the repaired projection and promote only a fully Ready candidate. The
            // previous bootstrap/recovery owner remains non-writable until this atomic install.
            val readySelection = selectionFor(location)
            promoteCandidate(
                candidate = prepareCandidate(readySelection),
                candidateToken = readySelection.capabilityToken,
                location = location,
                workspaceId = readySelection.stableWorkspaceId,
            )
            DerivedIndexRebuildSummary(
                memosIndexed = rebuild.memosIndexed,
                fileCount = rebuild.fileCount,
                attachmentCount = rebuild.attachmentCount,
                corruptLomoIsolated = rebuild.corruptLomoIsolated,
                highWaterRevision = rebuild.highWaterRevision,
            )
        }
    }

    override fun renderMarkdown(content: String) =
        renderMarkdown(content = content, schemaVersion = com.lomo.domain.model.markdown.MarkdownRenderDocument.SCHEMA_VERSION)

    override fun remindersForMemo(memoIdentity: String): List<ReminderMarker> =
        withActiveWorkspaceAdapter { adapter ->
            adapter.findMemoSnapshot(memoIdentity).reminders.map(WorkspaceReminderReferenceSnapshot::toDomainMarker)
        }

    override suspend fun rewriteReminder(
        reference: ReminderReference,
        replacement: String,
    ): String =
        withActiveWorkspaceAdapter { adapter ->
            val before = adapter.findMemoSnapshot(reference.memoIdentity)
            val reminder =
                before.reminders.singleOrNull { candidate -> candidate.matches(reference) }
                    ?: throw MarkdownWorkspaceCommandException(
                        code = "stale_snapshot",
                        message = "Reminder reference is not present in the current memo revision",
                    )
            val jobId =
                adapter.startWorkspaceDocumentCommand(
                    path = before.path,
                    expectedFingerprint = reference.revision,
                    command = WorkspaceNativeCommandSpec.RewriteReminder(reminder, replacement),
                )
            adapter.driveToCompletion(jobId)
            adapter.readWorkspaceDocumentCommandResult(jobId)
            adapter.findMemoSnapshot(reference.memoIdentity).content
        }

    override fun scanWorkspace(rootPath: String?): List<WorkspaceMemoSummarySnapshot> =
        withActiveWorkspaceAdapter { adapter -> adapter.scanAllMemoSnapshots(rootPath) }

    override fun replaceMemo(
        rootPath: String?,
        filename: String,
        identity: String,
        content: String,
    ): Boolean =
        withActiveWorkspaceAdapter { adapter ->
            adapter.executeMemoCommand(rootPath, filename, identity, WorkspaceNativeCommandSpec.Replace(identity, content))
        }

    override fun removeMemo(
        rootPath: String?,
        filename: String,
        identity: String,
    ): Boolean =
        withActiveWorkspaceAdapter { adapter ->
            adapter.executeMemoCommand(rootPath, filename, identity, WorkspaceNativeCommandSpec.Remove(identity))
        }

    override suspend fun toggleTask(
        memoIdentity: String,
        actionSpan: com.lomo.domain.model.markdown.MarkdownSourceSpan,
    ): String =
        withActiveWorkspaceAdapter { adapter ->
            val before = adapter.findMemoSnapshot(memoIdentity)
            val relativeStart = actionSpan.startByte
            val relativeEnd = actionSpan.endByte
            val contentLength = before.content.encodeToByteArray().size.toULong()
            if (relativeStart >= relativeEnd || relativeEnd > contentLength) {
                throw MarkdownWorkspaceCommandException(
                    code = "task_action_span_out_of_bounds",
                    message = "Task action span is outside the rendered memo body",
                )
            }
            val absoluteStart = before.bodyStart.checkedAdd(relativeStart)
            val absoluteEnd = before.bodyStart.checkedAdd(relativeEnd)
            if (absoluteEnd > before.bodyEnd) {
                throw MarkdownWorkspaceCommandException(
                    code = "task_action_span_out_of_bounds",
                    message = "Task action span exceeds the scanned memo body",
                )
            }
            val jobId =
                adapter.startWorkspaceDocumentCommand(
                    path = before.path,
                    expectedFingerprint = before.fingerprint,
                    command =
                        WorkspaceNativeCommandSpec.ToggleTask(
                            sourceStart = absoluteStart,
                            sourceEnd = absoluteEnd,
                        ),
                )
            adapter.driveToCompletion(jobId)
            adapter.readWorkspaceDocumentCommandResult(jobId)
            adapter.findMemoSnapshot(memoIdentity).content
        }

    override suspend fun activateWorkspace(location: StorageLocation) {
        check(!closed.get()) { "Managed engine session is closed" }
        require(location.raw.isNotBlank()) { "Workspace location must be non-blank" }
        activationMutex.withLock {
            check(!closed.get()) { "Managed engine session is closed" }
            val selection = selectionFor(location)
            // Prepared → RetiringPrevious → Committed. Every phase either advances or releases
            // what it took, so no caller observes a half-switched workspace authority.
            promoteCandidate(
                candidate = prepareCandidate(selection),
                candidateToken = selection.capabilityToken,
                location = location,
                workspaceId = selection.stableWorkspaceId,
            )
        }
    }

    override suspend fun clearWorkspace() {
        if (closed.get()) return
        activationMutex.withLock {
            if (closed.get()) return
            // Reselect / failed first selection: open awaiting engine under the activation mutex.
            val bootstrap = openAdapter(NativeEngineOpenRequest.forAppFilesDir(filesDir))
            promoteCandidate(
                candidate = bootstrap,
                candidateToken = null,
                location = null,
                workspaceId = null,
            )
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

    /**
     * Prepared phase: opens the candidate and requires Ready before any authority changes.
     *
     * Hard open failure and soft non-Ready both leave the previous engine authoritative and release
     * the capability this selection registered.
     */
    private suspend fun prepareCandidate(selection: PreparedSelection): RustEngineAdapter {
        val candidate = openWorkspaceAdapter(selection)
        val candidateReadiness = candidate.readiness.value
        if (candidateReadiness is EngineReadiness.Ready) {
            val preparation =
                runCatching {
                    if (selection.workspace is NativeWorkspaceSelection.Saf) {
                        withContext(Dispatchers.IO) {
                            val projection =
                                candidate
                                    .scanAllMemoSnapshots(rootPath = null)
                                    .map(WorkspaceMemoSummarySnapshot::toSafProjectionSnapshot)
                            candidate.rebuildSafStoreProjection(projection)
                        }
                    }
                    candidate
                }
            preparation.exceptionOrNull()?.let { error ->
                releaseCandidate(candidate, selection.capabilityToken, error, capabilityRegistry)
            }
            return preparation.getOrThrow()
        }
        // Soft open (Recovery / Opening / Awaiting): never promote; release the candidate.
        val activation =
            WorkspaceActivationException(
                candidateReadiness as? EngineReadiness.ReadOnlyRecovery
                    ?: EngineReadiness.ReadOnlyRecovery(
                        category = EngineReadiness.FailureCategory.INTERNAL,
                        code = "workspace_open_not_ready",
                        retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
                        diagnostic =
                            "Workspace open did not reach Ready " +
                                "(${candidateReadiness::class.simpleName})",
                    ),
            )
        releaseCandidate(candidate, selection.capabilityToken, activation, capabilityRegistry)
        throw activation
    }

    override fun rebuildActiveStore(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult =
        withActiveWorkspaceAdapter { adapter ->
            val location =
                checkNotNull(_activeWorkspaceLocation.value) {
                    "Ready workspace has no active storage location"
                }
            if (isContentUri(location.raw)) {
                val projection =
                    adapter
                        .scanAllMemoSnapshots(rootPath = null)
                        .map(WorkspaceMemoSummarySnapshot::toSafProjectionSnapshot)
                adapter.rebuildSafStoreProjection(projection)
            } else {
                adapter.startRebuild(batchSize)
            }
        }

    private fun openWorkspaceAdapter(selection: PreparedSelection): RustEngineAdapter =
        runCatching {
            openAdapter(
                NativeEngineOpenRequest
                    .forAppFilesDir(filesDir)
                    .copy(workspace = selection.workspace),
            )
        }.onFailure { selection.capabilityToken?.let(capabilityRegistry::revoke) }
            .getOrThrow()

    private fun rebuildAndReleaseRepairAdapter(
        adapter: RustEngineAdapter,
        capabilityToken: String?,
    ): com.lomo.nativebridge.StoreRebuildResult {
        val rebuildResult = runCatching { adapter.startRebuild(RECOVERY_REBUILD_BATCH_SIZE) }
        val closeFailure = runCatching(adapter::close).exceptionOrNull()
        capabilityToken?.let(capabilityRegistry::revoke)
        return rebuildResult.fold(
            onSuccess = { rebuild ->
                if (closeFailure != null) throw closeFailure
                rebuild
            },
            onFailure = { rebuildFailure ->
                closeFailure?.let(rebuildFailure::addSuppressed)
                throw rebuildFailure
            },
        )
    }

    /**
     * RetiringPrevious → Committed: the outgoing owner is released first and only a complete
     * retirement publishes [candidate] as the committed authority.
     */
    private fun promoteCandidate(
        candidate: RustEngineAdapter,
        candidateToken: String?,
        location: StorageLocation?,
        workspaceId: String?,
    ) {
        val retirement = retirePreviousAndCommit(candidate, candidateToken, location, workspaceId)
        retirement.previousToken
            ?.takeIf { it != candidateToken }
            ?.let(capabilityRegistry::revoke)
        val failure = retirement.failure ?: return
        // The previous owner could not be retired, so two writers could otherwise hold the same
        // workspace. Publish neither and freeze the session in structured recovery instead.
        releaseCandidate(candidate, candidateToken, failure, capabilityRegistry)
        holdRecoveryAuthority(
            EngineReadiness.ReadOnlyRecovery(
                category = EngineReadiness.FailureCategory.INTERNAL,
                code = "workspace_retire_failed",
                retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
                diagnostic = failure.message ?: "Previous workspace engine could not be retired",
            ),
        )
        throw failure
    }

    private fun retirePreviousAndCommit(
        candidate: RustEngineAdapter,
        candidateToken: String?,
        location: StorageLocation?,
        workspaceId: String?,
    ): AdapterRetirement =
        adapterLease.write {
            val token = activeCapabilityToken
            val previous = activeAdapter
            detachActiveAdapterLocked()
            // Exclusive lease waits for every in-flight workspace call before close.
            val failure = previous?.let { runCatching(it::close).exceptionOrNull() }
            if (failure == null) {
                // Ready install clears any prior recovery authority and becomes sole publisher.
                recoveryAuthority.set(null)
                installAdapterLocked(candidate, capabilityToken = candidateToken)
                _activeWorkspaceLocation.value = location
                // A new generation is published only here, once the candidate is the sole owner.
                _workspaceAuthority.value =
                    workspaceId?.let { id ->
                        WorkspaceAuthority(
                            workspaceId = id,
                            generation = activationGeneration.incrementAndGet(),
                        )
                    }
            }
            AdapterRetirement(previousToken = token, failure = failure)
        }

    private fun holdRecoveryAuthority(recovery: EngineReadiness.ReadOnlyRecovery) {
        recoveryAuthority.set(recovery)
        _readiness.value = recovery
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
        _workspaceAuthority.value = null
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

    private fun selectionFor(location: StorageLocation): PreparedSelection {
        val raw = location.raw.trim()
        return if (isContentUri(raw)) {
            val token = "cap-${UUID.randomUUID()}"
            val grant = capabilityRegistry.register(token = token, treeUri = raw)
            PreparedSelection(
                workspace = NativeWorkspaceSelection.Saf(grant),
                capabilityToken = grant.capabilityToken,
                // Stable tree identity, never the rotating process capability.
                stableWorkspaceId = grant.stableWorkspaceId.value,
            )
        } else {
            val rootPath = File(raw)
            PreparedSelection(
                workspace = NativeWorkspaceSelection.Direct(rootPath = rootPath),
                capabilityToken = null,
                stableWorkspaceId = "direct:${rootPath.absolutePath}",
            )
        }
    }

    private data class PreparedSelection(
        val workspace: NativeWorkspaceSelection,
        val capabilityToken: String?,
        val stableWorkspaceId: String,
    )

    /** Outcome of releasing the outgoing workspace owner during a switch or process close. */
    private data class AdapterRetirement(
        val previousToken: String?,
        val failure: Throwable?,
    )

    companion object {
        private const val RECOVERY_REBUILD_BATCH_SIZE: UInt = 64u
    }
}

/** Releases a candidate that will never be published, reporting its close failure on [primary]. */
private fun releaseCandidate(
    candidate: RustEngineAdapter,
    candidateToken: String?,
    primary: Throwable,
    capabilityRegistry: CapabilityRegistry,
) {
    runCatching(candidate::close).exceptionOrNull()?.let(primary::addSuppressed)
    // Capability revoke is never skipped by a failing candidate close.
    candidateToken?.let(capabilityRegistry::revoke)
}

private fun recoveryFromThrowable(error: Throwable): EngineReadiness.ReadOnlyRecovery =
    when (error) {
        is WorkspaceActivationException -> error.recovery
        else ->
            EngineReadiness.ReadOnlyRecovery(
                category = EngineReadiness.FailureCategory.INTERNAL,
                code = "workspace_open_failed",
                retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
                diagnostic = error.message ?: "Workspace open failed",
            )
    }

/**
 * Soft workspace activation failure: candidate opened but never reached Ready.
 * Carries structured recovery so cold-restore can freeze authority without promoting the candidate.
 */
class WorkspaceActivationException(
    val recovery: EngineReadiness.ReadOnlyRecovery,
) : IllegalStateException(
    "Workspace activation did not reach Ready (${recovery.code}): ${recovery.diagnostic}",
)

private fun WorkspaceNativeAdapter.scanAllMemoSnapshots(rootPath: String?): List<WorkspaceMemoSummarySnapshot> {
    val items = mutableListOf<WorkspaceMemoSummarySnapshot>()
    var cursor: String? = null
    do {
        val jobId =
            startWorkspaceScan(
                pageSize = MAX_WORKSPACE_SCAN_PAGE_SIZE,
                cursor = cursor,
                rootPath = rootPath,
            )
        driveToCompletion(jobId)
        val page = readWorkspaceScanPage(jobId)
        items += page.items
        cursor = page.nextCursor
    } while (cursor != null)
    return items
}

private fun WorkspaceNativeAdapter.findMemoSnapshot(identity: String): WorkspaceMemoSummarySnapshot {
    require(identity.isNotBlank()) { "Memo identity must be non-blank" }
    scanAllMemoSnapshots(rootPath = null).firstOrNull { item -> item.identity == identity }?.let { return it }
    throw MarkdownWorkspaceCommandException(
        code = "memo_identity_not_found",
        message = "Memo identity was not found in the active workspace",
    )
}

private fun WorkspaceNativeAdapter.executeMemoCommand(
    rootPath: String?,
    filename: String,
    identity: String,
    command: WorkspaceNativeCommandSpec,
): Boolean {
    val snapshot =
        scanAllMemoSnapshots(rootPath)
            .singleOrNull { item -> item.identity == identity && item.path.substringAfterLast('/') == filename }
            ?: return false
    val jobId =
        startWorkspaceDocumentCommand(
            path = snapshot.path,
            expectedFingerprint = snapshot.fingerprint,
            command = command,
        )
    driveToCompletion(jobId)
    readWorkspaceDocumentCommandResult(jobId)
    return true
}

private fun WorkspaceNativeAdapter.driveToCompletion(jobId: String) {
    when (val terminal = driveJob(jobId)) {
        NativeJobStep.Completed -> Unit
        is NativeJobStep.Failed -> throw terminal.failure.toWorkspaceCommandException()
        is NativeJobStep.BlockedByConflict -> throw terminal.failure.toWorkspaceCommandException()
        NativeJobStep.Running,
        is NativeJobStep.RunningNative,
        is NativeJobStep.NeedsPlatformBatch,
        ->
            throw MarkdownWorkspaceCommandException(
                code = "workspace_job_not_terminal",
                message = "Workspace job did not reach a terminal state",
            )
    }
}

private fun EngineFailureSnapshot.toWorkspaceCommandException(): MarkdownWorkspaceCommandException =
    MarkdownWorkspaceCommandException(code = code, message = diagnostic)

private fun ULong.checkedAdd(other: ULong): ULong {
    if (this > ULong.MAX_VALUE - other) {
        throw MarkdownWorkspaceCommandException(
            code = "task_action_span_overflow",
            message = "Task action span cannot be represented",
        )
    }
    return this + other
}

private fun WorkspaceReminderReferenceSnapshot.matches(reference: ReminderReference): Boolean =
    opaqueId == reference.opaqueId &&
        revision == reference.revision &&
        memoIdentity == reference.memoIdentity &&
        sourceStart == reference.sourceSpan.startByte &&
        sourceEnd == reference.sourceSpan.endByte &&
        tokenFingerprint == reference.tokenFingerprint

private fun WorkspaceReminderReferenceSnapshot.toDomainMarker(): ReminderMarker =
    ReminderMarker(
        dueAt =
            try {
                LocalDateTime.parse(dueAtLocal, ReminderMarker.TIMESTAMP_FORMAT)
            } catch (error: java.time.format.DateTimeParseException) {
                throw WorkspaceRenderBoundaryException(
                    code = "invalid_reminder_due_at",
                    message = "Rust reminder due-at fact is invalid: ${error.message}",
                )
            },
        repeatCount = repeatCount.toIntExact("repeat_count"),
        firedCount = firedCount.toIntExact("fired_count"),
        done = done,
        intervalMinutes = intervalMinutes.toIntExact("interval_minutes"),
        recurrence = Recurrence.fromCode(recurrenceCode),
        reference =
            ReminderReference(
                opaqueId = opaqueId,
                revision = revision,
                memoIdentity = memoIdentity,
                sourceSpan = MarkdownSourceSpan(startByte = sourceStart, endByte = sourceEnd),
                tokenFingerprint = tokenFingerprint,
            ),
        token = token,
    )

private fun UInt.toIntExact(field: String): Int {
    if (this > Int.MAX_VALUE.toUInt()) {
        throw WorkspaceRenderBoundaryException(
            code = "invalid_reminder_$field",
            message = "Rust reminder $field exceeds Kotlin Int",
        )
    }
    return toInt()
}

// A scan page emits one list batch plus one read batch per document. Keep the page below the
// platform driver's hard batch budget so large SAF trees paginate instead of failing activation.
private const val MAX_WORKSPACE_SCAN_PAGE_SIZE: UInt = 63u
