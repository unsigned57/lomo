package com.lomo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: AppShapes design-token object and the MaterialTheme Shapes slot binding in ui-components theme.
 * - Behavior focus: the Material 3 Expressive corner ramp (20 / 32 / 48 dp tokens), common asymmetric
 *   shapes (top-only, end-only), preservation of the legacy 28dp ExtraLarge constant for call sites
 *   that intentionally keep 28dp, and the extraLarge slot binding to the 32dp Expressive value that
 *   drives every consuming M3 component shape (FAB, Dialog, SearchBar, Card) at render time.
 * - Observable outcomes: each AppShapes token's CornerBasedShape corner sizes and the MaterialTheme
 *   Shapes slot bindings in the shared `Shapes` val that LomoTheme passes to MaterialTheme.
 * - Red phase: Fails before Phase 1A because the new tokens LargeIncreased / ExtraLargeIncreased /
 *   ExtraExtraLarge / SmallTop / MediumTop / LargeEnd are unresolved references, and `Shapes.extraLarge`
 *   still binds to the legacy 28dp AppShapes.ExtraLarge instead of the 32dp ExtraLargeIncreased token.
 * - Excludes: actual Compose render output, per-component shape overrides, Squircle / superellipse
 *   (non-goal), and color / typography tokens covered by their own contract tests.
 */
class AppShapesContractTest {
    @Test
    fun `M3 Expressive corner ramp exposes 20, 32, and 48 dp tokens`() {
        assertEquals(RoundedCornerShape(20.dp), AppShapes.LargeIncreased)
        assertEquals(RoundedCornerShape(32.dp), AppShapes.ExtraLargeIncreased)
        assertEquals(RoundedCornerShape(48.dp), AppShapes.ExtraExtraLarge)
    }

    @Test
    fun `legacy ExtraLarge stays at 28dp for call sites that intentionally pin there`() {
        assertEquals(RoundedCornerShape(28.dp), AppShapes.ExtraLarge)
    }

    @Test
    fun `SmallTop has 8dp top corners and square bottom for bottom-sheet first-item style`() {
        assertEquals(
            RoundedCornerShape(
                topStart = 8.dp,
                topEnd = 8.dp,
                bottomEnd = 0.dp,
                bottomStart = 0.dp,
            ),
            AppShapes.SmallTop,
        )
    }

    @Test
    fun `MediumTop has 16dp top corners and square bottom for bottom-sheet header style`() {
        assertEquals(
            RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomEnd = 0.dp,
                bottomStart = 0.dp,
            ),
            AppShapes.MediumTop,
        )
    }

    @Test
    fun `LargeEnd has 28dp on the end edge and square start edge for drawer-sheet shape`() {
        assertEquals(
            RoundedCornerShape(
                topStart = 0.dp,
                topEnd = 28.dp,
                bottomEnd = 28.dp,
                bottomStart = 0.dp,
            ),
            AppShapes.LargeEnd,
        )
    }

    @Test
    fun `Shapes extraLarge slot binds to the 32dp Expressive ExtraLargeIncreased token, not legacy 28dp`() {
        assertEquals(AppShapes.ExtraLargeIncreased, Shapes.extraLarge)
    }

    @Test
    fun `non-extraLarge Shapes slots keep their pre-Expressive token bindings`() {
        assertEquals(AppShapes.ExtraSmall, Shapes.extraSmall)
        assertEquals(AppShapes.Small, Shapes.small)
        assertEquals(AppShapes.Medium, Shapes.medium)
        assertEquals(AppShapes.Large, Shapes.large)
    }
}
