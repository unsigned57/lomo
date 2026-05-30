package com.lomo.ui.component.markdown

import androidx.compose.material3.Typography
import com.lomo.ui.component.card.buildMemoCardCollapsedSummary
import com.lomo.ui.testing.UiComponentsFunSpec
import com.lomo.ui.theme.TypographyScales
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.toImmutableList
import org.intellij.markdown.MarkdownElementTypes

/*
 * Behavior Contract:
 * - Unit under test: ModernMarkdownRenderPlan + MarkdownKnownTagFilter + MemoCard (collapsed summary sanitization)
 * - Owning layer: ui-components
 * - Priority tier: P2
 * - Capability: clean/sanitize tags and reminder tokens from body and collapsed summary texts, and plan
 *   media-resolved image paragraphs without bypassing the media presentation adapter.
 *
 * Scenarios:
 * - Given consecutive image-only paragraphs and no media resolver claims them, when a render plan is built, then they are grouped into an image gallery.
 * - Given consecutive image-only paragraphs and a media resolver claims them as audio, when a render plan is built, then no top-level image gallery is emitted and each paragraph remains available for media presentation rendering.
 * - Given a precomputed no-resolver gallery plan and a later media resolver claims those images, when unlimited render state is resolved, then the precomputed gallery is split into media-capable blocks.
 * - Given a precomputed no-resolver gallery plan and a later media resolver claims those images, when collapsed render state crops to one visible block, then only the first media-capable block is visible instead of the whole gallery.
 * - Given content with reminder tokens, when sanitizing modern markdown known tags, then reminder tokens are stripped from plain text blocks but preserved in code and headings.
 * - Given content with reminder tokens and tags, when sanitizing collapsed summary, then both tags and reminder tokens are completely stripped.
 * - Given tag/reminder sanitization executes, when completed, then no debug files or filesystem side effects are written to host-bound paths.
 *
 * Observable outcomes:
 * - Render item kinds, image gallery destinations, and media presentation sources produced by caller resolver.
 * - Returned string with tags and reminder tokens completely and cleanly removed, preserving correct spacing.
 * - No side-effect debug files are created or appended in host-bound filesystem paths.
 *
 * TDD proof:
 * - The media-resolved gallery scenario fails before the fix because createModernMarkdownRenderPlan has no media resolver input and consecutive audio image paragraphs are grouped as ModernMarkdownRenderItem.Gallery.
 * - The precomputed render-state scenarios fail before the follow-up fix because resolver-aware rendering reuses the no-resolver Gallery item unchanged.
 * - Fails because sanitization writes internal trace/debug segments to a host-bound filesystem path at /home/ephemeral/Projects/lomo/debug_segments.txt.
 *
 * Excludes:
 * - Compose layout, click logic, and notification routing.
 */
class ModernMarkdownRenderPlanTest : UiComponentsFunSpec() {
    private val tokenSpec = createModernMarkdownTokenSpec(Typography(), scales = TypographyScales())

