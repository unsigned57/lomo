/*
 * Behavior Contract:
 * - Capability: shared cache-key policy for Coil image requests in lomo.
 * - Given an image url, when lomoSharedKeyImageRequest builds the ImageRequest,
 *   then memoryCacheKey and placeholderMemoryCacheKey both expose the url as
 *   their key, and the request data equals the url.
 * - Reason: the gallery grid (and other image consumers) must hit the same
 *   cache slot that ImagePreloadRequest writes; the previous bug was that
 *   consumers used the implicit (url + size) key while the preloader used
 *   memoryCacheKey(url), so the cache never hit on cold entry.
 * - Excludes: actual image fetch / decode behavior (Coil-internal).
 */

package com.lomo.app.feature.image

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


import android.content.Context
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class LomoImageRequestTest : AppFunSpec() {
    init {
        test("lomoSharedKeyImageRequest uses the url as both memory and placeholder cache keys") {
            val url = "file:///lomo/sample.jpg"
            val context = mockk<Context>(relaxed = true)

            val request = lomoSharedKeyImageRequest(context = context, url = url)

            request.memoryCacheKey shouldBe url
            request.placeholderMemoryCacheKey?.key shouldBe url
            request.data shouldBe url
        }
    }
}
