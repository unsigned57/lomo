package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.lomo.app.ExternalAppCommand
import com.lomo.app.ExternalAppCommandTerminalResult
import com.lomo.app.feature.memo.MemoEditorController
import com.lomo.app.feature.memo.captureMarkdownInsertion
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun ExternalAppCommandExecutionEffect(
    commands: ImmutableList<ExternalAppCommand>,
    readiness: ExternalAppCommandReadiness,
    /**
     * Read at execution time, never an effect key: a keystroke change used to restart this effect
     * and re-run every pending command's steps (including re-seeding the draft editor).
     */
    draftText: StateFlow<String>,
    onRequireVoiceDirectory: () -> Unit,
    editorController: MemoEditorController,
    onRequestRecordAudioPermission: (ExternalAppCommand) -> Unit,
    executionTarget: MainExternalCommandExecutionTarget,
    permissionRequestInFlightCommandId: String?,
) {
    LaunchedEffect(commands, readiness, permissionRequestInFlightCommandId) {
        val nowMillis = System.currentTimeMillis()
        executionTarget.commandActions.onExpireExternalAppCommands(nowMillis)
        commands
            .asSequence()
            .filterNot { command -> command.isExpired(nowMillis) || command.status.isTerminal }
            .sortedBy(ExternalAppCommand::createdAtMillis)
            .forEach { command ->
                if (command.id == permissionRequestInFlightCommandId) {
                    return@forEach
                }
                executeExternalAppCommandPlan(
                    command = command,
                    plan =
                    planExternalAppCommandExecution(
                        command = command,
                        readiness = readiness,
                    ),
                    draftText = draftText,
                    onRequireVoiceDirectory = onRequireVoiceDirectory,
                    editorController = editorController,
                    onRequestRecordAudioPermission = onRequestRecordAudioPermission,
                    executionTarget = executionTarget,
                )
            }
    }
}

private fun executeExternalAppCommandPlan(
    command: ExternalAppCommand,
    plan: ExternalAppCommandExecutionPlan,
    draftText: StateFlow<String>,
    onRequireVoiceDirectory: () -> Unit,
    editorController: MemoEditorController,
    onRequestRecordAudioPermission: (ExternalAppCommand) -> Unit,
    executionTarget: MainExternalCommandExecutionTarget,
) {
    plan.statusUpdate?.let { status ->
        executionTarget.commandActions.onUpdateExternalAppCommandStatus(command.id, status)
    }
    var terminalHandledByAsyncStep = false
    plan.steps.forEach { step ->
        when (step) {
            ExternalAppCommandStep.RequestVoiceDirectory -> onRequireVoiceDirectory()

            ExternalAppCommandStep.OpenDraftEditor -> editorController.openForCreate(draftText.value)

            ExternalAppCommandStep.EnsureEditorVisible -> editorController.ensureVisible()

            ExternalAppCommandStep.RequestRecordAudioPermission -> onRequestRecordAudioPermission(command)

            ExternalAppCommandStep.StartRecording -> executionTarget.recordingActions.onStartRecording(
                editorController.editingSession?.draftId ?: executionTarget.editorBindings.flows.getOwnerDraftId(),
            )

            ExternalAppCommandStep.StopRecording -> {
                terminalHandledByAsyncStep = true
                val insertRecording = editorController.captureMarkdownInsertion()
                executionTarget.recordingActions.onStopRecording { markdown ->
                    if (markdown != null) {
                        insertRecording(markdown)
                        executionTarget.editorBindings.actions.onTrackVoiceMarkdown(markdown)
                        executionTarget.commandActions.onCompleteExternalAppCommand(
                            command.id,
                            ExternalAppCommandTerminalResult.Executed,
                        )
                    } else {
                        executionTarget.commandActions.onCompleteExternalAppCommand(
                            command.id,
                            ExternalAppCommandTerminalResult.Failed,
                        )
                    }
                }
            }
        }
    }
    if (!terminalHandledByAsyncStep) {
        plan.terminalResult?.let { result ->
            executionTarget.commandActions.onCompleteExternalAppCommand(command.id, result)
        }
    }
}
