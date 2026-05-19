/*
 * Behavior Contract:
 * - Capability: choosing which painter a retained async image renders.
 * - Given an AsyncImage that has just switched its model:
 *   When the new model is still Loading or has gone Empty, and a previously
 *   successful painter exists, then the image must render the retained
 *   painter (no blank frame).
 *   When the new model has resolved to Success, the image must render the
 *   current painter (the freshly loaded image).
 *   When no previous success exists (first-ever load), the image must render
 *   the current painter (Coil will draw its own placeholder/empty).
 *   When the new model errored AND there is no retained success, render the
 *   current painter (Coil draws the error state); when there IS a retained
 *   success, prefer the retained painter — surfacing a stale image is
 *   intentional UX for the gallery/blur consumers.
 * - Reason: the gallery grid flickered and the gallery reel blur background
 *   showed a black frame between image switches because the existing
 *   AsyncImage drew nothing while a new model was loading.
 * - Excludes: Coil ImageLoader internals, bitmap decoding, Compose drawing.
 */

package com.lomo.ui.component.image

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

class RetainedAsyncImageSourcePolicyTest : UiComponentsFunSpec() {
    init {
        test("success state always draws the current painter") {
            resolveRetainedAsyncImageSource(
                loadState = RetainedAsyncImageLoadState.Success,
                hasRetainedSuccess = false,
            ) shouldBe RetainedAsyncImageSource.Current

            resolveRetainedAsyncImageSource(
                loadState = RetainedAsyncImageLoadState.Success,
                hasRetainedSuccess = true,
            ) shouldBe RetainedAsyncImageSource.Current
        }

        test("loading state with a retained success draws the retained painter") {
            resolveRetainedAsyncImageSource(
                loadState = RetainedAsyncImageLoadState.Loading,
                hasRetainedSuccess = true,
            ) shouldBe RetainedAsyncImageSource.Retained
        }

        test("loading state without a retained success draws the current painter") {
            resolveRetainedAsyncImageSource(
                loadState = RetainedAsyncImageLoadState.Loading,
                hasRetainedSuccess = false,
            ) shouldBe RetainedAsyncImageSource.Current
        }

        test("empty state prefers retained success when available") {
            resolveRetainedAsyncImageSource(
                loadState = RetainedAsyncImageLoadState.Empty,
                hasRetainedSuccess = true,
            ) shouldBe RetainedAsyncImageSource.Retained

            resolveRetainedAsyncImageSource(
                loadState = RetainedAsyncImageLoadState.Empty,
                hasRetainedSuccess = false,
            ) shouldBe RetainedAsyncImageSource.Current
        }

        test("error state prefers retained success to avoid a blank frame") {
            resolveRetainedAsyncImageSource(
                loadState = RetainedAsyncImageLoadState.Error,
                hasRetainedSuccess = true,
            ) shouldBe RetainedAsyncImageSource.Retained

            resolveRetainedAsyncImageSource(
                loadState = RetainedAsyncImageLoadState.Error,
                hasRetainedSuccess = false,
            ) shouldBe RetainedAsyncImageSource.Current
        }
    }
}
