package com.lomo.app.navigation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareRoutePayloadStoreTest {
    @After
    fun tearDown() {
        ShareRoutePayloadStore.clearForTest()
    }

    @Test
    fun `consumeMemoContent returns stored value once`() {
        val key = ShareRoutePayloadStore.putMemoContent("memo body")

        assertEquals("memo body", ShareRoutePayloadStore.consumeMemoContent(key))
        assertNull(ShareRoutePayloadStore.consumeMemoContent(key))
    }

    @Test
    fun `consumeMemoContent returns null for unknown key`() {
        assertNull(ShareRoutePayloadStore.consumeMemoContent("missing"))
    }
}
