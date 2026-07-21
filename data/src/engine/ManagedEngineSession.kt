package com.lomo.data.engine

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.Recurrence
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.ReminderReference
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.repository.MarkdownReminderRepository
import com.lomo.domain.model.MarkdownWorkspaceCommandException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Sole production owner of the Rust engine lifecycle for the process.
 *
 * Cold start opens with no workspace (`AwaitingWorkspaceSelection`). When a Direct/SAF root is
 * selected (or restored once from persisted settings), [activateWorkspace] opens a candidate engine
 * and only installs it after it reaches [EngineReadiness.Ready]. Soft Recovery and hard open failure
 * leave the previous engine authoritative. Session-owned recovery authority freezes readiness so a
 * bootstrap Awaiting engine cannot resnapshot Recovery away after cold-restore failure.
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
) : EngineReadinessRepository,
    MarkdownWorkspaceRepository,
    MarkdownReminderRepository,
    WorkspaceMarkdownOwner,
    WorkspaceNativeAdapter,
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val activationMutex = Mutex()
    private val adapterLease = ReentrantReadWriteLock()
    private val _readiness = MutableStateFlow<EngineReadiness>(EngineReadiness.AwaitingWorkspaceSelection)
    private val _activeWorkspaceLocation = MutableStateFlow<StorageLocation?>(null)
    private var activeAdapter: RustEngineAdapter? = null
    private var activeCapabilityToken: String? = null
    private var mirrorJob: kotlinx.coroutines.Job? = null

    /**
     * When non-null, session readiness is held by recovery authority and adapter mirrors must not
     * overwrite it with bootstrap Awaiting/Opening. Cleared only by a successful Ready install.
     */
    private val recoveryAuthority = AtomicReference<EngineReadiness.ReadOnlyRecovery?>(null)

    init {
        // Bootstrap without a workspace: publishes AwaitingWorkspaceSelection and keeps a live handle
        // so process teardown can still close cleanly.
        installAdapter(
            adapter = openAdapter(NativeEngineOpenRequest.forAppFilesDir(filesDir)),
            capabilityToken = null,
        )
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

    override fun resnapshot() {
        check(!closed.get()) { "Managed engine session is closed" }
        // Recovery authority is session-owned; do not let bootstrap overwrite it.
        if (recoveryAuthority.get() != null) return
        adapterLease.read {
            activeAdapter?.resnapshot()
        }
    }

    override fun renderMarkdown(
        content: String,
        schemaVersion: UInt,
    ) = withActiveWorkspaceAdapter { adapter -> adapter.renderMarkdown(content, schemaVersion) }

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

    override fun startWorkspaceScan(
        pageSize: UInt,
        cursor: String?,
        rootPath: String?,
        deadlineMillis: ULong,
    ): String =
        withActiveWorkspaceAdapter { adapter ->
            adapter.startWorkspaceScan(pageSize, cursor, rootPath, deadlineMillis)
        }

    override fun driveJob(jobId: String): NativeJobStep =
        withActiveWorkspaceAdapter { adapter -> adapter.driveJob(jobId) }

    override fun readWorkspaceScanPage(jobId: String): WorkspaceScanPageSnapshot =
        withActiveWorkspaceAdapter { adapter -> adapter.readWorkspaceScanPage(jobId) }

    override fun startWorkspaceDocumentCommand(
        path: String,
        expectedFingerprint: String,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String =
        withActiveWorkspaceAdapter { adapter ->
            adapter.startWorkspaceDocumentCommand(
                path,
                expectedFingerprint,
                command,
                deadlineMillis,
            )
        }

    override fun queryMemos(
        query: com.lomo.nativebridge.StoreMemoQuery,
        cursor: com.lomo.nativebridge.StorePageCursor?,
        pageSize: UInt,
    ): com.lomo.nativebridge.StoreMemoPage =
        withActiveWorkspaceAdapter { adapter -> adapter.queryMemos(query, cursor, pageSize) }

    override fun getMemo(memoId: String): com.lomo.nativebridge.StoreMemoSnapshot? =
        withActiveWorkspaceAdapter { adapter -> adapter.getMemo(memoId) }

    override fun applyMemoCommand(
        command: com.lomo.nativebridge.StoreMemoCommand,
    ): com.lomo.nativebridge.StoreMemoCommit =
        withActiveWorkspaceAdapter { adapter -> adapter.applyMemoCommand(command) }

    override fun startRebuild(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult =
        withActiveWorkspaceAdapter { adapter -> adapter.startRebuild(batchSize) }

    override fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot =
        withActiveWorkspaceAdapter { adapter -> adapter.readWorkspaceDocumentCommandResult(jobId) }

    override suspend fun activateWorkspace(location: StorageLocation) {
        check(!closed.get()) { "Managed engine session is closed" }
        require(location.raw.isNotBlank()) { "Workspace location must be non-blank" }
        activationMutex.withLock {
            check(!closed.get()) { "Managed engine session is closed" }
            val selection = selectionFor(location)
            val request =
                NativeEngineOpenRequest.forAppFilesDir(filesDir).copy(
                    workspace = selection.workspace,
                )
            // Candidate open keeps the previous adapter authoritative until Ready is installed.
            val candidateResult = runCatching { openAdapter(request) }
            val candidate =
                candidateResult.getOrElse { error ->
                    // Hard open failure: leave previous engine authoritative and rethrow.
                    selection.capabilityToken?.let(capabilityRegistry::revoke)
                    throw error
                }
            val candidateReadiness = candidate.readiness.value
            if (candidateReadiness !is EngineReadiness.Ready) {
                // Soft open (Recovery / Opening / Awaiting): never promote; close candidate.
                val failure =
                    when (candidateReadiness) {
                        is EngineReadiness.ReadOnlyRecovery -> candidateReadiness
                        else ->
                            EngineReadiness.ReadOnlyRecovery(
                                category = EngineReadiness.FailureCategory.INTERNAL,
                                code = "workspace_open_not_ready",
                                retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
                                diagnostic =
                                    "Workspace open did not reach Ready " +
                                        "(${candidateReadiness::class.simpleName})",
                            )
                    }
                candidate.close()
                selection.capabilityToken?.let(capabilityRegistry::revoke)
                throw WorkspaceActivationException(failure)
            }
            val previousToken =
                adapterLease.write {
                    val previous = activeAdapter
                    val token = activeCapabilityToken
                    // Ready install clears any prior recovery authority and becomes sole publisher.
                    recoveryAuthority.set(null)
                    installAdapterLocked(candidate, capabilityToken = selection.capabilityToken)
                    _activeWorkspaceLocation.value = location
                    // Exclusive lease waits for every in-flight workspace call before close.
                    previous?.close()
                    token
                }
            previousToken
                ?.takeIf { it != selection.capabilityToken }
                ?.let(capabilityRegistry::revoke)
        }
    }

    override suspend fun clearWorkspace() {
        if (closed.get()) return
        activationMutex.withLock {
            if (closed.get()) return
            // Reselect / failed first selection: open awaiting engine under the activation mutex.
            val bootstrap = openAdapter(NativeEngineOpenRequest.forAppFilesDir(filesDir))
            val previousToken =
                adapterLease.write {
                    val previous = activeAdapter
                    val token = activeCapabilityToken
                    recoveryAuthority.set(null)
                    installAdapterLocked(bootstrap, capabilityToken = null)
                    _activeWorkspaceLocation.value = null
                    previous?.close()
                    token
                }
            previousToken?.let(capabilityRegistry::revoke)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val token =
            adapterLease.write {
                mirrorJob?.cancel()
                mirrorJob = null
                recoveryAuthority.set(null)
                val adapter = activeAdapter
                activeAdapter = null
                val activeToken = activeCapabilityToken
                activeCapabilityToken = null
                _activeWorkspaceLocation.value = null
                adapter?.close()
                activeToken
            }
        token?.let(capabilityRegistry::revoke)
        _readiness.value = EngineReadiness.ShuttingDown
    }

    private fun holdRecoveryAuthority(recovery: EngineReadiness.ReadOnlyRecovery) {
        recoveryAuthority.set(recovery)
        _readiness.value = recovery
    }

    private fun installAdapter(
        adapter: RustEngineAdapter,
        capabilityToken: String?,
    ) {
        adapterLease.write {
            installAdapterLocked(adapter, capabilityToken)
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
                        }
                    }
                }
            }
    }

    private inline fun <T> withActiveWorkspaceAdapter(block: (RustEngineAdapter) -> T): T {
        check(!closed.get()) { "Managed engine session is closed" }
        return adapterLease.read {
            check(_readiness.value is EngineReadiness.Ready) {
                "Workspace engine is not Ready"
            }
            val adapter = activeAdapter ?: error("Managed engine session has no active adapter")
            block(adapter)
        }
    }

    private fun selectionFor(location: StorageLocation): PreparedSelection {
        val raw = location.raw.trim()
        return if (isContentUri(raw)) {
            val token = "cap-${UUID.randomUUID()}"
            capabilityRegistry.register(token = token, treeUri = raw)
            PreparedSelection(
                workspace = NativeWorkspaceSelection.Saf(capabilityToken = token),
                capabilityToken = token,
            )
        } else {
            PreparedSelection(
                workspace = NativeWorkspaceSelection.Direct(rootPath = File(raw)),
                capabilityToken = null,
            )
        }
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

    private data class PreparedSelection(
        val workspace: NativeWorkspaceSelection,
        val capabilityToken: String?,
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

private const val MAX_WORKSPACE_SCAN_PAGE_SIZE: UInt = 256u
