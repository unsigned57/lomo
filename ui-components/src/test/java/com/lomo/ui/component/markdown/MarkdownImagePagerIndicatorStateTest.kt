/*
 * Behavior Contract:
 * - Unit under test: markdown image pager indicator state policy.
 * - Owning layer: ui-components
 * - Priority tier: P2
 * - Capability: keep memo multi-image pager dots synchronized with the visible image page.
 *
 * Scenarios:
 * - Given a multi-image pager, when the current page changes, then the active indicator page follows it.
 * - Given the image count shrinks, when the previous current page is outside the new range, then the
 *   active indicator page clamps to the last available page.
 * - Given no images, when indicator state is resolved, then no renderable indicator state is produced.
 *
 * Observable outcomes:
 * - resolved indicator state currentPage and pageCount values.
 *
 * TDD proof:
 * - Fails before the fix because MarkdownImagePager has no owned indicator state policy and each dot
 *   reads PagerState.currentPage directly during composition.
 *
 * Excludes:
 * - Compose gesture dispatch, image decoding, and pixel-perfect dot rendering.
 */

package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

class MarkdownImagePagerIndicatorStateTest : UiComponentsFunSpec() {
    init {
        test("current page changes update the active indicator page") {
            resolveMarkdownImagePagerIndicatorState(
                currentPage = 2,
                pageCount = 4,
            ) shouldBe MarkdownImagePagerIndicatorState(currentPage = 2, pageCount = 4)
        }

        test("current page clamps when the image count shrinks") {
            resolveMarkdownImagePagerIndicatorState(
                currentPage = 5,
                pageCount = 3,
            ) shouldBe MarkdownImagePagerIndicatorState(currentPage = 2, pageCount = 3)
        }

        test("empty image pager has no renderable indicator state") {
            resolveMarkdownImagePagerIndicatorState(
                currentPage = 0,
                pageCount = 0,
            ) shouldBe null
        }
    }
}
