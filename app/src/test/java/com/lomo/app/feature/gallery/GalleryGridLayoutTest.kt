package com.lomo.app.feature.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: GalleryGridLayout planner.
 * - Behavior focus: aspect classification boundaries and bento highlight cadence.
 * - Observable outcomes: planned aspect kind, selected first image URL, highlight flag, aspect ratio.
 * - Red phase: Fails before the feature because GalleryGridLayout types and planner do not exist.
 * - Excludes: Compose staggered-grid rendering, image decoding, shared-transition animation.
 */
class GalleryGridLayoutTest {
    @Test
    fun `planGalleryLayout classifies aspect boundaries inclusively`() {
        val memos =
            listOf(
                galleryLayoutInput("portrait", "/images/portrait.jpg"),
                galleryLayoutInput("square-low", "/images/square-low.jpg"),
                galleryLayoutInput("square-high", "/images/square-high.jpg"),
                galleryLayoutInput("landscape", "/images/landscape.jpg"),
            )

        val layout =
            planGalleryLayout(
                memos = memos,
                aspectByMemoId =
                    mapOf(
                        "portrait" to 0.69f,
                        "square-low" to 0.7f,
                        "square-high" to 1.4f,
                        "landscape" to 1.41f,
                    ),
                highlightStride = 0,
            )

        assertEquals(GalleryAspectKind.Portrait, layout[0].aspectKind)
        assertEquals(0.69f, layout[0].aspectRatio)
        assertEquals("/images/portrait.jpg", layout[0].firstImageUrl)
        assertEquals(GalleryAspectKind.Square, layout[1].aspectKind)
        assertEquals(GalleryAspectKind.Square, layout[2].aspectKind)
        assertEquals(GalleryAspectKind.Landscape, layout[3].aspectKind)
    }

    @Test
    fun `planGalleryLayout highlights every eighth square memo only`() {
        val memos = (0..16).map { index -> galleryLayoutInput("memo-$index", "/images/$index.jpg") }
        val aspectByMemoId =
            memos.associate { input ->
                input.memoId to
                    when (input.memoId) {
                        "memo-8" -> 0.6f
                        else -> 1f
                    }
            }

        val layout =
            planGalleryLayout(
                memos = memos,
                aspectByMemoId = aspectByMemoId,
                highlightStride = 8,
            )

        assertTrue(layout[0].isHighlight)
        assertFalse(layout[8].isHighlight)
        assertTrue(layout[16].isHighlight)
        assertEquals(1f, layout[0].aspectRatio)
        assertEquals(GalleryAspectKind.Portrait, layout[8].aspectKind)
    }

    @Test
    fun `planGalleryLayout falls back to square aspect for unresolved images`() {
        val layout =
            planGalleryLayout(
                memos = listOf(galleryLayoutInput("memo", "/images/memo.jpg")),
                aspectByMemoId = emptyMap(),
                highlightStride = 0,
            )

        assertEquals(GalleryAspectKind.Square, layout.single().aspectKind)
        assertEquals(1f, layout.single().aspectRatio)
    }
}

