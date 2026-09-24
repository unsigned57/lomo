package com.lomo.app.feature.gallery

import android.content.ContentResolver
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: GalleryImageDimensionResolver.
 * - Owning layer: app
 * - Capability: resolved image aspects stay available when the gallery screen is recreated.
 *
 * Scenarios:
 * - Given an aspect resolved by an earlier resolver, when a new resolver instance resolves the
 *   same image, then it starts with the previously resolved aspect.
 *
 * Observable outcomes: a new resolver instance starts with an aspect resolved by an earlier resolver.
 *
 * TDD proof:
 * - Fails before the fix because the resolver cache is instance-local, so returning from reel
 *   exposes an empty aspect map.
 *
 * Excludes: BitmapFactory decoding details, ContentResolver I/O, Compose rendering, navigation wiring.
 * Test Change Justification:
 * - Reason category: behavior contract clarified for shared aspect cache.
 * - Old behavior/assertion being replaced: instance-local aspect resolution assumption.
 * - Why old assertion is no longer correct: the aspect cache is shared so recreation keeps resolved aspects.
 * - Coverage preserved by: the cross-instance aspect resolution case.
 * - Why this is not fitting the test to the implementation: the shared-cache behavior is the intended product contract.
 */
class GalleryImageDimensionResolverTest : AppFunSpec() {
    init {
        test("new resolver instance starts with previously resolved aspect") {
            runTest {
                val imageUrl = "https://example.com/gallery-cache-proof.jpg"
                val firstResolver = GalleryImageDimensionResolver(mockk())

                (firstResolver.resolve(imageUrl)) shouldBe (GALLERY_DEFAULT_ASPECT_RATIO)

                val recreatedResolver = GalleryImageDimensionResolver(mockk())

                (recreatedResolver.aspectFlow.value[imageUrl]) shouldBe (GALLERY_DEFAULT_ASPECT_RATIO)
            }
        }
    }

}
