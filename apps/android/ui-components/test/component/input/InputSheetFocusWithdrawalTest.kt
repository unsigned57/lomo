package com.lomo.ui.component.input

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.SoftwareKeyboardController
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: an accepted send revokes pending IME requests before the next frame;
 * owning layer: ui-components input lifecycle; priority: P1.
 * Scenarios:
 * - Given focus awaiting its next frame, when send withdraws the sheet, then no keyboard show occurs.
 * - Given an active editor, when focus succeeds, then the keyboard shows once and retries finish.
 * Observable outcomes: keyboard show events, request completion and submission withdrawal.
 * TDD proof: reported-bugs/focus-red.log shows a stale keyboard show after send;
 * focus-green.log verifies revocation and successful focus.
 * Excludes: Android IME rendering and animation duration.
 * Test Change Justification:
 * Reason category: additional observable-result coverage.
 * Old behavior/assertion being replaced: none; keyboard events and completion remain asserted.
 * Why old assertion is no longer correct: existing assertions remain correct but did not check
 * the successful focus request's returned Boolean.
 * Coverage preserved by: unchanged withdrawal/keyboard assertions plus the successful result check.
 * Why this is not fitting the test to the implementation: successful focus must report success
 * as well as display the keyboard; no existing failure or cancellation expectation is weakened.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InputSheetFocusWithdrawalTest : UiComponentsFunSpec() {
    init {
        test("given pending focus when send withdraws before the next frame then keyboard stays hidden") {
            runTest {
                val frames = BroadcastFrameClock()
                val events = mutableListOf<String>()
                val focus = mockk<FocusRequester>()
                every { focus.requestFocus(any()) } returns true
                val keyboard = mockk<SoftwareKeyboardController>()
                every { keyboard.show() } answers { events += "show" }
                val session = InputSheetSessionState("draft").apply { isSheetVisible = true }
                val request = launch(frames) {
                    requestEditorFocusAndKeyboard(focus, keyboard) {
                        session.isSheetVisible && !session.isDismissing
                    }
                }
                runCurrent()
                submitInputSheetContent(session, "draft", "draft", "draft", this, {}, { true }) shouldBe true
                frames.sendFrame(1L)
                runCurrent()
                events shouldBe emptyList()
                request.isCompleted shouldBe true
            }
        }
        test("given active editor when focus succeeds then keyboard shows once without repeated requests") {
            runTest {
                val frames = BroadcastFrameClock()
                val events = mutableListOf<String>()
                val focus = mockk<FocusRequester>()
                every { focus.requestFocus(any()) } returns true
                val keyboard = mockk<SoftwareKeyboardController>()
                every { keyboard.show() } answers { events += "show" }
                var shownResult: Boolean? = null
                val request = launch(frames) {
                    shownResult = requestEditorFocusAndKeyboard(focus, keyboard) { true }
                }
                runCurrent()
                frames.sendFrame(1L)
                runCurrent()
                events shouldBe listOf("show")
                shownResult shouldBe true
                request.isCompleted shouldBe true
            }
        }
    }
}
