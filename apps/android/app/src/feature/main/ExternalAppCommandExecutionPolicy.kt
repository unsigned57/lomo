package com.lomo.app.feature.main

import com.lomo.app.ExternalAppCommand
import com.lomo.app.ExternalAppCommandAction
import com.lomo.app.ExternalAppCommandStatus
import com.lomo.app.ExternalAppCommandTerminalResult

internal data class ExternalAppCommandReadiness(
    val appReady: Boolean,
    val voiceDirectoryConfigured: Boolean,
    val editorVisible: Boolean,
    val canOpenDraftEditor: Boolean,
    val hasRecordAudioPermission: Boolean,
    val isRecording: Boolean,
    /** Identity of the live capture; a session-bound stop only acts when its payload matches. */
    val recordingCaptureId: String? = null,
)

internal enum class ExternalAppCommandStep {
    RequestVoiceDirectory,
    OpenDraftEditor,
    EnsureEditorVisible,
    RequestRecordAudioPermission,
    StartRecording,
    StopRecording,
}

internal data class ExternalAppCommandExecutionPlan(
    val statusUpdate: ExternalAppCommandStatus? = null,
    val steps: List<ExternalAppCommandStep> = emptyList(),
    val terminalResult: ExternalAppCommandTerminalResult? = null,
)

internal fun planExternalAppCommandExecution(
    command: ExternalAppCommand,
    readiness: ExternalAppCommandReadiness,
): ExternalAppCommandExecutionPlan {
    if (!readiness.appReady) {
        return waitFor(ExternalAppCommandStatus.WaitingForRoot, command.status)
    }
    return when (command.action) {
        ExternalAppCommandAction.CreateMemo ->
            planCreateMemoCommand(
                status = command.status,
                readiness = readiness,
            )

        ExternalAppCommandAction.StartRecording ->
            planStartRecordingCommand(
                status = command.status,
                readiness = readiness,
            )

        ExternalAppCommandAction.StopRecording ->
            planStopRecordingCommand(
                command = command,
                readiness = readiness,
            )
    }
}

private fun planCreateMemoCommand(
    status: ExternalAppCommandStatus,
    readiness: ExternalAppCommandReadiness,
): ExternalAppCommandExecutionPlan {
    val editorStep = editorTargetStep(status = status, readiness = readiness) ?: return waitForEditor(status)
    return ExternalAppCommandExecutionPlan(
        steps = listOf(editorStep),
        terminalResult = ExternalAppCommandTerminalResult.Executed,
    )
}

private fun planStartRecordingCommand(
    status: ExternalAppCommandStatus,
    readiness: ExternalAppCommandReadiness,
): ExternalAppCommandExecutionPlan {
    if (readiness.isRecording) {
        return ExternalAppCommandExecutionPlan(
            terminalResult = ExternalAppCommandTerminalResult.AlreadySatisfied,
        )
    }
    if (!readiness.voiceDirectoryConfigured) {
        return waitFor(
            status = ExternalAppCommandStatus.WaitingForVoiceDirectory,
            currentStatus = status,
            firstWaitStep = ExternalAppCommandStep.RequestVoiceDirectory,
        )
    }
    val editorStep = editorTargetStep(status = status, readiness = readiness) ?: return waitForEditor(status)
    if (!readiness.hasRecordAudioPermission) {
        return ExternalAppCommandExecutionPlan(
            statusUpdate = ExternalAppCommandStatus.WaitingForRecordAudioPermission,
            steps =
                listOf(
                    editorStep,
                    ExternalAppCommandStep.RequestRecordAudioPermission,
                ),
        )
    }
    return ExternalAppCommandExecutionPlan(
        steps =
            listOf(
                editorStep,
                ExternalAppCommandStep.StartRecording,
            ),
        terminalResult = ExternalAppCommandTerminalResult.Executed,
    )
}

private fun planStopRecordingCommand(
    command: ExternalAppCommand,
    readiness: ExternalAppCommandReadiness,
): ExternalAppCommandExecutionPlan {
    // A session-bound stop resolves ended for any capture it was not issued for — including a
    // dead process where nothing is recording — and must never become a start.
    val targetsOtherCapture =
        command.payload != null && command.payload != readiness.recordingCaptureId
    if (!readiness.isRecording || targetsOtherCapture) {
        return ExternalAppCommandExecutionPlan(
            terminalResult = ExternalAppCommandTerminalResult.AlreadySatisfied,
        )
    }
    val editorStep =
        editorTargetStep(status = command.status, readiness = readiness)
            ?: return waitForEditor(command.status)
    return ExternalAppCommandExecutionPlan(
        steps =
            listOf(
                editorStep,
                ExternalAppCommandStep.StopRecording,
            ),
        terminalResult = ExternalAppCommandTerminalResult.Executed,
    )
}

private fun editorTargetStep(
    status: ExternalAppCommandStatus,
    readiness: ExternalAppCommandReadiness,
): ExternalAppCommandStep? =
    when {
        readiness.editorVisible -> ExternalAppCommandStep.EnsureEditorVisible
        readiness.canOpenDraftEditor -> ExternalAppCommandStep.OpenDraftEditor
        status == ExternalAppCommandStatus.WaitingForEditor -> null
        else -> null
    }

private fun waitForEditor(status: ExternalAppCommandStatus): ExternalAppCommandExecutionPlan =
    waitFor(
        status = ExternalAppCommandStatus.WaitingForEditor,
        currentStatus = status,
    )

private fun waitFor(
    status: ExternalAppCommandStatus,
    currentStatus: ExternalAppCommandStatus,
    firstWaitStep: ExternalAppCommandStep? = null,
): ExternalAppCommandExecutionPlan =
    if (currentStatus == status) {
        ExternalAppCommandExecutionPlan()
    } else {
        ExternalAppCommandExecutionPlan(
            statusUpdate = status,
            steps = listOfNotNull(firstWaitStep),
        )
    }
