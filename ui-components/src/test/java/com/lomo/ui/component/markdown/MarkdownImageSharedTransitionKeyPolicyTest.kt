package com.lomo.ui.component.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: markdown image shared-transition key policy
 * - Behavior focus: non-navigable markdown image previews must not register shared-element keys, while navigable images keep deterministic keys and optional namespace isolation.
 * - Observable outcomes: nullable shared-element key returned for a destination under different navigation and namespace conditions.
 * - Red phase: Fails before the fix because markdown images always inherit destination as a shared-element key, even in non-clickable history previews that can render the same image multiple times and crash shared transitions.
 * - Excludes: Compose rendering, Coil painter state, image decoding, and navigation animation execution.
 */
class MarkdownImageSharedTransitionKeyPolicyTest {
    @Test
    fun `non navigable markdown image preview does not expose a shared transition key`() {
        assertNull(
            resolveMarkdownImageSharedElementKey(
                destination = "content://images/history.png",
                hasNavigationTarget = false,
            ),
        )
    }

    @Test
    fun `navigable markdown image preview keeps destination as shared transition key by default`() {
        assertEquals(
            "content://images/history.png",
            resolveMarkdownImageSharedElementKey(
                destination = "content://images/history.png",
                hasNavigationTarget = true,
            ),
        )
    }

    @Test
    fun `namespace isolates shared transition keys for duplicate image destinations`() {
        assertEquals(
            "history:r2|content://images/history.png",
            resolveMarkdownImageSharedElementKey(
                destination = "content://images/history.png",
                hasNavigationTarget = true,
                namespace = "history:r2",
            ),
        )
    }
}
