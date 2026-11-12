/*
 * Behavior Contract:
 * Capability: finalized recordings retain the cursor while recording UI returns to editing; owner app; P1.
 * Scenarios:
 * - Given a mid-text cursor or reversed selection, when finalization restores editor focus, then the recording inserts at the captured selection.
 * - Given text edited during finalization, when media completes, then current edits and active selection are retained.
 * Observable outcomes: resulting text, selection and undo restoration.
 * TDD proof: target/lomo/reports/manual/interaction-fixes/recording-{red,green}.log.
 * Excludes: microphone hardware, media storage and activity lifecycle.
 */
package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class MemoRecordingInsertionTest : AppFunSpec() {
    init {
        test("given a middle cursor when editor remounts before audio finalizes then insertion retains the cursor") {
            val controller = MemoEditorController()
            controller.openForCreate("before after")
            controller.updateInputValue(TextFieldValue("before after", TextRange(6)))
            val insertRecording = controller.captureMarkdownInsertion()
            controller.updateInputValue(TextFieldValue("before after", TextRange(12)))
            insertRecording("![voice](audio.m4a)")
            controller.inputValue.text shouldBe "before\n![voice](audio.m4a)\n after"
            controller.inputValue.selection shouldBe TextRange("before\n![voice](audio.m4a)\n".length)
            controller.undo()
            controller.inputValue.text shouldBe "before after"
        }
        test("given a reversed selection when recording completes then selected text is replaced") {
            val controller = MemoEditorController()
            controller.openForCreate("before selected after")
            controller.updateInputValue(TextFieldValue("before selected after", TextRange(15, 7)))
            val insertRecording = controller.captureMarkdownInsertion()
            controller.updateInputValue(TextFieldValue("before selected after", TextRange(21)))
            insertRecording("![voice](audio.m4a)")
            controller.inputValue.text shouldBe "before \n![voice](audio.m4a)\n after"
        }
        test("given new text typed during finalization when recording completes then current text and selection win") {
            val controller = MemoEditorController()
            controller.openForCreate("before after")
            val insertRecording = controller.captureMarkdownInsertion()
            controller.updateInputValue(TextFieldValue("before new after", TextRange(7)))
            insertRecording("![voice](audio.m4a)")
            controller.inputValue.text shouldBe "before \n![voice](audio.m4a)\nnew after"
        }
    }
}
