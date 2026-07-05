package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.lomo.app.ExternalAppCommand
import com.lomo.app.ExternalAppCommandTerminalResult
import com.lomo.app.feature.memo.MemoEditorController
import com.lomo.app.feature.memo.appendMarkdownBlock
import kotlinx.collections.immutable.ImmutableList

@Composable
internal fun ExternalAppCommandExecutionEffect(
    commands: ImmutableList<ExternalAppCommand>,
    readiness: ExternalAppCommandReadiness,
    draftText: String,
    directoryGuideController: MainDirectoryGuideController,
    editorController: MemoEditorController,
    onRequestRecordAudioPermission: (ExternalAppCommand) -> Unit,
    dependencies: MainScreenDependencies,
    permissionRequestInFlightCommandId: String?,
) {
    LaunchedEffect(commands, readiness, draftText, permissionRequestInFlightCommandId) {
        val nowMillis = System.currentTimeMillis()
        dependencies.mainViewModel.expireExternalAppCommands(nowMillis)
        commands
            .asSequence()
            .filterNot { command -> command.isExpired(nowMillis) }
            .sortedBy(ExternalAppCommand::createdAtMillis)
            .forEach { command ->
                if (command.id == permissionRequestInFlightCommandId) {
                    return@forEach
                }
                executeExternalAppCommandPlan(
                    command = command,
                    plan =
                        planExternalAppCommandExecution(
                            action = command.action,
                            status = command.status,
                            readiness = readiness,
                        ),
                    draftText = draftText,
                    directoryGuideController = directoryGuideController,
                    editorController = editorController,
                    onRequestRecordAudioPermission = onRequestRecordAudioPermission,
                    dependencies = dependencies,
                )
            }
    }
}

private fun executeExternalAppCommandPlan(
    command: ExternalAppCommand,
    plan: ExternalAppCommandExecutionPlan,
    draftText: String,
    directoryGuideController: MainDirectoryGuideController,
    editorController: MemoEditorController,
    onRequestRecordAudioPermission: (ExternalAppCommand) -> Unit,
    dependencies: MainScreenDependencies,
) {
    plan.statusUpdate?.let { status ->
        dependencies.mainViewModel.updateExternalAppCommandStatus(command.id, status)
    }
    var terminalHandledByAsyncStep = false
    plan.steps.forEach { step ->
        when (step) {
            ExternalAppCommandStep.RequestVoiceDirectory -> directoryGuideController.requestVoice()
            ExternalAppCommandStep.OpenDraftEditor -> editorController.openForCreate(draftText)
            ExternalAppCommandStep.EnsureEditorVisible -> editorController.ensureVisible()
            ExternalAppCommandStep.RequestRecordAudioPermission -> onRequestRecordAudioPermission(command)
            ExternalAppCommandStep.StartRecording -> dependencies.recordingViewModel.startRecording()
            ExternalAppCommandStep.StopRecording -> {
                terminalHandledByAsyncStep = true
                dependencies.recordingViewModel.stopRecording { markdown ->
                    if (markdown != null) {
                        editorController.appendMarkdownBlock(markdown)
                        dependencies.mainViewModel.completeExternalAppCommand(
                            command.id,
                            ExternalAppCommandTerminalResult.Executed,
                        )
                    } else {
                        dependencies.mainViewModel.completeExternalAppCommand(
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
            dependencies.mainViewModel.completeExternalAppCommand(command.id, result)
        }
    }
}