    init {
        test("render plan counts top level blocks while grouping consecutive image paragraphs into one visible gallery") {
            val plan =
                createModernMarkdownRenderPlan(
                    content =
                        """
                        intro

                        ![a](one.png)

                        ![b](two.png)

                        outro
                        """.trimIndent(),
                    maxVisibleBlocks = 2,
                    knownTagsToStrip = emptyList(),
                )

            (plan.totalBlocks) shouldBe (4)
            (plan.items.size) shouldBe (2)
            plan.items.first().shouldBeInstanceOf<ModernMarkdownRenderItem.Block>()
            val gallery = plan.items[1].shouldBeInstanceOf<ModernMarkdownRenderItem.Gallery>()
            (gallery.images.map { it.destination }) shouldBe (listOf("one.png", "two.png"))
        }

        test("given media resolver claims consecutive image paragraphs when render plan is built then no top level gallery is emitted") {
            val resolver = audioPresentationResolver()
            val plan =
                createModernMarkdownRenderPlan(
                    content =
                        """
                        ![first](recordings/first.ogg)

                        ![second](recordings/second.ogg)
                        """.trimIndent(),
                    knownTagsToStrip = emptyList(),
                    mediaPresentationResolver = resolver,
                )

            (plan.totalBlocks) shouldBe (2)
            (plan.items.size) shouldBe (2)
            val blocks = plan.items.map { item -> item.shouldBeInstanceOf<ModernMarkdownRenderItem.Block>() }
            val mediaSources =
                blocks.map { block ->
                    val item =
                        buildModernParagraphItems(
                            content = plan.content,
                            paragraphNode = block.node,
                            tokenSpec = tokenSpec,
                            textStyle = tokenSpec.paragraphStyle,
                            mediaPresentationResolver = resolver,
                        ).single()
                    item.shouldBeInstanceOf<ModernParagraphItem.Media>().presentation.source
                }

            mediaSources shouldBe listOf("recordings/first.ogg", "recordings/second.ogg")
        }

        test("given no-resolver precomputed audio gallery when unlimited render state uses media resolver then gallery is split into media blocks") {
            val resolver = audioPresentationResolver()
            val precomputedPlan = createPrecomputedAudioGalleryPlan()
            precomputedPlan.items.single().shouldBeInstanceOf<ModernMarkdownRenderItem.Gallery>()

            val state =
                resolveModernMarkdownRenderState(
                    basePlan = precomputedPlan,
                    content = precomputedPlan.content,
                    maxVisibleBlocks = Int.MAX_VALUE,
                    knownTagsToStrip = emptyList<String>().toImmutableList(),
                    mediaPresentationResolver = resolver,
                )

            val ready = state.shouldBeInstanceOf<ModernMarkdownRenderState.Ready>()
            ready.plan.items.size shouldBe 2
            resolvedMediaSources(ready.plan, resolver) shouldBe
                listOf("recordings/first.ogg", "recordings/second.ogg")
        }

        test("given no-resolver precomputed audio gallery when cropped render state uses media resolver then gallery does not bypass visible block limit") {
            val resolver = audioPresentationResolver()
            val precomputedPlan = createPrecomputedAudioGalleryPlan()
            precomputedPlan.items.single().shouldBeInstanceOf<ModernMarkdownRenderItem.Gallery>()

            val state =
                resolveModernMarkdownRenderState(
                    basePlan = precomputedPlan,
                    content = precomputedPlan.content,
                    maxVisibleBlocks = 1,
                    knownTagsToStrip = emptyList<String>().toImmutableList(),
                    mediaPresentationResolver = resolver,
                )

            val ready = state.shouldBeInstanceOf<ModernMarkdownRenderState.Ready>()
            ready.plan.items.size shouldBe 1
            resolvedMediaSources(ready.plan, resolver) shouldBe listOf("recordings/first.ogg")
        }

        test("task list presentation derives line based overrides from the modern ast") {
            val content =
                """
                - [ ] todo one
                - [x] done two
                """.trimIndent()
            val root = parseModernMarkdownDocument(content)
            val listNode = root.children.first { it.type == MarkdownElementTypes.UNORDERED_LIST }
            val listItems = listNode.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }

            val firstPresentation =
                resolveModernTaskListPresentation(
                    content = content,
                    listItemNode = listItems[0],
                    todoOverrides = mapOf(1 to false),
                )
            val secondPresentation =
                resolveModernTaskListPresentation(
                    content = content,
                    listItemNode = listItems[1],
                    todoOverrides = mapOf(1 to false),
                )

            (firstPresentation.isTask) shouldBe true
            (firstPresentation.sourceLine) shouldBe (0)
            (firstPresentation.effectiveChecked) shouldBe false

            (secondPresentation.isTask) shouldBe true
            (secondPresentation.sourceLine) shouldBe (1)
            (secondPresentation.effectiveChecked) shouldBe false
        }

        test("known tag sanitizing strips plain text tags but preserves headings links and code") {
            val sanitized =
                sanitizeModernMarkdownKnownTags(
                    content =
                        """
                        # Heading #todo

                        [jump #todo](https://example.com/#todo)

                        ```kotlin
                        val raw = "#todo"
                        ```

                        plain body #todo line
                        """.trimIndent(),
                    tags = listOf("todo"),
                )

            (sanitized.content.contains("# Heading #todo")) shouldBe true
            (sanitized.content.contains("[jump #todo](https://example.com/#todo)")) shouldBe true
            (sanitized.content.contains("val raw = \"#todo\"")) shouldBe true
            (sanitized.content.contains("plain body line")) shouldBe true
            (sanitized.content.contains("plain body #todo line")) shouldBe false
            (sanitized.reusableRoot) shouldBe (null)
        }

        test("known tag sanitizing returns reusable ast when content stays unchanged") {
            val sanitized =
                sanitizeModernMarkdownKnownTags(
                    content =
                        """
                        # Heading #todo

                        [jump #todo](https://example.com/#todo)
                        """.trimIndent(),
                    tags = listOf("todo"),
                )

            (sanitized.content) shouldBe ("""
                # Heading #todo

                [jump #todo](https://example.com/#todo)
                """.trimIndent())
            (sanitized.reusableRoot != null) shouldBe true
        }

