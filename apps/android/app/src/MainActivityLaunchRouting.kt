package com.lomo.app

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lomo.domain.model.SecuritySessionState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber

internal sealed interface PendingLaunchAction {
    data class SharedText(
        val text: String,
    ) : PendingLaunchAction

    data class SharedImage(
        val uri: Uri,
    ) : PendingLaunchAction

    data class OpenMemo(
        val memoId: String,
    ) : PendingLaunchAction
}

internal data class PendingLaunchCommand(
    val id: Long,
    val action: PendingLaunchAction,
)

internal sealed interface EntryFlowRequest {
    val action: PendingLaunchAction?

    data object DefaultLaunch : EntryFlowRequest {
        override val action: PendingLaunchAction? = null
    }

    data class PendingCommand(
        val command: PendingLaunchCommand,
    ) : EntryFlowRequest {
        override val action: PendingLaunchAction = command.action
    }
}

internal enum class EntryAppLockState {
    Resolving,
    Locked,
    Unlocked,
}

internal fun entryAppLockStateFor(session: SecuritySessionState): EntryAppLockState =
    when (session) {
        SecuritySessionState.Unknown -> EntryAppLockState.Resolving
        SecuritySessionState.Locked,
        SecuritySessionState.StorageFailure,
        -> EntryAppLockState.Locked
        SecuritySessionState.LockOff,
        SecuritySessionState.Unlocked,
        -> EntryAppLockState.Unlocked
    }

internal enum class EntryCapability {
    RootWorkspace,
    ImageWorkspace,
    VoiceWorkspace,
}

internal enum class EntryWorkspaceState {
    Resolving,
    Preparing,
    Ready,
    ReadOnlyRecovery,
}

internal enum class ActivityInstanceState {
    Fresh,
    Restored,
}

internal data class EntryFlowReadiness(
    val appLock: EntryAppLockState,
    val configuredCapabilities: Set<EntryCapability>,
    val workspace: EntryWorkspaceState = EntryWorkspaceState.Ready,
    val recoveryCode: String? = null,
    val recoveryDiagnostic: String? = null,
)

internal sealed interface EntryFlowState {
    data class WaitingForAppLockResolution(
        val request: EntryFlowRequest,
    ) : EntryFlowState

    data class BlockedByAppLock(
        val request: EntryFlowRequest,
    ) : EntryFlowState

    data class NeedsWorkspaceSetup(
        val request: EntryFlowRequest,
        val missingCapabilities: Set<EntryCapability>,
    ) : EntryFlowState

    data class WaitingForWorkspaceReadiness(
        val request: EntryFlowRequest,
    ) : EntryFlowState

    data class EngineReadOnlyRecovery(
        val request: EntryFlowRequest,
        val code: String,
        val diagnostic: String,
    ) : EntryFlowState

    data class MissingRequiredCapability(
        val request: EntryFlowRequest.PendingCommand,
        val missingCapabilities: Set<EntryCapability>,
    ) : EntryFlowState

    data class Ready(
        val command: PendingLaunchCommand,
    ) : EntryFlowState

    data object ReadyForDefaultLaunch : EntryFlowState
}

internal fun resolveEntryFlowState(
    request: EntryFlowRequest,
    readiness: EntryFlowReadiness,
): EntryFlowState =
    when (readiness.appLock) {
        EntryAppLockState.Resolving ->
            EntryFlowState.WaitingForAppLockResolution(request)

        EntryAppLockState.Locked ->
            EntryFlowState.BlockedByAppLock(request)

        EntryAppLockState.Unlocked ->
            resolveUnlockedEntryFlowState(
                request = request,
                readiness = readiness,
            )
    }

private fun resolveUnlockedEntryFlowState(
    request: EntryFlowRequest,
    readiness: EntryFlowReadiness,
): EntryFlowState {
    val requiredCapabilities = requiredEntryCapabilities(request.action)
    val missingCapabilities = requiredCapabilities - readiness.configuredCapabilities
    return when {
        readiness.workspace == EntryWorkspaceState.ReadOnlyRecovery ->
            EntryFlowState.EngineReadOnlyRecovery(
                request = request,
                code = readiness.recoveryCode ?: "engine_readonly",
                diagnostic = readiness.recoveryDiagnostic ?: "Engine is read-only",
            )

        EntryCapability.RootWorkspace in missingCapabilities ->
            EntryFlowState.NeedsWorkspaceSetup(
                request = request,
                missingCapabilities = setOf(EntryCapability.RootWorkspace),
            )

        readiness.workspace != EntryWorkspaceState.Ready ->
            EntryFlowState.WaitingForWorkspaceReadiness(request)

        missingCapabilities.isNotEmpty() ->
            EntryFlowState.MissingRequiredCapability(
                request = request.requirePendingCommandForCapabilityGate(),
                missingCapabilities = missingCapabilities,
            )

        request == EntryFlowRequest.DefaultLaunch -> EntryFlowState.ReadyForDefaultLaunch

        else -> EntryFlowState.Ready((request as EntryFlowRequest.PendingCommand).command)
    }
}

