package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.Typography
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lomo.ui.theme.TypographyScales
import com.lomo.ui.theme.memoBodyTextStyle

/*
 * Test Contract:
 * - Unit under test: modern markdown renderer token mapping.
 * - Behavior focus: the library-backed markdown path reuses Lomo memo paragraph rhythm, heading rhythm, and block spacing instead of drifting to library defaults.
 * - Observable outcomes: selected paragraph style line height and letter spacing, selected heading font weight, selected block spacing, and selected link decoration.
 * - Red phase: Fails before the fix because modern markdown tokens leave link decoration unset
 *   instead of exposing the requested visible underline treatment.
 * - Excludes: actual composable rendering, third-party markdown parsing internals, and image loading behavior.
 */
class ModernMarkdownRendererTokensTest : UiComponentsFunSpec() {
    private val typography = Typography()
    private val defaultScales = TypographyScales()

    init {
        test("modern markdown typography keeps memo paragraph rhythm and underlined link styling") {
        val spec = createModernMarkdownTokenSpec(typography, scales = defaultScales)

        (spec.paragraphStyle) shouldBe (typography.memoBodyTextStyle(defaultScales))
        (spec.paragraphStyle.lineHeight) shouldBe (16.sp)
        (spec.paragraphStyle.letterSpacing) shouldBe (0.1.sp)
        (spec.heading1Style.fontWeight != null) shouldBe true
        (spec.linkStyle.style?.textDecoration) shouldBe (TextDecoration.Underline)
        }
    }

    init {
        test("modern markdown padding keeps memo paragraph block spacing") {
        val spec = createModernMarkdownTokenSpec(typography, scales = defaultScales)

        (spec.blockSpacing) shouldBe (8.dp)
        (spec.listSpacing) shouldBe (8.dp)
        (spec.listItemSpacing) shouldBe (4.dp)
        }
    }
}
