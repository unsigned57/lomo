package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given a first image load without retained success, when presentation is resolved, then the loading
 *   placeholder is shown.
 * - Given retained success during loading or empty state, when presentation is resolved, then the successful
 *   image remains; an error state selects the error placeholder.
 * - Unit under test: Markdown image presentation policy.
 *
 * - Capability: memo images should not flash back to an empty/loading placeholder after a successful image has already been rendered.
 * - Observable outcomes: selected presentation mode for painter load states with and without retained success content.
 * - TDD proof: Fails before the fix because MarkdownImageBlock directly maps Loading and Empty states to placeholders even after a previous Success.
 * - Excludes: Coil internals, bitmap decoding, Compose image drawing, and markdown parsing.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for MarkdownImagePresentationPolicy.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including first loading state shows loading placeholder when
 * no success is retained; loading and empty states keep retained success image.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class MarkdownImagePresentationPolicyTest : UiComponentsFunSpec() {
    init {
        test("first loading state shows loading placeholder when no success is retained") {
            (
                resolveMarkdownImagePresentation(
                    loadState = MarkdownImageLoadState.Loading,
                    hasRetainedSuccess = false,
                )
                ) shouldBe (MarkdownImagePresentation.LoadingPlaceholder)
        }
    }

    init {
        test("loading and empty states keep retained success image") {
            (
                resolveMarkdownImagePresentation(
                    loadState = MarkdownImageLoadState.Loading,
                    hasRetainedSuccess = true,
                )
                ) shouldBe (MarkdownImagePresentation.RetainedSuccess)
            (
                resolveMarkdownImagePresentation(
                    loadState = MarkdownImageLoadState.Empty,
                    hasRetainedSuccess = true,
                )
                ) shouldBe (MarkdownImagePresentation.RetainedSuccess)
        }
    }

    init {
        test("error state still shows error placeholder even after retained success") {
            (
                resolveMarkdownImagePresentation(
                    loadState = MarkdownImageLoadState.Error,
                    hasRetainedSuccess = true,
                )
                ) shouldBe (MarkdownImagePresentation.ErrorPlaceholder)
        }
    }
}
