package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.Typography
import com.lomo.ui.theme.TypographyScales
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

/*
 * Test Contract:
 * - Unit under test: modern markdown paragraph item builder.
 * - Behavior focus: plain and mixed-content memo paragraphs must emit visible text items instead of collapsing to an empty body, while inline images and voice attachments still split into dedicated media items.
 * - Observable outcomes: emitted paragraph item kinds, text payloads, image destinations, and voice memo destinations.
 * - Red phase: Fails before the fix because leaf text nodes in the modern paragraph path produce empty annotated strings, and `.ogg` attachments are not recognized as voice memos.
 * - Excludes: Compose widget rendering, TextView layout, image loading, and top-level block planning.
 */
class ModernParagraphItemTest : UiComponentsFunSpec() {
    private val tokenSpec = createModernMarkdownTokenSpec(Typography(), scales = TypographyScales())

    init {
        test("plain text paragraph emits a visible text item") {
        val content = "plain memo body"

        val items = buildModernParagraphItemsFor(content)

        (items.size) shouldBe (1)
        val textItem = items.single() as ModernParagraphItem.Text
        (textItem.text.text) shouldBe ("plain memo body")
        }
    }

    init {
        test("rich inline paragraph keeps visible text") {
        val content = "今天 **bold** memo"

        val items = buildModernParagraphItemsFor(content)

        (items.size) shouldBe (1)
        val textItem = items.single() as ModernParagraphItem.Text
        (textItem.text.text.contains("今天")) shouldBe true
        (textItem.text.text.contains("bold")) shouldBe true
        (textItem.text.text.contains("memo")) shouldBe true
        }
    }

    init {
        test("single newline inside a paragraph stays as a visible line break") {
        val content = "第一行\n第二行"

        val items = buildModernParagraphItemsFor(content)

        (items.size) shouldBe (1)
        val textItem = items.single() as ModernParagraphItem.Text
        (textItem.text.text) shouldBe ("第一行\n第二行")
        }
    }

    init {
        test("single newline keeps rich inline formatting while preserving the line break") {
        val content = "第一行 **bold**\n第二行"

        val items = buildModernParagraphItemsFor(content)

        (items.size) shouldBe (1)
        val textItem = items.single() as ModernParagraphItem.Text
        (textItem.text.text) shouldBe ("第一行 bold\n第二行")
        (textItem.text.spanStyles.any { it.item.fontWeight != null }) shouldBe true
        }
    }

    init {
        test("paragraph with leading text and trailing image keeps both text and image items") {
        val content = "lead text ![cover](cover.png)"

        val items = buildModernParagraphItemsFor(content)

        (items.size) shouldBe (2)
        val textItem = items.first() as ModernParagraphItem.Text
        val imageItem = items[1] as ModernParagraphItem.Image
        (textItem.text.text) shouldBe ("lead text ")
        (imageItem.image.destination) shouldBe ("cover.png")
        }
    }

    init {
        test("image only paragraph still emits a dedicated image item") {
        val content = "![cover](cover.png)"

        val items = buildModernParagraphItemsFor(content)

        (items.size) shouldBe (1)
        val imageItem = items.single() as ModernParagraphItem.Image
        (imageItem.image.destination) shouldBe ("cover.png")
        }
    }

    init {
        test("voice memo paragraph emits a dedicated voice memo item for ogg attachments") {
        val content = "![voice](recordings/memo.ogg)"

        val items = buildModernParagraphItemsFor(content)

        (items.size) shouldBe (1)
        val voiceMemoItem = items.single() as ModernParagraphItem.VoiceMemo
        (voiceMemoItem.url) shouldBe ("recordings/memo.ogg")
        }
    }

    private fun buildModernParagraphItemsFor(content: String): List<ModernParagraphItem> =
        buildModernParagraphItems(
            content = content,
            paragraphNode = parseParagraphNode(content),
            tokenSpec = tokenSpec,
            textStyle = tokenSpec.paragraphStyle,
        )

    private fun parseParagraphNode(content: String): ASTNode =
        parseModernMarkdownDocument(content).children.first { it.type == MarkdownElementTypes.PARAGRAPH }
}
