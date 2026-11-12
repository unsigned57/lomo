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
 *   visual withdrawal finish and durable submission starts before the next scheduler turn.
 * - Given the same send intent while submission is active, when it is delivered again, then no
 *   duplicate submission starts.
 * - Given a submission is accepted, when durable work is still pending, then the editor surface
 *   is withdrawn immediately without waiting for the commit.
 * - Given durable work fails, when the terminal result arrives, then the withdrawn editor surface
 *   is restored with its submission lock released.
 * - Given an accepted submission, when the owner reports a durable commit, then onCommitted fires
 *   exactly once; a rejected or failed submission never fires it.
 *
 * Observable outcomes:
 * - accepted result, submission/focus event order, active lock state, submit invocation count,
 *   and committed-callback invocations.
 *
 * TDD proof:
 * - RED on 2026-08-25: InputSheet delayed the submission callback until after focus release,
 *   keyboard animation delay, and another frame; no atomic acceptance entrypoint existed.
 * - RED on 2026-08-26: focus was released immediately but the editor surface remained visible
 *   until the durable commit completed.
 * - RED on 2026-10-14: durable commit status and onCommitted callback did not exist, so the
 *   commit/reject/exception scenarios below could not even compile against the old signature.
 *
 * Test Change Justification (send latency):
 * Reason category: remove the scheduler turn between accepted send and durable submission.
 * Old assertion being replaced: only focus cleanup had run when acceptance returned.
 * Why old assertion is no longer correct: accepted sends must start work alongside withdrawal,
 * without queuing behind another UI frame.
 * Coverage preserved by: exact focus-before-submit order, suspended commit, duplicate rejection,
 * restoration on rejection/failure and committed-once assertions all remain.
 * Why this is not fitting the test to the implementation: the controlled suspended acknowledgement
 * distinguishes starting submission immediately from completing it or showing a speculative memo.
 * TDD proof: input-immediate-red.log records missing submit before scheduler advancement.
 * Evidence root: target/lomo/reports/manual/four-bugs/.
 * Test Change Justification:
 * - Reason category: production behavior change — `submitInputSheetContent` gained a durable
 *   Boolean result and a one-shot `onCommitted` callback for the autosave snackbar.
 * - Old behavior/assertion being replaced: `onSubmit` returned Unit and no callback existed;
 *   no assertion could distinguish durable success from rejection.
 * - Why old assertion is no longer correct: the old signature cannot express the committed
 *   contract the four snackbar hosts now depend on; the tests must observe the callback.
 * - Coverage preserved by: the original acceptance/ordering/lock assertions are unchanged;
 *   the three new scenarios add committed-once, rejection-silent and exception-silent cases.
 * - Why this is not fitting the test to the implementation: the RED came first — the new
 *   scenarios failed to compile/run before the production signature change, and rejection
 *   and exception paths are asserted to never invoke the callback.
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
                events shouldContainExactly listOf("focus", "submit")
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

        test("given accepted submission when durable commit arrives then onCommitted fires once") {
            runTest {
                val events = mutableListOf<String>()
                val session = InputSheetSessionState(initialInputText = "draft")

                submitInputSheetContent(
                    sessionState = session,
                    content = "draft",
                    triggerText = "draft",
                    sourceText = "draft",
                    scope = this,
                    releaseFocus = {},
                    onSubmit = { true },
                    onCommitted = { events += "committed" },
                )
                runCurrent()

                events shouldContainExactly listOf("committed")
                session.isSubmitting shouldBe true
            }
        }

        test("given rejected submission when terminal result arrives then onCommitted never fires") {
            runTest {
                val events = mutableListOf<String>()
                val session =
                    InputSheetSessionState(initialInputText = "draft").apply {
                        isSheetVisible = true
                    }

                submitInputSheetContent(
                    sessionState = session,
                    content = "draft",
                    triggerText = "draft",
                    sourceText = "draft",
                    scope = this,
                    releaseFocus = {},
                    onSubmit = { false },
                    onCommitted = { events += "committed" },
                )
                runCurrent()

                events shouldContainExactly emptyList()
                session.isSubmitting shouldBe false
                session.isSheetVisible shouldBe true
            }
        }

        test("given failing submission when exception propagates then onCommitted never fires") {
            runTest {
                val events = mutableListOf<String>()
                val session =
                    InputSheetSessionState(initialInputText = "draft").apply {
                        isSheetVisible = true
                    }
                val failureScope =
                    kotlinx.coroutines.CoroutineScope(
                        coroutineContext +
                            kotlinx.coroutines.SupervisorJob() +
                            kotlinx.coroutines.CoroutineExceptionHandler { _, _ ->
                                events += "failure"
                            },
                    )

                submitInputSheetContent(
                    sessionState = session,
                    content = "draft",
                    triggerText = "draft",
                    sourceText = "draft",
                    scope = failureScope,
                    releaseFocus = {},
                    onSubmit = { throw IllegalStateException("write failed") },
                    onCommitted = { events += "committed" },
                )
                runCurrent()

                events shouldContainExactly listOf("failure")
                session.isSubmitting shouldBe false
                session.isSheetVisible shouldBe true
            }
        }
    }
}
