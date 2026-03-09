package com.lomo.app.navigation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageViewerRoutePayloadStoreTest {
    @After
    fun tearDown() {
        ImageViewerRoutePayloadStore.clearForTest()
    }

    @Test
    fun `getImageUrls returns stored value without consuming it`() {
        val key = ImageViewerRoutePayloadStore.putImageUrls(listOf("a.jpg", "b.jpg"))

        assertEquals(listOf("a.jpg", "b.jpg"), ImageViewerRoutePayloadStore.getImageUrls(key))
        assertEquals(listOf("a.jpg", "b.jpg"), ImageViewerRoutePayloadStore.getImageUrls(key))
    }

    @Test
    fun `getImageUrls returns null for unknown key`() {
        assertNull(ImageViewerRoutePayloadStore.getImageUrls("missing"))
    }
}
