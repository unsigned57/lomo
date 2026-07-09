/*
 * Behavior Contract:
 * - Unit under test: external app command execution policy.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: decide whether a persisted external command should wait, request a prerequisite,
 *   run against the editor/recorder, or reach a terminal result.
 *
 * Scenarios:
 * - Given the main screen is not ready, when a create command is planned, then it waits for root
 *   readiness instead of being consumed.
 * - Given create memo has a visible editor, when planned, then it focuses the current editor and
 *   completes without replacing user input.
 * - Given create memo has no editor and cannot open a draft yet, when planned, then it waits for the
 *   editor target.
 * - Given start recording is missing voice storage, when planned from Pending, then it requests voice
 *   setup once and waits.
 * - Given start recording lacks microphone permission, when planned, then it opens/focuses the draft
 *   target, requests permission, and remains pending for the permission callback.
 * - Given start recording is already satisfied, when planned, then it completes idempotently.
 * - Given stop recording is already satisfied, when planned, then it completes idempotently.
 *
 * Observable outcomes: command plan steps, status updates, and terminal result.
 *
 * TDD proof:
 * - RED command: ./kotlin test --include-classes='com.lomo.app.feature.main.ExternalAppCommandExecutionPolicyTest'
 * - RED symptom: external command execution policy types do not exist.
 *
 * Excludes: Compose scheduling, Android permission launcher behavior, recorder I/O, and editor
 * rendering.
 */
package com.lomo.app.feature.main

import com.lomo.app.ExternalAppCommandAction
import com.lomo.app.ExternalAppCommandStatus
import com.lomo.app.ExternalAppCommandTerminalResult
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class ExternalAppCommandExecutionPolicyTest : AppFunSpec() {
    init {
        test("given main screen not ready when create planned then wait for root readiness") {
            planExternalAppCommandExecution(
                action = ExternalAppCommandAction.CreateMemo,
                status = ExternalAppCommandStatus.Pending,
                readiness = readiness(appReady = false),
            ) shouldBe
                ExternalAppCommandExecutionPlan(
                    statusUpdate = ExternalAppCommandStatus.WaitingForRoot,
                )
        }

        test("given visible editor when create planned then focus editor and complete") {
            planExternalAppCommandExecution(
                action = ExternalAppCommandAction.CreateMemo,
                status = ExternalAppCommandStatus.Pending,
                readiness = readiness(editorVisible = true),
            ) shouldBe
                ExternalAppCommandExecutionPlan(
                    steps = listOf(ExternalAppCommandStep.EnsureEditorVisible),
                    terminalResult = ExternalAppCommandTerminalResult.Executed,
                )
        }

        test("given no editor target when create planned then wait for editor target") {
            planExternalAppCommandExecution(
                action = ExternalAppCommandAction.CreateMemo,
                status = ExternalAppCommandStatus.Pending,
                readiness = readiness(editorVisible = false, canOpenDraftEditor = false),
            ) shouldBe
                ExternalAppCommandExecutionPlan(
                    statusUpdate = ExternalAppCommandStatus.WaitingForEditor,
                )
        }

        test("given missing voice storage when start planned then request setup and wait") {
            planExternalAppCommandExecution(
                action = ExternalAppCommandAction.StartRecording,
                status = ExternalAppCommandStatus.Pending,
                readiness = readiness(voiceDirectoryConfigured = false),
            ) shouldBe
                ExternalAppCommandExecutionPlan(
                    statusUpdate = ExternalAppCommandStatus.WaitingForVoiceDirectory,
                    steps = listOf(ExternalAppCommandStep.RequestVoiceDirectory),
                )
        }

        test("given command already waiting for voice storage when still missing then keep waiting") {
            planExternalAppCommandExecution(
                action = ExternalAppCommandAction.StartRecording,
                status = ExternalAppCommandStatus.WaitingForVoiceDirectory,
                readiness = readiness(voiceDirectoryConfigured = false),
            ) shouldBe ExternalAppCommandExecutionPlan()
        }

        test("given missing microphone permission when start planned then request permission and remain pending") {
            planExternalAppCommandExecution(
                action = ExternalAppCommandAction.StartRecording,
                status = ExternalAppCommandStatus.Pending,
                readiness =
                    readiness(
                        editorVisible = false,
                        hasRecordAudioPermission = false,
                    ),
            ) shouldBe
                ExternalAppCommandExecutionPlan(
                    statusUpdate = ExternalAppCommandStatus.WaitingForRecordAudioPermission,
                    steps =
                        listOf(
                            ExternalAppCommandStep.OpenDraftEditor,
                            ExternalAppCommandStep.RequestRecordAudioPermission,
                        ),
                )
        }

        test("given recording already active when start planned then complete idempotently") {
            planExternalAppCommandExecution(
                action = ExternalAppCommandAction.StartRecording,
                status = ExternalAppCommandStatus.Pending,
                readiness = readiness(isRecording = true),
            ) shouldBe
                ExternalAppCommandExecutionPlan(
                    terminalResult = ExternalAppCommandTerminalResult.AlreadySatisfied,
                )
        }

        test("given recording already stopped when stop planned then complete idempotently") {
            planExternalAppCommandExecution(
                action = ExternalAppCommandAction.StopRecording,
                status = ExternalAppCommandStatus.Pending,
                readiness = readiness(isRecording = false),
            ) shouldBe
                ExternalAppCommandExecutionPlan(
                    terminalResult = ExternalAppCommandTerminalResult.AlreadySatisfied,
                )
        }
    }
}

private fun readiness(
    appReady: Boolean = true,
    voiceDirectoryConfigured: Boolean = true,
    editorVisible: Boolean = false,
    canOpenDraftEditor: Boolean = true,
    hasRecordAudioPermission: Boolean = true,
    isRecording: Boolean = false,
): ExternalAppCommandReadiness =
    ExternalAppCommandReadiness(
        appReady = appReady,
        voiceDirectoryConfigured = voiceDirectoryConfigured,
        editorVisible = editorVisible,
        canOpenDraftEditor = canOpenDraftEditor,
        hasRecordAudioPermission = hasRecordAudioPermission,
        isRecording = isRecording,
    )
