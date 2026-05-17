package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/*
 * Test Contract:
 * - Unit under test: input sheet markdown formatting insertion helpers.
 * - Behavior focus: underline toolbar actions must wrap the active selection with `<u>` tags, insert an empty pair at the cursor when nothing is selected, and normalize reversed selections without corrupting surrounding text.
 * - Observable outcomes: resulting editor text and resulting selection range after insertion.
 * - Red phase: Fails before the fix because the input sheet exposes no underline formatting insertion behavior, so selected text cannot be wrapped from the toolbar.
 * - Excludes: Compose toolbar layout, Android EditText focus behavior, and markdown render output.
 */
class InputSheetMarkdownFormattingInsertionTest : UiComponentsFunSpec() {
    init {
        test("underline insertion wraps the selected text and preserves inner selection") {
        val result =
            buildWrappedSelectionInsertionValue(
                inputValue = TextFieldValue("before focus after", TextRange(7, 12)),
                prefix = "<u>",
                suffix = "</u>",
            )

        (result.text) shouldBe ("before <u>focus</u> after")
        (result.selection) shouldBe (TextRange(10, 15))
        }
    }

    init {
        test("underline insertion at collapsed cursor inserts empty tags and places cursor inside") {
        val result =
            buildWrappedSelectionInsertionValue(
                inputValue = TextFieldValue("before after", TextRange(7)),
                prefix = "<u>",
                suffix = "</u>",
            )

        (result.text) shouldBe ("before <u></u>after")
        (result.selection) shouldBe (TextRange(10))
        }
    }

    init {
        test("underline insertion normalizes reversed selections") {
        val result =
            buildWrappedSelectionInsertionValue(
                inputValue = TextFieldValue("abcde", TextRange(4, 1)),
                prefix = "<u>",
                suffix = "</u>",
            )

        (result.text) shouldBe ("a<u>bcd</u>e")
        (result.selection) shouldBe (TextRange(4, 7))
        }
    }
}
