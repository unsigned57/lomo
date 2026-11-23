/*
 * Behavior Contract:
 * - Unit under test: share-card image decode-source policy.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: share-card image rendering must decode only local/content/file
 *   image references and must skip remote HTTP(S) references without network I/O.
 *
 * Scenarios:
 * - Given a remote HTTP(S) image reference, when decode source is resolved, then
 *   it is rejected for share-card bitmap rendering.
 * - Given a content URI, file URI, or filesystem path, when decode source is
 *   resolved, then it is classified for local decode.
 *
 * Observable outcomes:
 * - returned ShareCardImageDecodeSource classification.
 *
 * TDD proof:
 * - RED before the fix because share-card image loading has no decode-source
 *   policy and routes HTTP(S) paths to HttpURLConnection.
 *
 * Excludes:
 * - Android BitmapFactory behavior, actual PNG/JPEG bytes, ContentResolver I/O,
 *   filesystem permissions, and share sheet dispatch.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for ShareCardBitmapRendererImages.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including given remote image paths when resolving decode
 * source then share card skips network image loading; given only remote image slots when loading share images
 * then no bitmap is loaded.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
package com.lomo.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class ShareCardBitmapRendererImagesTest : AppFunSpec() {
    init {
        test("given remote image paths when resolving decode source then share card skips network image loading") {
            resolveShareCardImageDecodeSource(
                "https://example.invalid/card.png",
            ) shouldBe ShareCardImageDecodeSource.Unsupported
            resolveShareCardImageDecodeSource(
                "HTTP://example.invalid/card.png",
            ) shouldBe ShareCardImageDecodeSource.Unsupported
        }

        test("given only remote image slots when loading share images then no bitmap is loaded") {
            val context = mockk<Context>()

            val loaded =
                loadShareImagesByDestination(
                    context = context,
                    imageDestinations =
                    listOf(
                        "https://example.invalid/card.png",
                        "http://example.invalid/card-2.png",
                    ),
                    resolvedImagePaths =
                    listOf(
                        "https://example.invalid/card.png",
                        "http://example.invalid/card-2.png",
                    ),
                    targetWidth = 320,
                )

            loaded shouldBe emptyMap<String, Bitmap>()
        }

        test("given local image paths when resolving decode source then share card keeps local decode") {
            resolveShareCardImageDecodeSource("content://com.lomo.app/images/1") shouldBe
                ShareCardImageDecodeSource.ContentUri
            resolveShareCardImageDecodeSource("file:///storage/emulated/0/Pictures/card.png") shouldBe
                ShareCardImageDecodeSource.FileUri
            resolveShareCardImageDecodeSource("/storage/emulated/0/Pictures/card.png") shouldBe
                ShareCardImageDecodeSource.FilePath
        }

        test("given image bounds when building decode options then software-renderable ARGB_8888 is requested") {
            val bounds =
                BitmapFactory.Options().apply {
                    outWidth = 2000
                    outHeight = 1000
                }

            val decodeOptions = bounds.toDecodeOptions(targetWidth = 900)

            decodeOptions.inPreferredConfig shouldBe Bitmap.Config.ARGB_8888
            decodeOptions.inSampleSize shouldBe 1
        }
    }
}
