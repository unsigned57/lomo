package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: MemoEditorController focus-request policy.
 * - Behavior focus: create, edit, and ensure-visible entry points must each emit a fresh focus request token, while pure content mutations must not request keyboard focus again.
 * - Observable outcomes: focusRequestToken progression across controller actions and unchanged token during content-only mutations.
 * - Red phase: Would fail before the fix because MemoEditorController had no explicit focus-request token, so open and ensure-visible flows could not re-trigger InputSheet focus for an active editor session.
 * - Excludes: Compose sheet rendering, IME activation timing, and memo persistence.
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

            controller.openForEdit(memo)
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
