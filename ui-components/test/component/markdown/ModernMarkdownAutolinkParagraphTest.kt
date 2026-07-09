package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import com.lomo.ui.theme.TypographyScales

/*
 * Behavior Contract:
 * - Unit under test: modern markdown autolink paragraph rendering.
 * - Owning layer: ui-components.
 * - Priority tier: P0.
 * - Capability: render URL-only paragraph text through both legacy fragment and semantic paragraph adapters.
 *
 * Scenarios:
 * - Given a bare URL fragment, when it is rendered by the legacy fragment adapter, then visible text and link annotation are preserved.
 * - Given a bare URL semantic paragraph, when paragraph items are built, then visible text and link annotation are preserved.
 *
 * Observable outcomes:
 * - Annotated text payload, link-annotation presence, and emitted paragraph-item kinds.
 *
 * TDD proof:
 * - Fails before the semantic pipeline fix because paragraph item rendering reparses markdown fragments instead of consuming the render-plan semantic paragraph.
 *
 * Excludes:
 * - Compose widget rendering, TextView movement methods, image loading, and top-level block planning.
 *
 * Test Change Justification:
 * - Reason category: production contract migration from AST fragment input to semantic paragraph input.
 * - Old behavior/assertion being replaced: the paragraph item test built items from raw content plus a parsed AST node.
 * - Why old assertion is no longer correct: paragraph item rendering no longer owns fragment parsing; it consumes the render-plan semantic paragraph.
 * - Coverage preserved by: the test still verifies URL-only visible text and link annotation through the semantic paragraph adapter.
 * - Why this is not fitting the test to the implementation: the observable link/text contract is unchanged; only the public test entrypoint follows the new single-IR boundary.
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

        test("bare url paragraph emits a visible text item") {
            val content = "https://example.com/path?q=1"

            val items =
                buildModernParagraphItems(
                    paragraph =
                        parseMarkdownSemanticDocument(content).blocks.single()
                            as MarkdownSemanticBlock.Paragraph,
                    tokenSpec = tokenSpec,
                )

            (items.size) shouldBe (1)
            val textItem = items.single() as ModernParagraphItem.Text
            (textItem.text.text) shouldBe (content)
            (textItem.text.getLinkAnnotations(0, textItem.text.length).isEmpty()) shouldBe false
        }
    }
}
