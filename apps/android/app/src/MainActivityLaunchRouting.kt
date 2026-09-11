package com.lomo.app

import android.net.Uri

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
 * Maps domain engine readiness into the entry-flow workspace lane.
 * Recovery is ordered ahead of writable surfaces by [resolveUnlockedEntryFlowState].
 */
internal fun entryWorkspaceStateFor(
    readiness: com.lomo.domain.model.EngineReadiness,
): Pair<EntryWorkspaceState, Pair<String, String>?> =
    when (readiness) {
        com.lomo.domain.model.EngineReadiness.AwaitingWorkspaceSelection ->
            EntryWorkspaceState.Resolving to null
        com.lomo.domain.model.EngineReadiness.Opening ->
            EntryWorkspaceState.Preparing to null
        is com.lomo.domain.model.EngineReadiness.Ready ->
            EntryWorkspaceState.Ready to null
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

