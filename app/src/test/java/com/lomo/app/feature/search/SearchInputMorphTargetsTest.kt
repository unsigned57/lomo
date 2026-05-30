package com.lomo.app.feature.search

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Behavior Contract:
 * - Unit under test: SearchInputMorphTargets.fromFocus(isFocused, colorScheme)
 * - Owning layer: app Search UI presentation state
 * - Priority tier: P1
 * - Capability: keep the dedicated search page's floating capsule input morph as a local,
 *   unit-testable visual target policy.
 *
 * Scenarios:
 * - Given the input is focused, when morph targets resolve, then high-emphasis surface, primary
 *   leading icon tint, tighter corner radius, and higher tonal elevation are selected.
 * - Given the input is resting, when morph targets resolve, then resting surface, variant icon
 *   tint, wider corner radius, and lower tonal elevation are selected.
 * - Given focused and resting targets are compared, when all fields are inspected, then every
 *   morph field differs.
 *
 * - Observable outcomes: returned SearchInputMorphTargets values for focused/resting states.
 *
 * TDD proof:
 * - Not applicable - style-only stabilization of an existing direct Search UI test for the
 *   preexisting morph extraction.
 *
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

        test("resting targets use floating capsule tone variant icon and extra large increased corner") {
            val targets = SearchInputMorphTargets.fromFocus(isFocused = false, colorScheme = scheme)

            (targets.containerColor) shouldBe (scheme.surfaceContainerHigh)
            (targets.leadingIconTint) shouldBe (scheme.onSurfaceVariant)
            (targets.cornerRadius) shouldBe (32.dp)
            (targets.tonalElevation) shouldBe (3.dp)
        }

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
