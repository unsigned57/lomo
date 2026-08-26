package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: input-sheet submission acceptance.
 * - Owning layer: ui-components input/editor surface.
 * - Priority tier: P1.
 * - Capability: one enabled send intent starts exactly one submission before presentation cleanup.
 *
 * Scenarios:
 * - Given non-blank content and an idle editor, when send is accepted, then focus cleanup and
 *   visual withdrawal finish before durable submission starts on the next scheduler turn.
 * - Given the same send intent while submission is active, when it is delivered again, then no
 *   duplicate submission starts.
 * - Given a submission is accepted, when durable work is still pending, then the editor surface
 *   is withdrawn immediately without waiting for the commit.
 * - Given durable work fails, when the terminal result arrives, then the withdrawn editor surface
 *   is restored with its submission lock released.
 *
 * Observable outcomes:
 * - accepted result, submission/focus event order, active lock state, and submit invocation count.
 *
 * TDD proof:
 * - RED on 2026-08-25: InputSheet delayed the submission callback until after focus release,
 *   keyboard animation delay, and another frame; no atomic acceptance entrypoint existed.
 * - RED on 2026-08-26: focus was released immediately but the editor surface remained visible
 *   until the durable commit completed.
 *
 * Excludes:
 * - Compose rendering, keyboard implementation details, and durable memo storage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InputSheetSubmissionTest : UiComponentsFunSpec() {
    init {
        test("given idle editor when send is accepted then withdrawal precedes durable submission") {
            runTest {
                val acknowledgement = CompletableDeferred<Boolean>()
                val events = mutableListOf<String>()
                val session =
                    InputSheetSessionState(initialInputText = "draft").apply {
                        isSheetVisible = true
                    }

                val accepted =
                    submitInputSheetContent(
                        sessionState = session,
                        content = "draft",
                        triggerText = "draft",
                        sourceText = "draft",
                        scope = this,
                        releaseFocus = { events += "focus" },
                        onSubmit = {
                            events += "submit"
                            acknowledgement.await()
                        },
                    )

                accepted shouldBe true
                events shouldContainExactly listOf("focus")
                session.isSubmitting shouldBe true
                session.isSheetVisible shouldBe false

                runCurrent()
                events shouldContainExactly listOf("focus", "submit")

                submitInputSheetContent(
                    sessionState = session,
                    content = "draft",
                    triggerText = "draft",
                    sourceText = "draft",
                    scope = this,
                    releaseFocus = { events += "duplicate-focus" },
                    onSubmit = {
                        events += "duplicate-submit"
                        true
                    },
                ) shouldBe false
                events shouldContainExactly listOf("focus", "submit")

                acknowledgement.complete(false)
                runCurrent()
                session.isSubmitting shouldBe false
                session.isSheetVisible shouldBe true
            }
        }
    }
}
