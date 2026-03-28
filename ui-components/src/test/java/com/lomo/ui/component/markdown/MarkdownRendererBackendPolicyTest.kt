package com.lomo.ui.component.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: Markdown renderer backend selection policy.
 * - Behavior focus: all markdown renderer configurations, including memo-specific features, route to the modern library-backed backend after the full migration.
 * - Observable outcomes: selected backend eligibility for a given renderer configuration.
 * - Red phase: Fails before the fix because memo-specific features still force the legacy backend path instead of the new unified backend.
 * - Excludes: third-party markdown rendering internals, text layout metrics, and actual composable rendering.
 */
class MarkdownRendererBackendPolicyTest {
    @Test
    fun `standard markdown without lomo-specific features uses the modern backend`() {
        assertTrue(
            shouldUseModernMarkdownBackend(
                maxVisibleBlocks = Int.MAX_VALUE,
                hasTodoToggleHandler = false,
                hasTodoOverrides = false,
                hasKnownTagsToStrip = false,
                hasImageClickHandler = false,
                hasPrecomputedNode = false,
            ),
        )
    }

    @Test
    fun `memo-specific features also use the modern backend after full migration`() {
        assertTrue(
            shouldUseModernMarkdownBackend(
                maxVisibleBlocks = 4,
                hasTodoToggleHandler = false,
                hasTodoOverrides = false,
                hasKnownTagsToStrip = false,
                hasImageClickHandler = false,
                hasPrecomputedNode = false,
            ),
        )
        assertTrue(
            shouldUseModernMarkdownBackend(
                maxVisibleBlocks = Int.MAX_VALUE,
                hasTodoToggleHandler = true,
                hasTodoOverrides = false,
                hasKnownTagsToStrip = false,
                hasImageClickHandler = false,
                hasPrecomputedNode = false,
            ),
        )
        assertTrue(
            shouldUseModernMarkdownBackend(
                maxVisibleBlocks = Int.MAX_VALUE,
                hasTodoToggleHandler = false,
                hasTodoOverrides = true,
                hasKnownTagsToStrip = false,
                hasImageClickHandler = false,
                hasPrecomputedNode = false,
            ),
        )
        assertTrue(
            shouldUseModernMarkdownBackend(
                maxVisibleBlocks = Int.MAX_VALUE,
                hasTodoToggleHandler = false,
                hasTodoOverrides = false,
                hasKnownTagsToStrip = true,
                hasImageClickHandler = false,
                hasPrecomputedNode = false,
            ),
        )
        assertTrue(
            shouldUseModernMarkdownBackend(
                maxVisibleBlocks = Int.MAX_VALUE,
                hasTodoToggleHandler = false,
                hasTodoOverrides = false,
                hasKnownTagsToStrip = false,
                hasImageClickHandler = true,
                hasPrecomputedNode = false,
            ),
        )
        assertTrue(
            shouldUseModernMarkdownBackend(
                maxVisibleBlocks = Int.MAX_VALUE,
                hasTodoToggleHandler = false,
                hasTodoOverrides = false,
                hasKnownTagsToStrip = false,
                hasImageClickHandler = false,
                hasPrecomputedNode = true,
            ),
        )
    }
}
