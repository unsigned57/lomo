package com.lomo.ui.component.input

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: Input editor preview content selection policy.
 * - Behavior focus: expanded preview should prefer externally resolved preview content and fall
 *   back to raw editor text only when no override is provided.
 * - Observable outcomes: resolved preview text string chosen for markdown rendering.
 * - Red phase: Fails before the fix because preview mode always rendered `inputValue.text` and had
 *   no override path for pre-resolved image markdown content.
 * - Excludes: Compose animation timing, markdown parser behavior, and image loading pipeline.
 */
class InputEditorPreviewContentPolicyTest : UiComponentsFunSpec() {
    init {
        test("preview content override has priority over raw input text") {
        val resolved =
            resolveInputEditorPreviewContent(
                inputText = "![image](img_001.jpg)",
                previewContent = "![image](content://images/img_001.jpg)",
            )

        (resolved) shouldBe ("![image](content://images/img_001.jpg)")
        }
    }

    init {
        test("preview content falls back to input text when override is absent") {
        val resolved =
            resolveInputEditorPreviewContent(
                inputText = "# Title",
                previewContent = null,
            )

        (resolved) shouldBe ("# Title")
        }
    }
}
