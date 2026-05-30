package com.lomo.ui.text

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Behavior Contract:
 * - Unit under test: MemoTextRenderingPolicy in the ui-components text layer.
 * - Owning layer: ui-components.
 * - Priority tier: P2.
 * - Capability: choose the platform Text renderer only for static plain paragraphs and route
 *   custom memo text capabilities through the custom canvas/layout engine with an explicit
 *   layout-cache boundary.
 *
 * Scenarios:
 * - Given static plain paragraph text, when rendering policy is resolved, then platform Text is selected.
 * - Given selection, tap handling, links, inline styles, or search highlights, when rendering policy
 *   is resolved, then the custom canvas text engine is selected with the triggering capability.
 * - Given custom engine rendering, when layout caching is planned, then the cache key is built
 *   from the production custom layout input and excludes interaction-only runtime state.
 * - Given custom layout input fields change, when a production cache key is built, then every
 *   layout-affecting field participates in the key.
 *
 * Observable outcomes:
 * - MemoTextRenderer choice, custom-engine capability reasons, layout cache field set, and
 *   production MemoTextLayoutInput-to-MemoTextLayoutCacheKey equality/inequality.
 *
 * TDD proof:
 * - Fails before the 4K follow-up because cache key assertions manually construct a data class
 *   instead of calling the production custom layout cache-key builder.
 *
 * Excludes:
 * - Canvas pixel output, Compose semantics tree inspection, Android TextView measurement, and
 *   markdown block traversal.
 */
class MemoTextRenderingPolicyTest : UiComponentsFunSpec() {
    init {
        test("given static plain text when policy resolves then platform text is selected") {
            val decision =
                resolveMemoTextRenderingPolicy(
                    MemoTextRenderingRequest(
                        text = "plain memo paragraph",
                    ),
                )

            decision.renderer shouldBe MemoTextRenderer.PlatformText
            decision.customEngineCapabilities shouldBe emptySet()
            decision.customLayoutCacheFields shouldBe emptySet()
        }

        test("given interaction and rich text capabilities when policy resolves then custom engine is selected") {
            val decision =
                resolveMemoTextRenderingPolicy(
                    MemoTextRenderingRequest(
                        text = "read the linked memo",
                        selectionRequired = true,
                        tapHandlingRequired = true,
                        hasLinks = true,
                        hasInlineStyles = true,
                        hasSearchHighlights = true,
                    ),
                )

            decision.renderer shouldBe MemoTextRenderer.CustomCanvasText
            decision.customEngineCapabilities shouldContainAll
                setOf(
                    MemoTextRenderingCapability.Selection,
                    MemoTextRenderingCapability.TapHandling,
                    MemoTextRenderingCapability.Links,
                    MemoTextRenderingCapability.InlineStyles,
                    MemoTextRenderingCapability.SearchHighlights,
                )
            decision.customLayoutCacheFields shouldContainAll
                setOf(
                    MemoTextLayoutCacheField.Text,
                    MemoTextLayoutCacheField.MaxWidthPx,
                    MemoTextLayoutCacheField.MaxLines,
                    MemoTextLayoutCacheField.EllipsizeLastVisibleLine,
                    MemoTextLayoutCacheField.LineHeightPx,
                    MemoTextLayoutCacheField.BaselinePx,
                    MemoTextLayoutCacheField.BaseLetterSpacingPx,
                    MemoTextLayoutCacheField.ProtectedRanges,
                )
        }

        test("given distinct interaction capabilities when production layout inputs match then cache keys match") {
            val selectableDecision =
                resolveMemoTextRenderingPolicy(
                    MemoTextRenderingRequest(
                        text = "same text",
                        selectionRequired = true,
                    ),
                )
            val tappableDecision =
                resolveMemoTextRenderingPolicy(
                    MemoTextRenderingRequest(
                        text = "same text",
                        tapHandlingRequired = true,
                    ),
                )
            val layoutInput =
                MemoTextLayoutInput(
                    text = "same text",
                    maxWidthPx = 320f,
                    maxLines = 3,
                    ellipsizeLastVisibleLine = true,
                    lineHeightPx = 24f,
                    baselinePx = 18f,
                    baseLetterSpacingPx = 0.5f,
                )

            selectableDecision.renderer shouldBe MemoTextRenderer.CustomCanvasText
            tappableDecision.renderer shouldBe MemoTextRenderer.CustomCanvasText
            selectableDecision.customLayoutCacheFields shouldBe tappableDecision.customLayoutCacheFields
            layoutInput.toMemoTextLayoutCacheKey() shouldBe layoutInput.toMemoTextLayoutCacheKey()
        }

        test("given layout-affecting input changes when production cache key is built then keys differ") {
            val baseInput =
                MemoTextLayoutInput(
                    text = "linked memo",
                    maxWidthPx = 240f,
                    maxLines = Int.MAX_VALUE,
                    ellipsizeLastVisibleLine = false,
                    lineHeightPx = 20f,
                    baselinePx = 15f,
                    baseLetterSpacingPx = 0f,
                    protectedRanges = listOf(MemoTextProtectedRange(start = 0, end = 6)),
                )
            val baseKey = baseInput.toMemoTextLayoutCacheKey()
            val withDifferentRange =
                baseInput.copy(
                    protectedRanges = listOf(MemoTextProtectedRange(start = 7, end = 11)),
                )

            resolveMemoTextRenderingPolicy(
                MemoTextRenderingRequest(
                    text = "linked memo",
                    hasLinks = true,
                ),
            ).customLayoutCacheFields shouldContain MemoTextLayoutCacheField.ProtectedRanges
            baseKey shouldNotBe baseInput.copy(text = "linked memo!").toMemoTextLayoutCacheKey()
            baseKey shouldNotBe baseInput.copy(maxWidthPx = 320f).toMemoTextLayoutCacheKey()
            baseKey shouldNotBe baseInput.copy(maxLines = 2).toMemoTextLayoutCacheKey()
            baseKey shouldNotBe baseInput.copy(ellipsizeLastVisibleLine = true).toMemoTextLayoutCacheKey()
            baseKey shouldNotBe baseInput.copy(lineHeightPx = 24f).toMemoTextLayoutCacheKey()
            baseKey shouldNotBe baseInput.copy(baselinePx = 18f).toMemoTextLayoutCacheKey()
            baseKey shouldNotBe baseInput.copy(baseLetterSpacingPx = 0.5f).toMemoTextLayoutCacheKey()
            baseKey shouldNotBe withDifferentRange.toMemoTextLayoutCacheKey()
        }
    }
}
