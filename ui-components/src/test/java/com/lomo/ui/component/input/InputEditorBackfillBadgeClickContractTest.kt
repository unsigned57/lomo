package com.lomo.ui.component.input

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: input editor backfill badge click contract.
 * - Behavior focus: the selected backfill timestamp badge must be an interactive cancel affordance
 *   whose click indication keeps the visible badge shape.
 * - Observable outcomes: source-level contract that the badge accepts a click callback, installs a
 *   clickable modifier on the badge surface, and receives the callback through InputEditorPanel.
 * - Red phase: Fails before the fixes because InputEditorBackfillBadge either only renders text
 *   without a click callback or installs clickable before clipping the badge shape.
 * - Excludes: Android gesture dispatch internals, Compose rendering, and date/time picker behavior.
 */
class InputEditorBackfillBadgeClickContractTest {
    @Test
    fun `backfill badge is clickable and receives panel callback`() {
        val badgeSource =
            File("src/main/java/com/lomo/ui/component/input/InputEditorBackfillBadge.kt")
                .readText()
        val componentsSource =
            File("src/main/java/com/lomo/ui/component/input/InputSheetComponents.kt")
                .readText()
        val contentSource =
            File("src/main/java/com/lomo/ui/component/input/InputSheetContent.kt")
                .readText()

        assertTrue(
            "InputEditorBackfillBadge should accept a click callback so the app can cancel backfill mode.",
            badgeSource.contains("onClick: () -> Unit"),
        )
        assertTrue(
            "The visible badge surface should install a clickable modifier instead of acting as static text.",
            badgeSource.contains(".clickable(onClick = onClick)"),
        )
        assertTrue(
            "InputEditorPanel should pass the badge callback to InputEditorBackfillBadge.",
            contentSource.contains("onBackfillBadgeClick = onBackfillBadgeClick") &&
                componentsSource.contains("InputEditorBackfillBadge("),
        )
    }

    @Test
    fun `backfill badge click indication is clipped to badge shape`() {
        val badgeSource =
            File("src/main/java/com/lomo/ui/component/input/InputEditorBackfillBadge.kt")
                .readText()
        val clipIndex = badgeSource.indexOf(".clip(AppShapes.Large)")
        val clickIndex = badgeSource.indexOf(".clickable(onClick = onClick)")

        assertTrue(
            "InputEditorBackfillBadge should clip the clickable surface to the badge shape before installing ripple.",
            clipIndex >= 0 && clickIndex > clipIndex,
        )
    }
}
