package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: coordinates cross the editor boundary as validated geo links; owner: app; P1.
 * Scenarios:
 * - Given out-of-range or nonfinite coordinates, when parsed, then insertion is rejected.
 * - Given signed boundary coordinates, when parsed, then a valid geo link is produced.
 * - Given a selected draft range, when inserting coordinates, then only that range changes and undo restores it.
 * Observable outcomes: validation states, inserted Markdown bytes, selection and undo.
 * TDD proof: toolbar-red.log observes the missing coordinate capability; coordinate-green.log verifies its typed boundary.
 * Excludes: OS positioning accuracy and Android permission prompts.
 */
class MemoCoordinatesTest : AppFunSpec() {
    init {
        test("given invalid coordinates when parsed then insertion is rejected") {
            MemoCoordinates.parse("", "1") shouldBe MemoCoordinateInput.Incomplete
            listOf("91", "-90.01", "NaN", "Infinity", "invalid").forEach { latitude ->
                MemoCoordinates.parse(latitude, "1") shouldBe MemoCoordinateInput.InvalidLatitude
            }
            listOf("181", "-180.01", "NaN", "Infinity").forEach { longitude ->
                MemoCoordinates.parse("1", longitude) shouldBe MemoCoordinateInput.InvalidLongitude
            }
        }
        test("given signed limits and small decimals when parsed then links remain decimal geo coordinates") {
            val limits = MemoCoordinates.parse("-90", "180") as MemoCoordinateInput.Valid
            limits.coordinates.markdown shouldBe "[-90, 180](geo:-90,180)"
            val decimals = MemoCoordinates.parse("1e-7", "-0.0") as MemoCoordinateInput.Valid
            decimals.coordinates.markdown shouldBe "[0.0000001, 0](geo:0.0000001,0)"
        }
        test("given selected draft text when inserting then selection and undo stay atomic") {
            val controller = MemoEditorController()
            controller.openForCreate("before replace after")
            controller.updateInputValue(TextFieldValue(controller.inputValue.text, TextRange(7, 14)))
            val coordinates = (MemoCoordinates.parse("31.2304", "121.4737") as MemoCoordinateInput.Valid).coordinates
            controller.updateInputValue(buildMemoCoordinateInsertionValue(controller.inputValue, coordinates))
            controller.inputValue.text shouldBe "before [31.2304, 121.4737](geo:31.2304,121.4737) after"
            controller.inputValue.selection shouldBe TextRange(7 + coordinates.markdown.length)
            controller.undo()
            controller.inputValue.text shouldBe "before replace after"
        }
    }
}
