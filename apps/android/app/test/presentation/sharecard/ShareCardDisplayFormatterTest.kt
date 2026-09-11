package com.lomo.app.presentation.sharecard

/*
 * Behavior Contract:
 * - Unit under test: ShareCardDisplayFormatter
 * - Owning layer: production path under test
 * - Priority tier: P1
 * - Capability: preserve observable product behavior after Markdown semantic ownership moved to
 *   lomo-workspace (typed IR, workspace scan/render/document commands) with Kotlin adapters only.
 *
 * Scenarios:
 * - Given production collaborators expose workspace IR / document-command seams, when this suite
 *   runs, then assertions verify the same user-visible outcomes without Kotlin MarkdownParser.
 * - Given deleted JetBrains or line-authority helpers, when tests construct fakes, then they use
 *   FakeMarkdownWorkspace / content projector adapters instead of dual-authority parsers.
 * - Given invalid or missing readiness inputs, when exercised, then fail-closed outcomes remain.
 *
 * Observable outcomes:
 * - Public method results, DI wiring, and presentation fields match the post-cutover contracts.
 *
 * TDD proof:
 * - RED: suites fail to compile or assert against MarkdownParser / JetBrains plan types after cutover.
 * - GREEN: ./kotlin test on this class passes against workspace IR adapters.
 *
 * Excludes:
 * - Room schema ownership, sync backend redesign, and Compose pixel rendering.
 *
 * Test Change Justification:
 * - Reason category: production Markdown ownership cutover to Rust workspace IR / document commands.
 * - Old behavior/assertion being replaced: tests that assumed Kotlin MarkdownParser, MemoTextProcessor,
 *   JetBrains render plans, or dual-authority analysis helpers as production collaborators.
 * - Why old assertion is no longer correct: production storage analysis and presentation consume
 *   lomo-workspace typed IR and workspace adapters; the deleted Kotlin/JetBrains authorities are gone.
 * - Coverage preserved by: the same observable product outcomes (mapping, mutation gates, DI wiring,
 *   share/card presentation) re-asserted against FakeMarkdownWorkspace / IR / projector seams.
 * - Why this is not fitting the test to the implementation: assertions still check public behavior and
 *   fail-closed boundaries, not private parser implementation details.
 */

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class ShareCardDisplayFormatterTest : AppFunSpec() {
    private val formatter = ShareCardDisplayFormatter()

    init {
        test("formatTagsForDisplay trims hashes deduplicates truncates and caps count") {
            val tags =
                listOf(
                    "  #Android  ",
                    "#Android",
                    "",
                    "   ",
                    "#Compose",
                    "VeryLongTagNameThatShouldBeTrimmed",
                    "#Sync",
                    "#Git",
                    "#Offline",
                    "#Extra",
                    "#Ignored",
                )

            val result = formatter.formatTagsForDisplay(tags)

            result shouldBe
                listOf(
                    "Android",
                    "Compose",
                    "VeryLongTagNameTha",
                    "Sync",
                    "Git",
                    "Offline",
                )
        }

        test("formatBodyText replaces bare audio and image path tokens from owner plain text") {
            val result =
                formatter.formatBodyText(
                    plainBodyText =
                        """
                        Title line
                        voice_01.m4a
                        cover.png
                        """.trimIndent(),
                    audioPlaceholder = "[Audio]",
                    imagePlaceholder = "[Photo]",
                    imageNamedPlaceholderPattern = "[Photo: %s]",
                )

            result shouldBe
                """
                Title line
                [Audio]
                [Photo]
                """.trimIndent()
        }

        test("formatBodyText replaces named image placeholders") {
            val result =
                formatter.formatBodyText(
                    plainBodyText = "[Image: screenshot]",
                    audioPlaceholder = "[Audio]",
                    imagePlaceholder = "[Photo]",
                    imageNamedPlaceholderPattern = "[Photo: %s]",
                )

            result shouldBe "[Photo: screenshot]"
        }
    }
}
