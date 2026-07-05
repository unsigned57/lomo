package com.lomo.app.feature.main

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
    action: ExternalAppCommandAction,
    status: ExternalAppCommandStatus,
    readiness: ExternalAppCommandReadiness,
): ExternalAppCommandExecutionPlan {
    if (!readiness.appReady) {
        return waitFor(ExternalAppCommandStatus.WaitingForRoot, status)
    }
    return when (action) {
        ExternalAppCommandAction.CreateMemo ->
            planCreateMemoCommand(
                status = status,
                readiness = readiness,
            )

        ExternalAppCommandAction.StartRecording ->
            planStartRecordingCommand(
                status = status,
                readiness = readiness,
            )

        ExternalAppCommandAction.StopRecording ->
            planStopRecordingCommand(
                status = status,
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
    status: ExternalAppCommandStatus,
    readiness: ExternalAppCommandReadiness,
): ExternalAppCommandExecutionPlan {
    if (!readiness.isRecording) {
        return ExternalAppCommandExecutionPlan(
            terminalResult = ExternalAppCommandTerminalResult.AlreadySatisfied,
        )
    }
    val editorStep = editorTargetStep(status = status, readiness = readiness) ?: return waitForEditor(status)
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
