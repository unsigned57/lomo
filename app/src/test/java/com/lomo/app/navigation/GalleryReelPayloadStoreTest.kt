package com.lomo.app.navigation

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: GalleryReelPayloadStore.
 * - Behavior focus: gallery reel snapshot retention, LRU capacity, and TTL pruning.
 * - Observable outcomes: restored memo id order, restored aspect map, eviction, expiration.
 * - Red phase: Fails before the feature because GalleryReelPayloadStore and its test clock hooks do not exist.
 * - Excludes: navigation graph rendering, MainViewModel list collection, route serialization.
 */
class GalleryReelPayloadStoreTest : AppFunSpec() {
    private var nowMillis = 0L

    init {
        afterTest {
            GalleryReelPayloadStore.clearForTest()
        }
    }

    init {
        test("get returns stored gallery snapshot without consuming it") {
            GalleryReelPayloadStore.setClockForTest { nowMillis }
            val payload =
                GalleryReelPayloadStore.Payload(
                    memoIds = listOf("b", "a", "c"),
                    aspectByMemoId = mapOf("a" to 1.2f, "b" to 0.8f),
                )

            val key = GalleryReelPayloadStore.put(payload)

            (GalleryReelPayloadStore.get(key)) shouldBe (payload)
            (GalleryReelPayloadStore.get(key)) shouldBe (payload)
        }
    }

    init {
        test("get prunes expired payloads") {
            GalleryReelPayloadStore.setClockForTest { nowMillis }
            val key =
                GalleryReelPayloadStore.put(
                    GalleryReelPayloadStore.Payload(
                        memoIds = listOf("memo"),
                        aspectByMemoId = emptyMap(),
                    ),
                )

            nowMillis = GalleryReelPayloadStore.ENTRY_TTL_MILLIS_FOR_TEST + 1L

            (GalleryReelPayloadStore.get(key)) shouldBe null
        }
    }

    init {
        test("put evicts least recently used payload when capacity is exceeded") {
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
            (GalleryReelPayloadStore.get(keys.first())?.memoIds) shouldBe (listOf("memo-0"))

            val newKey =
                GalleryReelPayloadStore.put(
                    GalleryReelPayloadStore.Payload(
                        memoIds = listOf("new"),
                        aspectByMemoId = emptyMap(),
                    ),
                )

            (GalleryReelPayloadStore.get(keys.first())?.memoIds) shouldBe (listOf("memo-0"))
            (GalleryReelPayloadStore.get(keys[1])) shouldBe null
            (GalleryReelPayloadStore.get(newKey)?.memoIds) shouldBe (listOf("new"))
        }
    }

}

