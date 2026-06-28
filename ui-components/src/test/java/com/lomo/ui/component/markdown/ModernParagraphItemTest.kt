package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import androidx.compose.material3.Typography
import com.lomo.ui.theme.TypographyScales

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
 * - Given a render plan semantic paragraph with reference image and rich inline text, when paragraph items are built, then the semantic paragraph is the only inline source.
 *
 * Observable outcomes:
 * - emitted paragraph item kinds, text payloads, image destinations, inline style ranges, link annotations, and media presentation data.
 *
 * TDD proof:
 * - Fails before the fix because paragraph media is represented as VoiceMemo and is selected by ui-components through domain MediaFileExtensions.
 * - Fails before the semantic pipeline fix because reference images are parsed into the render-plan semantic block but
 *   buildModernParagraphItems reparses the paragraph AST fragment and cannot resolve reference image destinations.
 *
 * Excludes:
 * - Compose widget rendering, TextView layout, image loading, and top-level block planning.
 *
 * Test Change Justification:
 * - Reason category: production contract migration from AST paragraph input to semantic paragraph input.
 * - Old behavior/assertion being replaced: tests parsed paragraph AST nodes locally and passed raw content into buildModernParagraphItems.
 * - Why old assertion is no longer correct: paragraph item building no longer owns Markdown parsing; the render plan owns semantic parsing once.
 * - Coverage preserved by: existing text, line break, image, gallery, and media scenarios still assert emitted paragraph item outcomes.
 * - Why this is not fitting the test to the implementation: the expected user-visible item outputs are unchanged, and the new reference-image scenario locks cross-parser behavior.
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

        test("semantic paragraph is the single source for reference images and inline styling") {
            val content =
                """
                lead [site][home] ==focus== <u>now</u> ![cover][cover]

                [home]: https://example.com
                [cover]: images/cover.png "Cover"
                """.trimIndent()
            val plan =
                createModernMarkdownRenderPlan(
                    content = content,
                    knownTagsToStrip = emptyList(),
                )
            val block = plan.items.first() as ModernMarkdownRenderItem.Block
            val paragraph = block.semanticBlock as MarkdownSemanticBlock.Paragraph

            val items = buildModernParagraphItemsFor(paragraph)

            (items.size) shouldBe (2)
            val textItem = items.first() as ModernParagraphItem.Text
            (textItem.text.text) shouldBe ("lead site focus now ")
            (textItem.text.getLinkAnnotations(0, textItem.text.length).isEmpty()) shouldBe false
            (textItem.text.spanStyles.any { range -> textItem.text.text.substring(range.start, range.end) == "focus" }) shouldBe true
            (textItem.text.spanStyles.any { range -> textItem.text.text.substring(range.start, range.end) == "now" }) shouldBe true
            val imageItem = items[1] as ModernParagraphItem.Image
            (imageItem.image.destination) shouldBe ("images/cover.png")
            (imageItem.image.title) shouldBe ("cover")
        }
    }

    private fun buildModernParagraphItemsFor(
        content: String,
        mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
    ): List<ModernParagraphItem> =
        buildModernParagraphItemsFor(
            paragraph = parseParagraph(content),
            mediaPresentationResolver = mediaPresentationResolver,
        )

    private fun buildModernParagraphItemsFor(
        paragraph: MarkdownSemanticBlock.Paragraph,
        mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
    ): List<ModernParagraphItem> =
        buildModernParagraphItems(
            paragraph = paragraph,
            tokenSpec = tokenSpec,
            mediaPresentationResolver = mediaPresentationResolver,
        )

    private fun parseParagraph(content: String): MarkdownSemanticBlock.Paragraph =
        parseMarkdownSemanticDocument(content).blocks.first() as MarkdownSemanticBlock.Paragraph
}
