package com.lomo.ui.component.input

import org.junit.Assert.assertEquals
import org.junit.Test

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
class InputEditorPreviewContentPolicyTest {
    @Test
    fun `preview content override has priority over raw input text`() {
        val resolved =
            resolveInputEditorPreviewContent(
                inputText = "![image](img_001.jpg)",
                previewContent = "![image](content://images/img_001.jpg)",
            )

        assertEquals("![image](content://images/img_001.jpg)", resolved)
    }

    @Test
    fun `preview content falls back to input text when override is absent`() {
        val resolved =
            resolveInputEditorPreviewContent(
                inputText = "# Title",
                previewContent = null,
            )

        assertEquals("# Title", resolved)
    }
}
