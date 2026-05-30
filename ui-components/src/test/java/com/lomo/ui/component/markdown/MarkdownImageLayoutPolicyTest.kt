/*
 * Behavior Contract:
 * - Unit under test: markdown image layout policy.
 * - Owning layer: ui-components
 * - Priority tier: P2
 * - Capability: keep memo image blocks measurably stable during fast LazyColumn scrolling.
 *
 * Scenarios:
 * - Given an image has no cached or freshly decoded aspect ratio, when it enters the list, then a
 *   bounded fallback ratio is used instead of an unconstrained image block.
 * - Given a valid cached ratio exists, when the image is loading again, then the cached ratio wins.
 * - Given a freshly decoded ratio exists, when success is rendered, then the fresh ratio wins.
 * - Given malformed ratio input, when layout is resolved, then fallback ratio is used.
 *
 * Observable outcomes:
 * - resolved aspect ratio value applied by markdown image blocks.
 *
 * TDD proof:
 * - Fails before the fix because MarkdownImageBlock only applies aspectRatio after a successful
 *   decode, leaving rapidly recycled list images without a stable bounded layout.
 *
 * Excludes:
 * - Coil decoder internals, bitmap memory cache behavior, and Compose rendering pixels.
 */

package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.floats.shouldBeExactly

class MarkdownImageLayoutPolicyTest : UiComponentsFunSpec() {
    init {
        test("missing ratios resolve to stable fallback ratio") {
            resolveMarkdownImageLayoutRatio(
                cachedRatio = null,
                freshRatio = null,
            ).shouldBeExactly(DEFAULT_MARKDOWN_IMAGE_LAYOUT_RATIO)
        }

        test("cached ratio wins while image reloads") {
            resolveMarkdownImageLayoutRatio(
                cachedRatio = 1.5f,
                freshRatio = null,
            ).shouldBeExactly(1.5f)
        }

        test("fresh ratio wins over cached ratio after decode") {
            resolveMarkdownImageLayoutRatio(
                cachedRatio = 1.5f,
                freshRatio = 0.75f,
            ).shouldBeExactly(0.75f)
        }

        test("invalid ratios fall back to stable ratio") {
            resolveMarkdownImageLayoutRatio(
                cachedRatio = Float.NaN,
                freshRatio = Float.POSITIVE_INFINITY,
            ).shouldBeExactly(DEFAULT_MARKDOWN_IMAGE_LAYOUT_RATIO)
        }
    }
}
