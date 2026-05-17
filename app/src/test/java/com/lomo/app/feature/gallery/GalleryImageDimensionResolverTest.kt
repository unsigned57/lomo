package com.lomo.app.feature.gallery

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: GalleryImageDimensionResolver.
 *
 * Scenario matrix:
 * - Happy: standard happy path for GalleryImageDimensionResolverTest.
 * - Boundary: boundary and edge cases for GalleryImageDimensionResolverTest.
 * - Failure: failure and error scenarios for GalleryImageDimensionResolverTest.
 * - Must-not-happen: invariants are never violated for GalleryImageDimensionResolverTest.
 * - Behavior focus: resolved image aspects stay available when the gallery screen is recreated.
 * - Observable outcomes: a new resolver instance starts with an aspect resolved by an earlier resolver.
 * - Red phase: Fails before the fix because the resolver cache is instance-local, so returning from reel exposes an empty aspect map.
 * - Excludes: BitmapFactory decoding details, ContentResolver I/O, Compose rendering, navigation wiring.
 */
class GalleryImageDimensionResolverTest : AppFunSpec() {
    init {
        test("new resolver instance starts with previously resolved aspect") {
            runTest {
                val imageUrl = "https://example.com/gallery-cache-proof.jpg"
                val firstResolver = GalleryImageDimensionResolver()

                (firstResolver.resolve(imageUrl)) shouldBe (GALLERY_DEFAULT_ASPECT_RATIO)

                val recreatedResolver = GalleryImageDimensionResolver()

                (recreatedResolver.aspectFlow.value[imageUrl]) shouldBe (GALLERY_DEFAULT_ASPECT_RATIO)
            }
        }
    }

}
