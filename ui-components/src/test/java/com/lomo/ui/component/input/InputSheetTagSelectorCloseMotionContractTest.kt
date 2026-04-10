package com.lomo.ui.component.input

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputSheet tag-selector close motion layout contract.
 * - Behavior focus: closing the tag selector must be a single continuous collapse motion without
 *   a second downward shift caused by delayed removal of parent-level spacing.
 * - Observable outcomes: InputEditorPanel no longer uses global Column spacedBy for tag-selector
 *   neighbors, the toolbar keeps an explicit stable top gap, and tag-selector-specific top spacing
 *   is hosted inside the AnimatedVisibility block so it collapses together with selector content.
 * - Red phase: Fails before the fix because InputEditorPanel uses a global
 *   verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall), which leaves spacing
 *   ownership outside the tag selector visibility animation and can produce a second layout shift
 *   after close animation completes.
 * - Excludes: pixel-level timing/easing validation, OEM keyboard variance, and submit logic.
 */
class InputSheetTagSelectorCloseMotionContractTest {
    private val sourceText =
        File("src/main/java/com/lomo/ui/component/input/InputSheetComponents.kt").readText()

    @Test
    fun `tag selector close motion spacing is not controlled by global column spacing`() {
        assertFalse(
            "Global Column spacedBy around the animated tag selector can remove spacing in a second layout phase after close animation settles, causing a visible hitch.",
            sourceText.contains("verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall)"),
        )
    }

    @Test
    fun `toolbar keeps explicit stable gap while tag selector owns its animated spacing`() {
        assertTrue(
            "Toolbar should keep a stable explicit top gap so hidden-tag layout is deterministic.",
            sourceText.contains("modifier = chromeModifier.padding(top = AppSpacing.MediumSmall)"),
        )
        assertTrue(
            "Tag selector should own top spacing inside AnimatedVisibility so spacing collapses together with content.",
            sourceText.contains("Spacer(modifier = Modifier.height(AppSpacing.MediumSmall))"),
        )
    }
}
