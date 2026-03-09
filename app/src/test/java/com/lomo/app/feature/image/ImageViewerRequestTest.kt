package com.lomo.app.feature.image

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageViewerRequestTest {
    @Test
    fun `createImageViewerRequest keeps clicked image index`() {
        val request =
            createImageViewerRequest(
                imageUrls = listOf("a.jpg", "b.jpg", "c.jpg"),
                clickedUrl = "b.jpg",
            )

        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), request.imageUrls)
        assertEquals(1, request.initialIndex)
    }

    @Test
    fun `createImageViewerRequest falls back to first image when clicked url is missing`() {
        val request =
            createImageViewerRequest(
                imageUrls = listOf("a.jpg", "b.jpg"),
                clickedUrl = "missing.jpg",
            )

        assertEquals(listOf("a.jpg", "b.jpg"), request.imageUrls)
        assertEquals(0, request.initialIndex)
    }
}
