package com.lomo.app.feature.search

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Test Contract:
 * - Unit under test: SearchInputMorphTargets.fromFocus(isFocused, colorScheme)
 * - Behavior focus: pure M3E target mapping for the dedicated search page's floating capsule
 *   search input. Focused and resting states must produce different shape, surface tone,
 *   elevation, and leading-icon emphasis.
 * - Observable outcomes: returned SearchInputMorphTargets values for focused/resting states.
 * - Red phase: Fails before the production change because SearchInputMorphTargets does not exist
 *   in the single-page search implementation, so the floating capsule has no unit-testable
 *   focus morph contract.
 * - Excludes: Compose runtime focus dispatch, animation frame timing, pixel rendering, keyboard
 *   behavior, and search result rendering.
 */
class SearchInputMorphTargetsTest : AppFunSpec() {
    private val scheme = lightColorScheme()

    init {
        test("focused targets use high emphasis tone primary icon and large increased corner") {
            val targets = SearchInputMorphTargets.fromFocus(isFocused = true, colorScheme = scheme)

            (targets.containerColor) shouldBe (scheme.surfaceContainerHighest)
            (targets.leadingIconTint) shouldBe (scheme.primary)
            (targets.cornerRadius) shouldBe (20.dp)
            (targets.tonalElevation) shouldBe (6.dp)
        }
    }

    init {
        test("resting targets use floating capsule tone variant icon and extra large increased corner") {
            val targets = SearchInputMorphTargets.fromFocus(isFocused = false, colorScheme = scheme)

            (targets.containerColor) shouldBe (scheme.surfaceContainerHigh)
            (targets.leadingIconTint) shouldBe (scheme.onSurfaceVariant)
            (targets.cornerRadius) shouldBe (32.dp)
            (targets.tonalElevation) shouldBe (3.dp)
        }
    }

    init {
        test("focused and resting targets differ across every morph field") {
            val focused = SearchInputMorphTargets.fromFocus(isFocused = true, colorScheme = scheme)
            val resting = SearchInputMorphTargets.fromFocus(isFocused = false, colorScheme = scheme)

            (focused.containerColor) shouldNotBe (resting.containerColor)
            (focused.leadingIconTint) shouldNotBe (resting.leadingIconTint)
            (focused.cornerRadius) shouldNotBe (resting.cornerRadius)
            (focused.tonalElevation) shouldNotBe (resting.tonalElevation)
        }
    }

}
