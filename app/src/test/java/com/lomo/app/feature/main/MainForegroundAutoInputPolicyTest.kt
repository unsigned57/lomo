package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: Main foreground auto-input policy.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: converts a user-enabled foreground-entry preference into one main-screen editor
 *   command without interrupting explicit app entry paths.
 *
 * Scenarios:
 * - Given the preference is enabled and Main is ready, when a new foreground entry arrives, then
 *   the draft editor opens.
 * - Given the editor is already visible, when a foreground entry arrives, then only a refocus is
 *   requested.
 * - Given an explicit share/open/command entry is pending, when a foreground entry arrives, then
 *   auto-input is suppressed for that entry.
 * - Given Main is not ready yet, when a foreground entry arrives, then the policy waits instead of
 *   consuming the entry.
 *
 * Observable outcomes:
 * - Resolved MainForegroundAutoInputPolicy values.
 *
 * TDD proof:
 * - RED: foreground auto-input policy does not exist before this feature.
 *
 * Excludes:
 * - Compose lifecycle dispatch, keyboard rendering, DataStore persistence, and settings UI.
 */
class MainForegroundAutoInputPolicyTest : AppFunSpec() {
    init {
        test("given enabled ready main when foreground entry is new then draft editor opens") {
            val decision =
                resolveMainForegroundAutoInputDecision(
                    foregroundEntryId = 7L,
                    handledForegroundEntryId = 6L,
                    enabled = true,
                    isReady = true,
                    explicitEntryPending = false,
                    editorVisible = false,
                    isRecording = false,
                    hasPendingNewMemoCreation = false,
                )

            decision shouldBe MainForegroundAutoInputPolicy.OpenDraftEditor
        }

        test("given visible editor when foreground entry is new then editor is refocused") {
            val decision =
                resolveMainForegroundAutoInputDecision(
                    foregroundEntryId = 7L,
                    handledForegroundEntryId = 6L,
                    enabled = true,
                    isReady = true,
                    explicitEntryPending = false,
                    editorVisible = true,
                    isRecording = false,
                    hasPendingNewMemoCreation = false,
                )

            decision shouldBe MainForegroundAutoInputPolicy.RefocusEditor
        }

        test("given explicit entry or busy editor state when foreground entry is new then auto input is suppressed") {
            val explicitEntryDecision =
                resolveMainForegroundAutoInputDecision(
                    foregroundEntryId = 7L,
                    handledForegroundEntryId = 6L,
                    enabled = true,
                    isReady = true,
                    explicitEntryPending = true,
                    editorVisible = false,
                    isRecording = false,
                    hasPendingNewMemoCreation = false,
                )
            val recordingDecision =
                resolveMainForegroundAutoInputDecision(
                    foregroundEntryId = 7L,
                    handledForegroundEntryId = 6L,
                    enabled = true,
                    isReady = true,
                    explicitEntryPending = false,
                    editorVisible = false,
                    isRecording = true,
                    hasPendingNewMemoCreation = false,
                )
            val pendingCreationDecision =
                resolveMainForegroundAutoInputDecision(
                    foregroundEntryId = 7L,
                    handledForegroundEntryId = 6L,
                    enabled = true,
                    isReady = true,
                    explicitEntryPending = false,
                    editorVisible = false,
                    isRecording = false,
                    hasPendingNewMemoCreation = true,
                )

            explicitEntryDecision shouldBe MainForegroundAutoInputPolicy.Suppress
            recordingDecision shouldBe MainForegroundAutoInputPolicy.Suppress
            pendingCreationDecision shouldBe MainForegroundAutoInputPolicy.Suppress
        }

        test("given not ready main when foreground entry is new then policy waits without consuming") {
            val decision =
                resolveMainForegroundAutoInputDecision(
                    foregroundEntryId = 7L,
                    handledForegroundEntryId = 6L,
                    enabled = true,
                    isReady = false,
                    explicitEntryPending = false,
                    editorVisible = false,
                    isRecording = false,
                    hasPendingNewMemoCreation = false,
                )

            decision shouldBe MainForegroundAutoInputPolicy.WaitForReady
        }

        test("given disabled or already handled foreground entry then no editor command is requested") {
            val disabledDecision =
                resolveMainForegroundAutoInputDecision(
                    foregroundEntryId = 7L,
                    handledForegroundEntryId = 6L,
                    enabled = false,
                    isReady = true,
                    explicitEntryPending = false,
                    editorVisible = false,
                    isRecording = false,
                    hasPendingNewMemoCreation = false,
                )
            val handledDecision =
                resolveMainForegroundAutoInputDecision(
                    foregroundEntryId = 7L,
                    handledForegroundEntryId = 7L,
                    enabled = true,
                    isReady = true,
                    explicitEntryPending = false,
                    editorVisible = false,
                    isRecording = false,
                    hasPendingNewMemoCreation = false,
                )

            disabledDecision shouldBe MainForegroundAutoInputPolicy.Suppress
            handledDecision shouldBe MainForegroundAutoInputPolicy.Ignore
        }
    }
}
