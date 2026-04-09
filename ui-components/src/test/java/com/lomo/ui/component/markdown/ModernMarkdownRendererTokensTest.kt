package com.lomo.ui.component.markdown

import androidx.compose.material3.Typography
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lomo.ui.theme.memoBodyTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: modern markdown renderer token mapping.
 * - Behavior focus: the library-backed markdown path reuses Lomo memo paragraph rhythm, heading rhythm, and block spacing instead of drifting to library defaults.
 * - Observable outcomes: selected paragraph style line height and letter spacing, selected heading font weight, selected block spacing, and selected link decoration.
 * - Red phase: Fails before the fix because modern markdown tokens leave link decoration unset
 *   instead of exposing the requested visible underline treatment.
 * - Excludes: actual composable rendering, third-party markdown parsing internals, and image loading behavior.
 */
class ModernMarkdownRendererTokensTest {
    private val typography = Typography()

    @Test
    fun `modern markdown typography keeps memo paragraph rhythm and underlined link styling`() {
        val spec = createModernMarkdownTokenSpec(typography)

        assertEquals(typography.memoBodyTextStyle(), spec.paragraphStyle)
        assertEquals(16.sp, spec.paragraphStyle.lineHeight)
        assertEquals(0.1.sp, spec.paragraphStyle.letterSpacing)
        assertTrue(spec.heading1Style.fontWeight != null)
        assertEquals(TextDecoration.Underline, spec.linkStyle.style?.textDecoration)
    }

    @Test
    fun `modern markdown padding keeps memo paragraph block spacing`() {
        val spec = createModernMarkdownTokenSpec(typography)

        assertEquals(8.dp, spec.blockSpacing)
        assertEquals(8.dp, spec.listSpacing)
        assertEquals(4.dp, spec.listItemSpacing)
    }
}