private fun EntryFlowRequest.requirePendingCommandForCapabilityGate(): EntryFlowRequest.PendingCommand =
    this as? EntryFlowRequest.PendingCommand
        ?: error("Only action-backed entry flows can require non-root capabilities.")

internal fun resolvePendingLaunchCommandEntryFlowState(
    command: PendingLaunchCommand,
    readiness: EntryFlowReadiness,
): EntryFlowState =
    resolveEntryFlowState(
        request = EntryFlowRequest.PendingCommand(command),
        readiness = readiness,
    )

internal fun requiredEntryCapabilities(action: PendingLaunchAction?): Set<EntryCapability> =
    when (action) {
        null,
        is PendingLaunchAction.OpenMemo,
        is PendingLaunchAction.SharedText,
        -> setOf(EntryCapability.RootWorkspace)

        is PendingLaunchAction.SharedImage ->
            setOf(
                EntryCapability.RootWorkspace,
                EntryCapability.ImageWorkspace,
            )
    }

/**
 * Maps the published workspace mount into the entry-flow workspace lane.
 *
 * Dispatch is admitted only by the mount itself: a Ready engine whose mount does not admit
 * projection reads keeps commands queued instead of recomposing readiness, authority and
 * freshness a second time. Recovery is ordered ahead of writable surfaces by
 * [resolveUnlockedEntryFlowState].
 */
internal fun entryWorkspaceStateFor(
    mount: com.lomo.domain.model.WorkspaceMount,
): Pair<EntryWorkspaceState, Pair<String, String>?> =
    when (val readiness = mount.readiness) {
        com.lomo.domain.model.EngineReadiness.AwaitingWorkspaceSelection ->
            EntryWorkspaceState.Resolving to null
        com.lomo.domain.model.EngineReadiness.Opening ->
            EntryWorkspaceState.Preparing to null
        is com.lomo.domain.model.EngineReadiness.Ready ->
            if (mount.admitsProjectionReads) {
                EntryWorkspaceState.Ready to null
            } else {
                EntryWorkspaceState.Preparing to null
            }
        is com.lomo.domain.model.EngineReadiness.ReadOnlyRecovery ->
            EntryWorkspaceState.ReadOnlyRecovery to (readiness.code to readiness.diagnostic)
        com.lomo.domain.model.EngineReadiness.ShuttingDown ->
            EntryWorkspaceState.Preparing to null
    }

/**
 * Deep-link / share command dispatch is writable-surface work. Only Ready may apply pending entry
 * commands; Recovery/Opening keep commands queued so entry does not mutate under a frozen engine.
 */
internal fun shouldDispatchPendingLaunchCommands(workspace: EntryWorkspaceState): Boolean =
    workspace == EntryWorkspaceState.Ready

/**
 * Dispatches one queued launch command against the current session/workspace facts.
 * Returns true only when the command was handed to its consumer — the caller acknowledges
 * exactly that command immediately, so an interrupted dispatch pass can never re-apply a
 * command whose action already ran.
 */
internal fun dispatchPendingLaunchCommand(
    command: PendingLaunchCommand,
    readiness: EntryFlowReadiness,
    dispatch: (PendingLaunchAction) -> Unit,
): Boolean =
    when (resolvePendingLaunchCommandEntryFlowState(command = command, readiness = readiness)) {
        is EntryFlowState.Ready -> {
            dispatch(command.action)
            true
        }

        else -> false
    }

/**
 * Durable-enough snapshot of the pending launch queue for Activity saved state.
 * Commands accepted but not yet dispatched survive configuration and process recreation and
 * dispatch exactly once; this is an in-memory queue contract, not cross-process exactly-once.
 */
@kotlinx.serialization.Serializable
internal data class PendingLaunchCommandSnapshot(
    val nextCommandId: Long,
    val commands: List<SnapshotPendingLaunchCommand>,
) {
    @kotlinx.serialization.Serializable
    internal data class SnapshotPendingLaunchCommand(
        val id: Long,
        val action: String,
        val payload: String,
    )
}

private const val SNAPSHOT_ACTION_SHARED_TEXT = "shared_text"
private const val SNAPSHOT_ACTION_SHARED_IMAGE = "shared_image"
private const val SNAPSHOT_ACTION_OPEN_MEMO = "open_memo"

