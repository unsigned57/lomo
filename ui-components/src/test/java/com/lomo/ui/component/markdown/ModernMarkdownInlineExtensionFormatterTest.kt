package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import com.lomo.ui.theme.TypographyScales

/*
 * Test Contract:
 * - Unit under test: modern markdown inline extension formatter contract.
 * - Behavior focus: display-time inline markdown extensions must render highlight and underline styling without corrupting plain text or code-span literals.
 * - Observable outcomes: rendered annotated text content, highlight span background, underline span decoration, and absence of extension styling inside code spans.
 * - Red phase: Fails before the fix because modern markdown annotation leaves `==highlight==` and `<u>underline</u>` as plain literal text instead of rendering extension styles.
 * - Excludes: Compose widget rendering, block-level markdown layout, and third-party parser internals beyond observable annotated output.
 */
class ModernMarkdownInlineExtensionFormatterTest : UiComponentsFunSpec() {
    private val typography = Typography()
    private val tokenSpec =
        createModernMarkdownTokenSpec(
            typography,
            linkColor = Color(0xFF0061A4),
            scales = TypographyScales(),
        )
    private val paragraphStyle: TextStyle = tokenSpec.paragraphStyle

    init {
        test("highlight extension renders background span and strips marker syntax") {
        val result =
            buildModernMarkdownAnnotatedTextFromFragment(
                fragment = "Before ==focus== after",
                style = paragraphStyle,
                tokenSpec = tokenSpec,
            )

        (result.text) shouldBe ("Before focus after")
        val highlightRange = result.spanStyles.single { it.item.background != Color.Unspecified }
        (result.text.substring(highlightRange.start, highlightRange.end)) shouldBe ("focus")
        }
    }

    init {
        test("underline html extension renders underline span and strips tags") {
        val result =
            buildModernMarkdownAnnotatedTextFromFragment(
                fragment = "Keep <u>focus</u> visible",
                style = paragraphStyle,
                tokenSpec = tokenSpec,
            )

        (result.text) shouldBe ("Keep focus visible")
        val underlineRange =
            result.spanStyles.single { it.item.textDecoration == TextDecoration.Underline }
        (result.text.substring(underlineRange.start, underlineRange.end)) shouldBe ("focus")
        }
    }

    init {
        test("inline code literals keep extension markers as plain text") {
        val result =
            buildModernMarkdownAnnotatedTextFromFragment(
                fragment = "`==code==` and `<u>tag</u>`",
                style = paragraphStyle,
                tokenSpec = tokenSpec,
            )

        (result.text.contains("==code==")) shouldBe true
        (result.text.contains("tag")) shouldBe true
        (result.spanStyles.any { it.item.fontFamily != null }) shouldBe true
        (result.spanStyles.any { it.item.background != Color.Unspecified }) shouldBe false
        (result.spanStyles.any { it.item.textDecoration == TextDecoration.Underline }) shouldBe false
        }
    }
}
