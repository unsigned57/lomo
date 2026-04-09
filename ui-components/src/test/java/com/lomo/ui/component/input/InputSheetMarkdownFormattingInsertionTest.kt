package com.lomo.ui.component.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: input sheet markdown formatting insertion helpers.
 * - Behavior focus: underline toolbar actions must wrap the active selection with `<u>` tags, insert an empty pair at the cursor when nothing is selected, and normalize reversed selections without corrupting surrounding text.
 * - Observable outcomes: resulting editor text and resulting selection range after insertion.
 * - Red phase: Fails before the fix because the input sheet exposes no underline formatting insertion behavior, so selected text cannot be wrapped from the toolbar.
 * - Excludes: Compose toolbar layout, Android EditText focus behavior, and markdown render output.
 */
class InputSheetMarkdownFormattingInsertionTest {
    @Test
    fun `underline insertion wraps the selected text and preserves inner selection`() {
        val result =
            buildWrappedSelectionInsertionValue(
                inputValue = TextFieldValue("before focus after", TextRange(7, 12)),
                prefix = "<u>",
                suffix = "</u>",
            )

        assertEquals("before <u>focus</u> after", result.text)
        assertEquals(TextRange(10, 15), result.selection)
    }

    @Test
    fun `underline insertion at collapsed cursor inserts empty tags and places cursor inside`() {
        val result =
            buildWrappedSelectionInsertionValue(
                inputValue = TextFieldValue("before after", TextRange(7)),
                prefix = "<u>",
                suffix = "</u>",
            )

        assertEquals("before <u></u>after", result.text)
        assertEquals(TextRange(10), result.selection)
    }

    @Test
    fun `underline insertion normalizes reversed selections`() {
        val result =
            buildWrappedSelectionInsertionValue(
                inputValue = TextFieldValue("abcde", TextRange(4, 1)),
                prefix = "<u>",
                suffix = "</u>",
            )

        assertEquals("a<u>bcd</u>e", result.text)
        assertEquals(TextRange(4, 7), result.selection)
    }
}
