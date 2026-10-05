package com.lomo.ui.theme

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/*
 * Behavior Contract:
 * Capability: distinguish standard and increased Material shape roles; owner: ui-components; P1.
 * Scenarios:
 * Given theme shapes, when components select a named slot, then 28dp remains extraLarge and
 * 20/32/48dp have their own increased slots. Existing asymmetric container edges remain available.
 * Observable outcomes: resolved corners and role mappings.
 * TDD proof: existing role assertions are updated for the approved design; UI rendering is device validation.
 * Excludes: actual layout and animated corner morphs.
 * Test Change Justification:
 * Reason category: User-approved design contract change.
 * Old behavior/assertion being replaced: extraLarge was incorrectly used as the 32dp slot.
 * Why old assertion is no longer correct: Material now exposes the distinct increased shape slots.
 * Coverage preserved by: every original corner assertion plus the new role mappings.
 * Why this is not fitting the test to the implementation: mappings follow Material's published Shapes API.
 */
class AppShapesContractTest : UiComponentsFunSpec() {
    init {
        test("M3 Expressive corner ramp exposes 20, 32, and 48 dp tokens") {
        (AppShapes.LargeIncreased) shouldBe (RoundedCornerShape(20.dp))
        (AppShapes.ExtraLargeIncreased) shouldBe (RoundedCornerShape(32.dp))
        (AppShapes.ExtraExtraLarge) shouldBe (RoundedCornerShape(48.dp))
        }

        test("legacy ExtraLarge stays at 28dp for call sites that intentionally pin there") {
        (AppShapes.ExtraLarge) shouldBe (RoundedCornerShape(28.dp))
        }

        test("SmallTop has 8dp top corners and square bottom for bottom-sheet first-item style") {
        (AppShapes.SmallTop) shouldBe (RoundedCornerShape(
                topStart = 8.dp,
                topEnd = 8.dp,
                bottomEnd = 0.dp,
                bottomStart = 0.dp,
            ))
        }

        test("MediumTop has 16dp top corners and square bottom for bottom-sheet header style") {
        (AppShapes.MediumTop) shouldBe (RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomEnd = 0.dp,
                bottomStart = 0.dp,
            ))
        }

        test("LargeEnd has 28dp on the end edge and square start edge for drawer-sheet shape") {
        (AppShapes.LargeEnd) shouldBe (RoundedCornerShape(
                topStart = 0.dp,
                topEnd = 28.dp,
                bottomEnd = 28.dp,
                bottomStart = 0.dp,
            ))
        }

        test("Shapes keeps 28dp extraLarge and separately exposes Expressive increased slots") {
        (Shapes.extraLarge) shouldBe (AppShapes.ExtraLarge)
        Shapes.largeIncreased shouldBe AppShapes.LargeIncreased
        Shapes.extraLargeIncreased shouldBe AppShapes.ExtraLargeIncreased
        Shapes.extraExtraLarge shouldBe AppShapes.ExtraExtraLarge
        }

        test("non-extraLarge Shapes slots keep their pre-Expressive token bindings") {
        (Shapes.extraSmall) shouldBe (AppShapes.ExtraSmall)
        (Shapes.small) shouldBe (AppShapes.Small)
        (Shapes.medium) shouldBe (AppShapes.Medium)
        (Shapes.large) shouldBe (AppShapes.Large)
        }
    }
}
