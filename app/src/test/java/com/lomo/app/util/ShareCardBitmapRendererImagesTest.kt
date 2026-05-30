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
 */
package com.lomo.app.util

import android.content.Context
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class ShareCardBitmapRendererImagesTest : AppFunSpec() {
    init {
        test("given remote image paths when resolving decode source then share card skips network image loading") {
            resolveShareCardImageDecodeSource("https://example.invalid/card.png") shouldBe ShareCardImageDecodeSource.Unsupported
            resolveShareCardImageDecodeSource("HTTP://example.invalid/card.png") shouldBe ShareCardImageDecodeSource.Unsupported
        }

        test("given only remote image slots when loading share images then no bitmap is loaded") {
            val context = mockk<Context>()

            val loaded =
                loadShareImages(
                    context = context,
                    resolvedImagePaths =
                        listOf(
                            "https://example.invalid/card.png",
                            "http://example.invalid/card-2.png",
                        ),
                    totalImageSlots = 2,
                    targetWidth = 320,
                )

            loaded shouldBe emptyMap<Int, android.graphics.Bitmap>()
        }

        test("given local image paths when resolving decode source then share card keeps local decode") {
            resolveShareCardImageDecodeSource("content://com.lomo.app/images/1") shouldBe
                ShareCardImageDecodeSource.ContentUri
            resolveShareCardImageDecodeSource("file:///storage/emulated/0/Pictures/card.png") shouldBe
                ShareCardImageDecodeSource.FileUri
            resolveShareCardImageDecodeSource("/storage/emulated/0/Pictures/card.png") shouldBe
                ShareCardImageDecodeSource.FilePath
        }
    }
}
