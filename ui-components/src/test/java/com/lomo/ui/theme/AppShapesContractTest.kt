package com.lomo.ui.theme

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/*
 * Behavior Contract:
 * - Unit under test: AppShapes design-token object and the MaterialTheme Shapes slot binding in ui-components theme.
 * - Behavior focus: the Material 3 Expressive corner ramp (20 / 32 / 48 dp tokens), common asymmetric
 *   shapes (top-only, end-only), preservation of the legacy 28dp ExtraLarge constant for call sites
 *   that intentionally keep 28dp, and the extraLarge slot binding to the 32dp Expressive value that
 *   drives every consuming M3 component shape (FAB, Dialog, SearchBar, Card) at render time.
 * - Observable outcomes: each AppShapes token's CornerBasedShape corner sizes and the MaterialTheme
 *   Shapes slot bindings in the shared `Shapes` val that LomoTheme passes to MaterialTheme.
 * - TDD proof: Fails before Phase 1A because the new tokens LargeIncreased / ExtraLargeIncreased /
 *   ExtraExtraLarge / SmallTop / MediumTop / LargeEnd are unresolved references, and `Shapes.extraLarge`
 *   still binds to the legacy 28dp AppShapes.ExtraLarge instead of the 32dp ExtraLargeIncreased token.
 * - Excludes: actual Compose render output, per-component shape overrides, Squircle / superellipse
 *   (non-goal), and color / typography tokens covered by their own contract tests.
 */
class AppShapesContractTest : UiComponentsFunSpec() {
    init {
        test("M3 Expressive corner ramp exposes 20, 32, and 48 dp tokens") {
        (AppShapes.LargeIncreased) shouldBe (RoundedCornerShape(20.dp))
        (AppShapes.ExtraLargeIncreased) shouldBe (RoundedCornerShape(32.dp))
        (AppShapes.ExtraExtraLarge) shouldBe (RoundedCornerShape(48.dp))
        }
    }

    init {
        test("legacy ExtraLarge stays at 28dp for call sites that intentionally pin there") {
        (AppShapes.ExtraLarge) shouldBe (RoundedCornerShape(28.dp))
        }
    }

    init {
        test("SmallTop has 8dp top corners and square bottom for bottom-sheet first-item style") {
        (AppShapes.SmallTop) shouldBe (RoundedCornerShape(
                topStart = 8.dp,
                topEnd = 8.dp,
                bottomEnd = 0.dp,
                bottomStart = 0.dp,
            ))
        }
    }

    init {
        test("MediumTop has 16dp top corners and square bottom for bottom-sheet header style") {
        (AppShapes.MediumTop) shouldBe (RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomEnd = 0.dp,
                bottomStart = 0.dp,
            ))
        }
    }

    init {
        test("LargeEnd has 28dp on the end edge and square start edge for drawer-sheet shape") {
        (AppShapes.LargeEnd) shouldBe (RoundedCornerShape(
                topStart = 0.dp,
                topEnd = 28.dp,
                bottomEnd = 28.dp,
                bottomStart = 0.dp,
            ))
        }
    }

    init {
        test("Shapes extraLarge slot binds to the 32dp Expressive ExtraLargeIncreased token, not legacy 28dp") {
        (Shapes.extraLarge) shouldBe (AppShapes.ExtraLargeIncreased)
        }
    }

    init {
        test("non-extraLarge Shapes slots keep their pre-Expressive token bindings") {
        (Shapes.extraSmall) shouldBe (AppShapes.ExtraSmall)
        (Shapes.small) shouldBe (AppShapes.Small)
        (Shapes.medium) shouldBe (AppShapes.Medium)
        (Shapes.large) shouldBe (AppShapes.Large)
        }
    }
}
