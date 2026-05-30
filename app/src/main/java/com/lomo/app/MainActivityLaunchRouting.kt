package com.lomo.app

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

internal sealed interface PendingLaunchAction {
    data class SharedText(
        val text: String,
    ) : PendingLaunchAction

    data class SharedImage(
        val uri: Uri,
    ) : PendingLaunchAction

    data object CreateMemo : PendingLaunchAction

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
}

internal data class EntryFlowReadiness(
    val appLock: EntryAppLockState,
    val configuredCapabilities: Set<EntryCapability>,
    val workspace: EntryWorkspaceState = EntryWorkspaceState.Ready,
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
        PendingLaunchAction.CreateMemo,
        is PendingLaunchAction.OpenMemo,
        is PendingLaunchAction.SharedText,
        -> setOf(EntryCapability.RootWorkspace)

        is PendingLaunchAction.SharedImage ->
            setOf(
                EntryCapability.RootWorkspace,
                EntryCapability.ImageWorkspace,
            )
    }

internal fun extractPendingLaunchActions(intent: Intent?): List<PendingLaunchAction> {
    if (intent == null) {
        return emptyList()
    }
    return when (intent.action) {
        Intent.ACTION_SEND,
        Intent.ACTION_SEND_MULTIPLE,
        -> extractShareLaunchActions(intent)

        MainActivity.ACTION_NEW_MEMO -> listOf(PendingLaunchAction.CreateMemo)

        MainActivity.ACTION_OPEN_MEMO ->
            intent.getStringExtra(MainActivity.EXTRA_MEMO_ID)
                ?.takeIf(String::isNotBlank)
                ?.let { memoId -> listOf(PendingLaunchAction.OpenMemo(memoId)) }
                .orEmpty()

        "com.lomo.reminder.action.OPEN" ->
            intent.getStringExtra("memo_id")
                ?.takeIf(String::isNotBlank)
                ?.let { memoId -> listOf(PendingLaunchAction.OpenMemo(memoId)) }
                .orEmpty()

        else -> emptyList()
    }
}

private fun extractShareLaunchActions(intent: Intent): List<PendingLaunchAction> {
    val type = intent.type.orEmpty()
    if (type.startsWith("text/")) {
        return extractSharedTexts(intent).map(PendingLaunchAction::SharedText)
    }
    if (type.startsWith("image/")) {
        return extractSharedImageUris(intent).map(PendingLaunchAction::SharedImage)
    }
    return buildList {
        extractSharedTexts(intent).forEach { text -> add(PendingLaunchAction.SharedText(text)) }
        extractSharedImageUris(intent).forEach { uri -> add(PendingLaunchAction.SharedImage(uri)) }
    }
}

private fun extractSharedTexts(intent: Intent): List<String> {
    val texts = mutableListOf<String>()
    val extras = intent.extras
    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
        if (text.isNotBlank()) {
            texts += text
        }
    }
    extras?.getStringArrayList(Intent.EXTRA_TEXT)?.forEach { text ->
        if (text.isNotBlank()) {
            texts += text
        }
    }
    extras?.getCharSequenceArrayList(Intent.EXTRA_TEXT)?.forEach { text ->
        val normalized = text?.toString()
        if (!normalized.isNullOrBlank()) {
            texts += normalized
        }
    }
    return texts.distinct()
}

private fun extractSharedImageUris(intent: Intent): List<Uri> {
    val uris = mutableListOf<Uri>()
    IntentCompat
        .getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        ?.let(uris::add)
    IntentCompat
        .getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        ?.forEach(uris::add)
    intent.clipData?.let { clipData ->
        repeat(clipData.itemCount) { index ->
            clipData.getItemAt(index).uri?.let(uris::add)
        }
    }
    return uris.distinct()
}
