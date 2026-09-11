/*
 * Behavior Contract:
 * - Unit under test: RecordingTileClickPolicy
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: map Quick Settings recording tile clicks to trusted Activity launch commands.
 *
 * Scenarios:
 * - Given the session is idle, when the tile is clicked, then the trusted Activity start-recording command is launched.
 * - Given the session is recording, when the tile is clicked, then the trusted Activity stop-recording command is launched.
 * - Given the tile observes session state, when presentation is resolved, then idle maps to Start and recording maps to Stop.
 *
 * Observable outcomes: TileClickAction returned by the policy.
 *
 * TDD proof:
 * - RED before implementation because the tile policy still accepted background-recording preconditions and could return direct recording actions.
 *
 * Excludes: TileService lifecycle, Android permission APIs, app-lock prompt UI, and RecordingSession side effects.
 */
package com.lomo.app.widget

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.RecordingSessionState
import io.kotest.matchers.shouldBe

class RecordingTileClickPolicyTest : AppFunSpec() {
    init {
        test("given idle session when clicked then launch trusted start recording activity command") {
            val action = RecordingTileClickPolicy().decide(RecordingSessionState.Idle)

            action shouldBe TileClickAction.LaunchStartRecording
        }

        test("given recording session when clicked then launch trusted stop recording activity command") {
            val action =
                RecordingTileClickPolicy().decide(
                    RecordingSessionState.Recording(
                        filename = "voice_20260702_100000.m4a",
                        startedAtMillis = 1_000L,
                    ),
                )

            action shouldBe TileClickAction.LaunchStopRecording
        }

        test("presentation marks idle tile inactive with start label and recording tile active with stop label") {
            RecordingTileClickPolicy().presentation(RecordingSessionState.Idle) shouldBe
                RecordingTilePresentation.Start

            RecordingTileClickPolicy().presentation(
                RecordingSessionState.Recording(
                    filename = "voice_20260702_100000.m4a",
                    startedAtMillis = 1_000L,
                ),
            ) shouldBe RecordingTilePresentation.Stop
        }
    }
}
