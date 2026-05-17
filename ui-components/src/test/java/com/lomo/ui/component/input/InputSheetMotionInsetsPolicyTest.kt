package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: InputSheet motion-stage inset policy.
 * - Behavior focus: collapsing the long-form sheet must start removing fullscreen status/navigation insets during the collapse, instead of waiting for compact settle and causing a second height drop.
 * - Observable outcomes: motion stages report whether they should retain expanded insets, with collapsing opting out while expanding and expanded keep them.
 * - Red phase: Fails before the fix because the inset target still follows expanded surface form, so collapsing keeps fullscreen insets until compact settle.
 * - Excludes: Compose animation frame timing, exact pixel deltas, and IME OEM differences.
 */
class InputSheetMotionInsetsPolicyTest : UiComponentsFunSpec() {
    init {
        test("collapsing does not keep expanded insets alive") {
        withClue("Collapsing should already target compact insets so the sheet does not shrink a second time after the tabs finish closing.") { (InputSheetMotionStage.Collapsing.usesExpandedInsets()) shouldBe false }
        }
    }

    init {
        test("expanding and expanded still keep fullscreen insets") {
        withClue("Expanding should keep fullscreen insets so the opening path still reaches the long-form surface cleanly.") { (InputSheetMotionStage.Expanding.usesExpandedInsets()) shouldBe true }
        withClue("Expanded should keep fullscreen insets because the long-form editor still occupies the fullscreen sheet form.") { (InputSheetMotionStage.Expanded.usesExpandedInsets()) shouldBe true }
        }
    }

    init {
        test("compact keeps compact inset policy") {
        withClue("Compact should not reintroduce fullscreen insets.") { (InputSheetMotionStage.Compact.usesExpandedInsets()) shouldBe false }
        }
    }
}
