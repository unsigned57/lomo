package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: Markdown image presentation policy.
 *
 * Scenario matrix:
 * - Happy: standard happy path for MarkdownImagePresentationPolicyTest.
 * - Boundary: boundary and edge cases for MarkdownImagePresentationPolicyTest.
 * - Failure: failure and error scenarios for MarkdownImagePresentationPolicyTest.
 * - Must-not-happen: invariants are never violated for MarkdownImagePresentationPolicyTest.
 * - Behavior focus: memo images should not flash back to an empty/loading placeholder after a successful image has already been rendered.
 * - Observable outcomes: selected presentation mode for painter load states with and without retained success content.
 * - Red phase: Fails before the fix because MarkdownImageBlock directly maps Loading and Empty states to placeholders even after a previous Success.
 * - Excludes: Coil internals, bitmap decoding, Compose image drawing, and markdown parsing.
 */
class MarkdownImagePresentationPolicyTest : UiComponentsFunSpec() {
    init {
        test("first loading state shows loading placeholder when no success is retained") {
        (resolveMarkdownImagePresentation(
                loadState = MarkdownImageLoadState.Loading,
                hasRetainedSuccess = false,
            )) shouldBe (MarkdownImagePresentation.LoadingPlaceholder)
        }
    }

    init {
        test("loading and empty states keep retained success image") {
        (resolveMarkdownImagePresentation(
                loadState = MarkdownImageLoadState.Loading,
                hasRetainedSuccess = true,
            )) shouldBe (MarkdownImagePresentation.RetainedSuccess)
        (resolveMarkdownImagePresentation(
                loadState = MarkdownImageLoadState.Empty,
                hasRetainedSuccess = true,
            )) shouldBe (MarkdownImagePresentation.RetainedSuccess)
        }
    }

    init {
        test("error state still shows error placeholder even after retained success") {
        (resolveMarkdownImagePresentation(
                loadState = MarkdownImageLoadState.Error,
                hasRetainedSuccess = true,
            )) shouldBe (MarkdownImagePresentation.ErrorPlaceholder)
        }
    }
}
