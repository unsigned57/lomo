package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/*
 * Behavior Contract:
 * - Unit under test: input sheet markdown formatting insertion helpers.
 * - Owning layer: ui-components.
 * - Priority tier: P2.
 * - Capability: editor toolbar and tag commands transform text at the active TextFieldValue selection.
 *
 * Scenarios:
 * - Given selected text, when underline insertion runs, then the selection is wrapped with `<u>` tags.
 * - Given a collapsed cursor, when underline insertion runs, then empty underline tags are inserted at the cursor.
 * - Given a reversed selection, when underline insertion runs, then the selection is normalized before wrapping.
 * - Given a middle cursor, when tag insertion runs, then the tag is inserted at that cursor instead of the end.
 * - Given selected text, when tag insertion runs, then the selected range is replaced by the tag.
 * - Given empty text or an end cursor, when tag insertion runs, then the previous end-append result is preserved.
 *
 * - Observable outcomes: resulting editor text and resulting selection range after insertion.
 * - TDD proof: Fails before the fix because tag insertion receives only String text, so it appends to the end and cannot replace the selected range.
 * - Excludes: Compose toolbar layout, Android EditText focus behavior, and markdown render output.
 *
 * Test Change Justification:
 * - Reason category: InputSheetContent was restructured alongside LomoList animation registry and stepped picker changes.
 * - Old behavior/assertion being replaced: previous formatting insertion tests relied on older InputSheetContent layout.
 * - Why old assertion is no longer correct: the InputSheetContent surface changed with the broader ui-components refactoring.
 * - Coverage preserved by: all markdown formatting insertion scenarios retained.
 * - Why this is not fitting the test to the implementation: tests verify observable markdown insertion formatting, not internal widget layout.
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

        test("tag insertion uses the middle cursor as insertion point") {
            val result = buildTagInsertionValue(TextFieldValue("alpha omega", TextRange(6)), "tag")

            result.text shouldBe "alpha #tag omega"
            result.selection shouldBe TextRange(11)
        }

        test("tag insertion replaces the selected range and places cursor after tag") {
            val result = buildTagInsertionValue(TextFieldValue("alpha selected omega", TextRange(6, 14)), "tag")

            result.text shouldBe "alpha #tag omega"
            result.selection shouldBe TextRange(11)
        }

        test("tag insertion preserves empty and end cursor append behavior") {
            buildTagInsertionValue(TextFieldValue("", TextRange(0)), "tag") shouldBe
                TextFieldValue("#tag ", TextRange(5))

            buildTagInsertionValue(TextFieldValue("alpha", TextRange(5)), "tag") shouldBe
                TextFieldValue("alpha #tag ", TextRange(11))
        }
    }
}
