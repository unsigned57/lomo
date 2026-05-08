package com.lomo.app.navigation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: GalleryReelPayloadStore.
 * - Behavior focus: gallery reel snapshot retention, LRU capacity, and TTL pruning.
 * - Observable outcomes: restored memo id order, restored aspect map, eviction, expiration.
 * - Red phase: Fails before the feature because GalleryReelPayloadStore and its test clock hooks do not exist.
 * - Excludes: navigation graph rendering, MainViewModel list collection, route serialization.
 */
class GalleryReelPayloadStoreTest {
    private var nowMillis = 0L

    @After
    fun tearDown() {
        GalleryReelPayloadStore.clearForTest()
    }

    @Test
    fun `get returns stored gallery snapshot without consuming it`() {
        GalleryReelPayloadStore.setClockForTest { nowMillis }
        val payload =
            GalleryReelPayloadStore.Payload(
                memoIds = listOf("b", "a", "c"),
                aspectByMemoId = mapOf("a" to 1.2f, "b" to 0.8f),
            )

        val key = GalleryReelPayloadStore.put(payload)

        assertEquals(payload, GalleryReelPayloadStore.get(key))
        assertEquals(payload, GalleryReelPayloadStore.get(key))
    }

    @Test
    fun `get prunes expired payloads`() {
        GalleryReelPayloadStore.setClockForTest { nowMillis }
        val key =
            GalleryReelPayloadStore.put(
                GalleryReelPayloadStore.Payload(
                    memoIds = listOf("memo"),
                    aspectByMemoId = emptyMap(),
                ),
            )

        nowMillis = GalleryReelPayloadStore.ENTRY_TTL_MILLIS_FOR_TEST + 1L

        assertNull(GalleryReelPayloadStore.get(key))
    }

    @Test
    fun `put evicts least recently used payload when capacity is exceeded`() {
        GalleryReelPayloadStore.setClockForTest { nowMillis }
        val keys =
            (0 until GalleryReelPayloadStore.MAX_ENTRIES_FOR_TEST).map { index ->
                GalleryReelPayloadStore.put(
                    GalleryReelPayloadStore.Payload(
                        memoIds = listOf("memo-$index"),
                        aspectByMemoId = mapOf("memo-$index" to 1f),
                    ),
                )
            }
        assertEquals(listOf("memo-0"), GalleryReelPayloadStore.get(keys.first())?.memoIds)

        val newKey =
            GalleryReelPayloadStore.put(
                GalleryReelPayloadStore.Payload(
                    memoIds = listOf("new"),
                    aspectByMemoId = emptyMap(),
                ),
            )

        assertEquals(listOf("memo-0"), GalleryReelPayloadStore.get(keys.first())?.memoIds)
        assertNull(GalleryReelPayloadStore.get(keys[1]))
        assertEquals(listOf("new"), GalleryReelPayloadStore.get(newKey)?.memoIds)
    }
}

