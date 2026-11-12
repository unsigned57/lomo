package com.lomo.ui.component.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: hidden editor metadata remains an atomic source-backed edit; owner: ui-components; priority: P1.
 * Scenarios:
 * - Given hidden metadata, when displaying, then only visible text is projected by the framework output transformation.
 * - Given backspace touching metadata, when editing, then the complete hidden atom is removed.
 * - Given stale or overlapping ranges, when projecting/removing text, then invalid input is rejected.
 * Observable outcomes: visible text, cursor offsets, edited source and classified boundary rejection.
 * TDD proof: device reminder_capsules RED lacks the badge after confirming; host GREEN verifies source preservation.
 * Excludes: grammar recognition, durable reminder scheduling and IME implementation details.
 */
class InputEditorHiddenTextTest : UiComponentsFunSpec() {
    init {
        test("given hidden metadata when displaying then only visible text is projected") {
            val source = "a<one>b<two>c"
            inputEditorVisibleText(source, listOf(TextRange(1, 6), TextRange(7, 12))) shouldBe "abc"
        }
        test("given backspace inside hidden metadata when editing then the entire token disappears") {
            val previous = TextFieldValue("hello<token>", TextRange(12))
            val proposed = TextFieldValue("hello<token", TextRange(11))
            val result = applyInputHiddenTextEdit(previous, proposed, listOf(TextRange(5, 12)))
            result.text shouldBe "hello"
            result.selection shouldBe TextRange(5)
        }
        test("given an empty visible editor with metadata when typing then the token retains a separator") {
            val previous = TextFieldValue("<token>", TextRange(7))
            val proposed = TextFieldValue("<token>body", TextRange(11))
            val result = applyInputHiddenTextEdit(previous, proposed, listOf(TextRange(0, 7)))
            result.text shouldBe "<token> body"
            result.selection shouldBe TextRange(12)
        }
        test("given stale source or overlapping spans when applying then the boundary rejects them") {
            shouldThrow<IllegalArgumentException> {
                removeInputTextRange(TextFieldValue("new text"), "old text", TextRange(0, 3))
            }
            shouldThrow<IllegalArgumentException> {
                inputEditorVisibleText("text", listOf(TextRange(0, 3), TextRange(2, 4)))
            }
        }
        test("given adjacent metadata lines when hidden then coalescing retains a valid projection") {
            val ranges = mergeInputHiddenRanges(listOf(TextRange(0, 3), TextRange(2, 5)))
            inputEditorVisibleText("12345body", ranges) shouldBe "body"
        }
    }
}
