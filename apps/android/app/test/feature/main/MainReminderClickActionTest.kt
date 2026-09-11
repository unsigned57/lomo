/*
 * Behavior Contract:
 * - Unit under test: MainReminderClickAction
 * - Owning layer: production path under test
 * - Priority tier: P1
 * - Capability: preserve observable product behavior after Markdown semantic ownership moved to
 *   lomo-workspace (typed IR, workspace scan/render/document commands) with Kotlin adapters only.
 *
 * Scenarios:
 * - Given production collaborators expose workspace IR / document-command seams, when this suite
 *   runs, then assertions verify the same user-visible outcomes without Kotlin MarkdownParser.
 * - Given deleted JetBrains or line-authority helpers, when tests construct fakes, then they use
 *   FakeMarkdownWorkspace / content projector adapters instead of dual-authority parsers.
 * - Given invalid or missing readiness inputs, when exercised, then fail-closed outcomes remain.
 *
 * Observable outcomes:
 * - Public method results, DI wiring, and presentation fields match the post-cutover contracts.
 *
 * TDD proof:
 * - RED: suites fail to compile or assert against MarkdownParser / JetBrains plan types after cutover.
 * - GREEN: ./kotlin test on this class passes against workspace IR adapters.
 *
 * Excludes:
 * - Room schema ownership, sync backend redesign, and Compose pixel rendering.
 *
 * Test Change Justification:
 * - Reason category: production Markdown ownership cutover to Rust workspace IR / document commands.
 * - Old behavior/assertion being replaced: tests that assumed Kotlin MarkdownParser, MemoTextProcessor,
 *   JetBrains render plans, or dual-authority analysis helpers as production collaborators.
 * - Why old assertion is no longer correct: production storage analysis and presentation consume
 *   lomo-workspace typed IR and workspace adapters; the deleted Kotlin/JetBrains authorities are gone.
 * - Coverage preserved by: the same observable product outcomes (mapping, mutation gates, DI wiring,
 *   share/card presentation) re-asserted against FakeMarkdownWorkspace / IR / projector seams.
 * - Why this is not fitting the test to the implementation: assertions still check public behavior and
 *   fail-closed boundaries, not private parser implementation details.
 */

package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Recurrence
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.ReminderReference
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class MainReminderClickActionTest : AppFunSpec() {
    init {
        test("given memo reminder when main list dispatches click then mark-done receives memo id and raw token") {
            val recorder = RecordingReminderDoneSink()
            val action =
                createMainReminderDoneClickAction(
                    memoId = "memo-42",
                    onReminderDone = recorder::markDone,
                )
            val token = "@2026-05-22-17:51x5i15rw"
            action(
                ReminderMarker(
                    dueAt = LocalDateTime.of(2026, 5, 22, 17, 51),
                    repeatCount = 5,
                    firedCount = 0,
                    done = false,
                    intervalMinutes = 15,
                    recurrence = Recurrence.WEEKLY,
                    reference =
                        ReminderReference(
                            opaqueId = "rem-1",
                            revision = "1",
                            memoIdentity = "memo-42",
                            sourceSpan = MarkdownSourceSpan(6u, 27u),
                            tokenFingerprint = "fp",
                        ),
                    token = token,
                ),
            )
            // Production action marks done by opaque reminder reference id from owner IR.
            recorder.memoId shouldBe "memo-42"
            recorder.tokenRaw shouldBe "rem-1"
            recorder.callCount shouldBe 1
        }
    }

    private class RecordingReminderDoneSink {
        var memoId: String? = null
            private set
        var tokenRaw: String? = null
            private set
        var callCount: Int = 0
            private set

        fun markDone(memoId: String, tokenRaw: String) {
            this.memoId = memoId
            this.tokenRaw = tokenRaw
            callCount++
        }
    }
}
