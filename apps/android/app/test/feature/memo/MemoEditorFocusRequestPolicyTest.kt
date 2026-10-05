package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.verifiedEditSession
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoEditorController focus-request policy.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: create, edit, and ensure-visible entry points each emit a fresh focus request
 *   token, while pure content mutations never request keyboard focus again.
 *
 * Scenarios:
 * - Given an open-for-create or ensure-visible entry point, when the controller runs it, then the
 *   focusRequestToken advances.
 * - Given content-only mutations, when they run, then the focus request token stays unchanged.
 *
 * Observable outcomes: focusRequestToken progression across controller actions.
 *
 * TDD proof:
 * - Would fail before the fix because MemoEditorController had no explicit focus-request token,
 *   so open and ensure-visible flows could not re-trigger InputSheet focus for an active session.
 *
 * Excludes:
 * - Compose sheet rendering, IME activation timing, and memo persistence.
 *
 * Test Change Justification:
 * - Reason category: open-for-edit now requires a verified edit session.
 * - Old behavior/assertion being replaced: openForEdit consumed a bare Memo preview.
 * - Why old assertion is no longer correct: a preview cannot become an edit baseline; the
 *   controller only opens a MemoEditSession verified through the full-snapshot gate.
 * - Coverage preserved by: identical token-progression scenarios via verifiedEditSession helper.
 * - Why this is not fitting the test to the implementation: assertions check token outcomes,
 *   not the session's verification internals.
 */
class MemoEditorFocusRequestPolicyTest : AppFunSpec() {
    init {
        test("open and ensure visible entry points advance the focus request token") {
            val controller = MemoEditorController()
            val memo =
                Memo(
                    id = "memo-1",
                    timestamp = 10L,
                    content = "existing body",
                    rawContent = "- 10:00 existing body",
                    dateKey = "2026_03_26",
                )

            (controller.focusRequestToken) shouldBe (0L)

            controller.openForCreate("draft")
            (controller.focusRequestToken) shouldBe (1L)

            controller.openForEdit(memo.verifiedEditSession())
            (controller.focusRequestToken) shouldBe (2L)

            controller.ensureVisible()
            (controller.focusRequestToken) shouldBe (3L)
        }
    }

    init {
        test("content only mutations keep the current focus request token") {
            val controller = MemoEditorController()

            controller.openForCreate("draft")
            val initialToken = controller.focusRequestToken

            controller.appendMarkdownBlock("- [ ] todo")
            controller.appendImageMarkdown("images/cover.jpg")
            controller.updateInputValue(TextFieldValue("manual", TextRange(2)))

            (controller.focusRequestToken) shouldBe (initialToken)
        }
    }

}