internal fun pendingLaunchCommandsSnapshot(
    nextCommandId: Long,
    commands: List<PendingLaunchCommand>,
): PendingLaunchCommandSnapshot =
    PendingLaunchCommandSnapshot(
        nextCommandId = nextCommandId,
        commands =
            commands.map { command ->
                when (val action = command.action) {
                    is PendingLaunchAction.SharedText ->
                        PendingLaunchCommandSnapshot.SnapshotPendingLaunchCommand(
                            id = command.id,
                            action = SNAPSHOT_ACTION_SHARED_TEXT,
                            payload = action.text,
                        )

                    is PendingLaunchAction.SharedImage ->
                        PendingLaunchCommandSnapshot.SnapshotPendingLaunchCommand(
                            id = command.id,
                            action = SNAPSHOT_ACTION_SHARED_IMAGE,
                            payload = action.uri.toString(),
                        )

                    is PendingLaunchAction.OpenMemo ->
                        PendingLaunchCommandSnapshot.SnapshotPendingLaunchCommand(
                            id = command.id,
                            action = SNAPSHOT_ACTION_OPEN_MEMO,
                            payload = action.memoId,
                        )
                }
            },
    )

internal fun PendingLaunchCommandSnapshot.toPendingLaunchQueue(): Pair<Long, List<PendingLaunchCommand>> =
    nextCommandId to
        commands.mapNotNull { command ->
            // behavior-contract: silent-result-ok: a snapshot written by a newer version can name
            // an action this build cannot execute; dropping it preserves the commands we can run
            val action =
                when (command.action) {
                    SNAPSHOT_ACTION_SHARED_TEXT -> PendingLaunchAction.SharedText(command.payload)
                    SNAPSHOT_ACTION_SHARED_IMAGE ->
                        PendingLaunchAction.SharedImage(Uri.parse(command.payload))
                    SNAPSHOT_ACTION_OPEN_MEMO -> PendingLaunchAction.OpenMemo(command.payload)
                    else -> return@mapNotNull null
                }
            PendingLaunchCommand(id = command.id, action = action)
        }

/**
 * The Activity-owned pending launch queue: enqueue/consume plus saved-state snapshot round-trip.
 * Commands are admitted once and acknowledged per command so a recreated Activity replays
 * exactly the still-undispatched tail.
 */
internal class PendingLaunchCommandQueue {
    private var nextCommandId = 0L

    var commands by mutableStateOf<ImmutableList<PendingLaunchCommand>>(persistentListOf())
        private set

    fun enqueue(action: PendingLaunchAction) {
        commands =
            (commands + PendingLaunchCommand(id = nextCommandId++, action = action))
                .toImmutableList()
    }

    fun consume(commandId: Long) {
        commands = commands.filterNot { it.id == commandId }.toImmutableList()
    }

    fun snapshotJson(): String =
        pendingLaunchCommandSnapshotJson.encodeToString(
            PendingLaunchCommandSnapshot.serializer(),
            pendingLaunchCommandsSnapshot(
                nextCommandId = nextCommandId,
                commands = commands,
            ),
        )

    /**
     * Merges a persisted tail into the live queue instead of replacing it: anything enqueued before
     * restore runs (e.g. an intent consumed ahead of the saved-state pass) stays queued ahead of
     * the restored commands, and the id allocator resumes past every id either side has used.
     */
    fun restore(savedState: String?) {
        val json = savedState?.takeIf(String::isNotBlank) ?: return
        val (restoredNextId, restored) =
            try {
                pendingLaunchCommandSnapshotJson
                    .decodeFromString(PendingLaunchCommandSnapshot.serializer(), json)
                    .toPendingLaunchQueue()
            } catch (error: SerializationException) {
                // behavior-contract: silent-result-ok: a corrupted saved queue cannot rebuild into
                // live commands; dropping it keeps the restored launch from re-firing garbage
                Timber.w(error, "Dropping corrupted pending launch command snapshot")
                return
            }
        // A snapshot's recorded counter can lag its own command ids; resume past both.
        nextCommandId = maxOf(nextCommandId, restoredNextId, (restored.maxOfOrNull { it.id } ?: -1L) + 1)
        val claimedIds = commands.mapTo(mutableSetOf()) { it.id }
        val merged =
            restored.map { command ->
                if (command.id in claimedIds) {
                    // A restored id already live in memory must not alias it — consume(id) would
                    // acknowledge both. Re-key the restored command onto a fresh id instead.
                    command.copy(id = nextCommandId++).also { claimedIds += it.id }
                } else {
                    command.also { claimedIds += it.id }
                }
            }
        commands = (commands + merged).toImmutableList()
    }

    private companion object {
        val pendingLaunchCommandSnapshotJson =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}