        test("render plan prunes blank paragraph blocks left behind after known tag stripping") {
            val plan =
                createModernMarkdownRenderPlan(
                    content =
                        """
                        #todo #work

                        body line
                        """.trimIndent(),
                    knownTagsToStrip = listOf("todo", "work"),
                )

            (plan.totalBlocks) shouldBe (1)
            (plan.items.size) shouldBe (1)
            (plan.content.contains("body line")) shouldBe true
        }

        test("known tag sanitizing also strips reminder tokens from plain text but preserves headings links and code") {
            val sanitized =
                sanitizeModernMarkdownKnownTags(
                    content =
                        """
                        # Heading @2026-05-22-16:00

                        [jump @2026-05-22-16:00](https://example.com/#todo)

                        ```kotlin
                        val raw = "@2026-05-22-16:00"
                        ```

                        plain body @2026-05-22-16:00 line
                        plain body @2026-05-22-16:00x3 line
                        plain body @2026-05-22-16:00x3.2 line
                        plain body @2026-05-22-16:00.done line
                        plain body @2026-05-22-16:00x3i5rd.1 line
                        plain body @2026-05-22-16:00rw line
                        """.trimIndent(),
                    tags = emptyList(),
                )

            sanitized.content.contains("# Heading @2026-05-22-16:00") shouldBe true
            sanitized.content.contains("[jump @2026-05-22-16:00]") shouldBe true
            sanitized.content.contains("val raw = \"@2026-05-22-16:00\"") shouldBe true
            sanitized.content.contains("plain body line") shouldBe true
            sanitized.content.contains("plain body @2026-05-22-16:00") shouldBe false
            sanitized.content.contains("plain body @2026-05-22-16:00x3") shouldBe false
            sanitized.content.contains("plain body @2026-05-22-16:00x3.2") shouldBe false
            sanitized.content.contains("plain body @2026-05-22-16:00.done") shouldBe false
            sanitized.content.contains("plain body @2026-05-22-16:00x3i5rd.1") shouldBe false
            sanitized.content.contains("plain body @2026-05-22-16:00rw") shouldBe false
        }

        test("direct stripReminderTokens test") {
            val res = stripReminderTokens("plain body @2026-05-22-16:00 line")
            res shouldBe "plain body line"
        }

        test("buildMemoCardCollapsedSummary strips reminder tokens from the output text") {
            val content = "buy milk @2026-05-22-16:00 today"
            val summary = buildMemoCardCollapsedSummary(content, tags = emptyList())
            summary shouldBe "buy milk today"
        }

        test("given tag sanitization runs when executed then no host filesystem debug file is written") {
            val debugFile = java.io.File("/home/ephemeral/Projects/lomo/debug_segments.txt")
            if (debugFile.exists()) {
                debugFile.delete()
            }

            sanitizeModernMarkdownKnownTags(
                content = "some #todo content @2026-05-22-16:00",
                tags = listOf("todo"),
            )

            debugFile.exists() shouldBe false
        }
    }

    private fun audioPresentationResolver(): MarkdownMediaPresentationResolver = { image ->
        if (image.destination.endsWith(".ogg")) {
            MarkdownMediaPresentation(
                source = image.destination,
                description = image.title,
                kind = "audio",
            )
        } else {
            null
        }
    }

    private fun createPrecomputedAudioGalleryPlan(): ModernMarkdownRenderPlan =
        createModernMarkdownRenderPlan(
            content =
                """
                ![first](recordings/first.ogg)

                ![second](recordings/second.ogg)
                """.trimIndent(),
            knownTagsToStrip = emptyList(),
        )

    private fun resolvedMediaSources(
        plan: ModernMarkdownRenderPlan,
        resolver: MarkdownMediaPresentationResolver,
    ): List<String> =
        plan.items.map { item ->
            val block = item.shouldBeInstanceOf<ModernMarkdownRenderItem.Block>()
            val paragraphItem =
                buildModernParagraphItems(
                    content = plan.content,
                    paragraphNode = block.node,
                    tokenSpec = tokenSpec,
                    textStyle = tokenSpec.paragraphStyle,
                    mediaPresentationResolver = resolver,
                ).single()
            paragraphItem.shouldBeInstanceOf<ModernParagraphItem.Media>().presentation.source
        }
}
