package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: coherent ordered draft edits; owning layer: app editor; priority: P1.
 * Scenarios:
 * - Given adjacent ordered items, when Enter inserts an item, then later siblings renumber.
 * - Given nested items and a subsequent separate list, when inserting, then only siblings change.
 * - Given fenced code, when Enter follows a number, then source remains literal.
 * - Given undo, when the insertion is undone, then numbering and selection restore together.
 * Observable outcomes: draft bytes, cursor, undo and redo snapshots.
 * TDD proof: RED/GREEN ./kotlin test --include-module=app
 * --include-classes=com.lomo.app.feature.memo.MemoEditorListEditingTest (ui-bugs/app-red.log and app-green.log).
 * Excludes: durable document parsing and IME hardware.
 */
class MemoEditorListEditingTest : AppFunSpec() {
    init {
        test("given ordered siblings when inserting in the middle then numbering and undo stay coherent") {
            val controller = MemoEditorController()
            controller.openForCreate("1. first\n2. second\n3. third")
            controller.updateInputValue(TextFieldValue("1. first\n\n2. second\n3. third", TextRange(9)))
            controller.inputValue.text shouldBe "1. first\n2. \n3. second\n4. third"
            controller.inputValue.selection shouldBe TextRange(12)
            controller.undo()
            controller.inputValue.text shouldBe "1. first\n2. second\n3. third"
            controller.redo()
            controller.inputValue.text shouldBe "1. first\n2. \n3. second\n4. third"
        }
        test("given nested and separate lists when inserting then renumber only the current sibling run") {
            val controller = MemoEditorController()
            controller.openForCreate("9. first\n10. second\n    1. child\n11. third\n\noutside\n\n1. separate")
            controller.updateInputValue(
                TextFieldValue(
                    "9. first\n\n10. second\n    1. child\n11. third\n\noutside\n\n1. separate",
                    TextRange(9),
                ),
            )
            controller.inputValue.text shouldBe
                "9. first\n10. \n11. second\n    1. child\n12. third\n\noutside\n\n1. separate"
            controller.inputValue.selection shouldBe TextRange(13)
        }
        test("given a code fence when entering after an ordered looking line then no list edit occurs") {
            val controller = MemoEditorController()
            controller.openForCreate("```\n1. literal")
            val entered = "```\n1. literal\n"
            controller.updateInputValue(TextFieldValue(entered, TextRange(entered.length)))
            controller.inputValue.text shouldBe entered
        }
    }
}
