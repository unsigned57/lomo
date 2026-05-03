package com.lomo.app.feature.search

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

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
class SearchInputMorphTargetsTest {
    private val scheme = lightColorScheme()

    @Test
    fun `focused targets use high emphasis tone primary icon and large increased corner`() {
        val targets = SearchInputMorphTargets.fromFocus(isFocused = true, colorScheme = scheme)

        assertEquals(scheme.surfaceContainerHighest, targets.containerColor)
        assertEquals(scheme.primary, targets.leadingIconTint)
        assertEquals(20.dp, targets.cornerRadius)
        assertEquals(6.dp, targets.tonalElevation)
    }

    @Test
    fun `resting targets use floating capsule tone variant icon and extra large increased corner`() {
        val targets = SearchInputMorphTargets.fromFocus(isFocused = false, colorScheme = scheme)

        assertEquals(scheme.surfaceContainerHigh, targets.containerColor)
        assertEquals(scheme.onSurfaceVariant, targets.leadingIconTint)
        assertEquals(32.dp, targets.cornerRadius)
        assertEquals(3.dp, targets.tonalElevation)
    }

    @Test
    fun `focused and resting targets differ across every morph field`() {
        val focused = SearchInputMorphTargets.fromFocus(isFocused = true, colorScheme = scheme)
        val resting = SearchInputMorphTargets.fromFocus(isFocused = false, colorScheme = scheme)

        assertNotEquals(resting.containerColor, focused.containerColor)
        assertNotEquals(resting.leadingIconTint, focused.leadingIconTint)
        assertNotEquals(resting.cornerRadius, focused.cornerRadius)
        assertNotEquals(resting.tonalElevation, focused.tonalElevation)
    }
}
