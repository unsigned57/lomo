/*
 * Behavior Contract:
 * - Unit under test: RecordingTileClickPolicy
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: map Quick Settings recording tile clicks to one command after enforcing entry preconditions.
 *
 * Scenarios:
 * - Given audio permission, voice workspace, and app-lock session are satisfied while idle, when the tile is clicked, then recording starts in place.
 * - Given audio permission, voice workspace, and app-lock session are satisfied while already recording, when the tile is clicked, then recording stops in place.
 * - Given any required precondition is missing, when the tile is clicked, then MainActivity is launched with StartRecording instead of background recording.
 *
 * Observable outcomes: TileClickAction returned by the policy.
 *
 * TDD proof:
 * - RED before implementation because RecordingTileClickPolicy, RecordingPreconditions, and TileClickAction do not exist.
 *
 * Excludes: TileService lifecycle, Android permission APIs, app-lock prompt UI, and RecordingSession side effects.
 */
package com.lomo.app.widget

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.RecordingSessionState
import io.kotest.matchers.shouldBe

class RecordingTileClickPolicyTest : AppFunSpec() {
    init {
        test("given all preconditions satisfied and session idle when clicked then start recording") {
            val action =
                RecordingTileClickPolicy().decide(
                    preconditions = satisfiedPreconditions(),
                    state = RecordingSessionState.Idle,
                )

            action shouldBe TileClickAction.StartRecording
        }

        test("given all preconditions satisfied and session recording when clicked then stop recording") {
            val action =
                RecordingTileClickPolicy().decide(
                    preconditions = satisfiedPreconditions(),
                    state =
                        RecordingSessionState.Recording(
                            filename = "voice_20260702_100000.m4a",
                            startedAtMillis = 1_000L,
                        ),
                )

            action shouldBe TileClickAction.StopRecording
        }

        test("given any precondition is missing when clicked then launch main activity start recording flow") {
            val missingPermission =
                satisfiedPreconditions().copy(hasRecordAudioPermission = false)
            val missingVoiceWorkspace =
                satisfiedPreconditions().copy(isVoiceWorkspaceReady = false)
            val appLocked =
                satisfiedPreconditions().copy(isAppLockSatisfied = false)

            listOf(missingPermission, missingVoiceWorkspace, appLocked).forEach { preconditions ->
                val action =
                    RecordingTileClickPolicy().decide(
                        preconditions = preconditions,
                        state = RecordingSessionState.Idle,
                    )

                action shouldBe TileClickAction.LaunchMainActivityWithStartRecording
            }
        }
    }
}

private fun satisfiedPreconditions(): RecordingPreconditions =
    RecordingPreconditions(
        hasRecordAudioPermission = true,
        isVoiceWorkspaceReady = true,
        isAppLockSatisfied = true,
    )
