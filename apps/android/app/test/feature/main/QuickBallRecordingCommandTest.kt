package com.lomo.app.feature.main

import com.lomo.app.ExternalAppCommand
import com.lomo.app.ExternalAppCommandAction
import com.lomo.app.ExternalAppCommandSource
import com.lomo.app.ExternalAppCommandStatus
import com.lomo.app.ExternalAppCommandTerminalResult
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: route gesture-ball recording through the existing permission/directory state machine;
 * owning layer: app; priority: P1.
 * Scenarios:
 * - Given no audio permission or voice directory, when recording is requested, then only the
 *   prerequisite steps run and the recording is not started prematurely.
 * - Given an existing editor, when prerequisites are satisfied, then it is reused without reseeding.
 * - Given a recording already in progress, when requested again, then no second capture starts.
 * Observable outcomes: ordered execution steps, waiting statuses and terminal result.
 * TDD proof: quick-ball feature RED is recorded in QuickBallConfigTest; these scenarios exercise
 * the existing recording planner with the newly introduced QuickBall command source under GREEN.
 * GREEN: ./kotlin test --include-module=app --include-module=data --include-module=domain
 * --include-module=ui-components; all 2598 discovered tests passed (including both domain targets),
 * evidence: /tmp/lomo-quick-ball-final-tests.stdout.
 * Excludes: OS permission dialogs, capture hardware and the caller's command-id minting.
 */
class QuickBallRecordingCommandTest : AppFunSpec() {
    init {
        test(
            "given no microphone permission when recording is requested then the editor and permission prompt precede capture",
        ) {
            val plan = planExternalAppCommandExecution(
                quickBallRecordingCommand(),
                recordingReadiness(permission = false),
            )
            plan.statusUpdate shouldBe ExternalAppCommandStatus.WaitingForRecordAudioPermission
            plan.steps shouldBe listOf(
                ExternalAppCommandStep.OpenDraftEditor,
                ExternalAppCommandStep.RequestRecordAudioPermission,
            )
            plan.terminalResult shouldBe null
        }

        test("given no voice directory when recording is requested then directory setup is required before capture") {
            val plan = planExternalAppCommandExecution(
                quickBallRecordingCommand(),
                recordingReadiness().copy(voiceDirectoryConfigured = false),
            )
            plan.statusUpdate shouldBe ExternalAppCommandStatus.WaitingForVoiceDirectory
            plan.steps shouldBe listOf(ExternalAppCommandStep.RequestVoiceDirectory)
        }

        test("given an existing editor when recording is requested then its draft is reused") {
            val plan = planExternalAppCommandExecution(
                quickBallRecordingCommand(),
                recordingReadiness().copy(editorVisible = true),
            )
            plan.steps shouldBe listOf(
                ExternalAppCommandStep.EnsureEditorVisible,
                ExternalAppCommandStep.StartRecording,
            )
            plan.terminalResult shouldBe ExternalAppCommandTerminalResult.Executed
        }

        test("given capture is already running when recording is requested again then a second capture never starts") {
            val plan = planExternalAppCommandExecution(
                quickBallRecordingCommand(),
                recordingReadiness().copy(isRecording = true),
            )
            plan.steps shouldBe emptyList()
            plan.terminalResult shouldBe ExternalAppCommandTerminalResult.AlreadySatisfied
        }
    }
}

private fun quickBallRecordingCommand(): ExternalAppCommand = ExternalAppCommand(
    id = "gesture-recording",
    action = ExternalAppCommandAction.StartRecording,
    source = ExternalAppCommandSource.QuickBall,
    status = ExternalAppCommandStatus.Pending,
    createdAtMillis = 1L,
    expiresAtMillis = 1_000L,
)

private fun recordingReadiness(permission: Boolean = true): ExternalAppCommandReadiness = ExternalAppCommandReadiness(
    appReady = true,
    voiceDirectoryConfigured = true,
    editorVisible = false,
    canOpenDraftEditor = true,
    hasRecordAudioPermission = permission,
    isRecording = false,
)
