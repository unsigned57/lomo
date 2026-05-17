package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import com.lomo.ui.theme.TypographyScales

/*
 * Test Contract:
 * - Unit under test: modern markdown autolink paragraph rendering.
 * - Behavior focus: a memo paragraph containing only a bare URL must still emit visible text and preserve a clickable link annotation instead of collapsing to an empty paragraph.
 * - Observable outcomes: annotated text payload, link-annotation presence, and emitted paragraph-item kinds.
 * - Red phase: Fails before the fix because the modern fragment annotator returns an empty result for a bare autolink fragment, so URL-only memo paragraphs render no visible text item.
 * - Excludes: Compose widget rendering, TextView movement methods, image loading, and top-level block planning.
 */
class ModernMarkdownAutolinkParagraphTest : UiComponentsFunSpec() {
    private val tokenSpec =
        createModernMarkdownTokenSpec(
            typography = Typography(),
            linkColor = Color(0xFF3366FF),
            scales = TypographyScales(),
        )

    init {
        test("bare url fragment keeps visible text and link annotation") {
        val fragment = "https://example.com/path?q=1"

        val annotatedText =
            buildModernMarkdownAnnotatedTextFromFragment(
                fragment = fragment,
                style = tokenSpec.paragraphStyle,
                tokenSpec = tokenSpec,
            )

        (annotatedText.text) shouldBe (fragment)
        (annotatedText.getLinkAnnotations(0, annotatedText.length).isNotEmpty()) shouldBe true
        }
    }

    init {
        test("bare url paragraph emits a visible text item") {
        val content = "https://example.com/path?q=1"

        val items =
            buildModernParagraphItems(
                content = content,
                paragraphNode = parseModernMarkdownDocument(content).children.single(),
                tokenSpec = tokenSpec,
                textStyle = tokenSpec.paragraphStyle,
            )

        (items.size) shouldBe (1)
        val textItem = items.single() as ModernParagraphItem.Text
        (textItem.text.text) shouldBe (content)
        (textItem.text.getLinkAnnotations(0, textItem.text.length).isEmpty()) shouldBe false
        }
    }
}
