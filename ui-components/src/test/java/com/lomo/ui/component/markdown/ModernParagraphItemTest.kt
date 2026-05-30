package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.Typography
import com.lomo.ui.theme.TypographyScales
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

/*
 * Behavior Contract:
 * - Unit under test: modern markdown paragraph item builder.
 * - Owning layer: ui-components.
 * - Priority tier: P0.
 * - Capability: split Markdown paragraphs into presentation-safe text, image, gallery, and caller-resolved media items.
 *
 * Scenarios:
 * - Given plain or rich Markdown text, when paragraph items are built, then visible text is preserved.
 * - Given image Markdown, when no media resolver claims it, then image and gallery behavior is preserved.
 * - Given an audio-looking image marker and no media resolver, when paragraph items are built, then it falls back to image presentation.
 * - Given an audio-looking image marker and a caller media resolver, when paragraph items are built, then it emits a media presentation item instead of hard-coding an audio card.
 *
 * Observable outcomes:
 * - emitted paragraph item kinds, text payloads, image destinations, and media presentation data.
 *
 * TDD proof:
 * - Fails before the fix because paragraph media is represented as VoiceMemo and is selected by ui-components through domain MediaFileExtensions.
 *
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

        test("rich inline paragraph keeps visible text") {
            val content = "今天 **bold** memo"

            val items = buildModernParagraphItemsFor(content)

            (items.size) shouldBe (1)
            val textItem = items.single() as ModernParagraphItem.Text
            (textItem.text.text.contains("今天")) shouldBe true
            (textItem.text.text.contains("bold")) shouldBe true
            (textItem.text.text.contains("memo")) shouldBe true
        }

        test("single newline inside a paragraph stays as a visible line break") {
            val content = "第一行\n第二行"

            val items = buildModernParagraphItemsFor(content)

            (items.size) shouldBe (1)
            val textItem = items.single() as ModernParagraphItem.Text
            (textItem.text.text) shouldBe ("第一行\n第二行")
        }

        test("single newline keeps rich inline formatting while preserving the line break") {
            val content = "第一行 **bold**\n第二行"

            val items = buildModernParagraphItemsFor(content)

            (items.size) shouldBe (1)
            val textItem = items.single() as ModernParagraphItem.Text
            (textItem.text.text) shouldBe ("第一行 bold\n第二行")
            (textItem.text.spanStyles.any { it.item.fontWeight != null }) shouldBe true
        }

        test("paragraph with leading text and trailing image keeps both text and image items") {
            val content = "lead text ![cover](cover.png)"

            val items = buildModernParagraphItemsFor(content)

            (items.size) shouldBe (2)
            val textItem = items.first() as ModernParagraphItem.Text
            val imageItem = items[1] as ModernParagraphItem.Image
            (textItem.text.text) shouldBe ("lead text ")
            (imageItem.image.destination) shouldBe ("cover.png")
        }

        test("image only paragraph still emits a dedicated image item") {
            val content = "![cover](cover.png)"

            val items = buildModernParagraphItemsFor(content)

            (items.size) shouldBe (1)
            val imageItem = items.single() as ModernParagraphItem.Image
            (imageItem.image.destination) shouldBe ("cover.png")
        }

        test("audio marker falls back to image item when no media resolver is supplied") {
            val content = "![voice](recordings/memo.ogg)"

            val items = buildModernParagraphItemsFor(content)

            (items.size) shouldBe (1)
            val imageItem = items.single() as ModernParagraphItem.Image
            (imageItem.image.destination) shouldBe ("recordings/memo.ogg")
        }

        test("caller media resolver promotes audio marker to media presentation item") {
            val content = "![voice](recordings/memo.ogg)"

            val items =
                buildModernParagraphItemsFor(
                    content = content,
                    mediaPresentationResolver = { image ->
                        if (image.destination.endsWith(".ogg")) {
                            MarkdownMediaPresentation(
                                source = image.destination,
                                description = image.title,
                                kind = "audio",
                            )
                        } else {
                            null
                        }
                    },
                )

            (items.size) shouldBe (1)
            val mediaItem = items.single() as ModernParagraphItem.Media
            (mediaItem.presentation.source) shouldBe ("recordings/memo.ogg")
            (mediaItem.presentation.kind) shouldBe ("audio")
        }
    }

    private fun buildModernParagraphItemsFor(
        content: String,
        mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
    ): List<ModernParagraphItem> =
        buildModernParagraphItems(
            content = content,
            paragraphNode = parseParagraphNode(content),
            tokenSpec = tokenSpec,
            textStyle = tokenSpec.paragraphStyle,
            mediaPresentationResolver = mediaPresentationResolver,
        )

    private fun parseParagraphNode(content: String): ASTNode =
        parseModernMarkdownDocument(content).children.first { it.type == MarkdownElementTypes.PARAGRAPH }
}
