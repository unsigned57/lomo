package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.testMemoUiMapper
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MemoUiMapper cached-model governance.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: the shared UI-model cache keeps rows until LRU capacity forces eviction; mapping
 *   one screen's collection must not evict rows that another screen's list still renders.
 *
 * Scenarios:
 * - Given rows cached by one list, when another screen maps a disjoint list, then the first
 *   list's rows still hit the cache instead of being dropped by a cross-screen purge.
 * - Given more distinct memos than the cache bound, when they are all mapped, then the cache
 *   stays bounded and the oldest entries are evicted.
 *
 * Observable outcomes:
 * - peekCachedUiModel hit/miss outcomes after cross-screen mapping and after over-capacity mapping,
 *   plus cacheStats hit/miss/eviction/size counters.
 *
 * TDD proof:
 * - RED for cross-screen retention: mapToUiModels purged every cached id absent from the current
 *   list, so a disjoint list wiped the first list's rows and the peek returned null.
 *
 * Excludes:
 * - Paging transforms, Compose rendering, and dispatcher lanes (covered by ViewModel suites).
 */
class MemoUiMapperCacheGovernanceTest : AppFunSpec() {
    init {
        test("given rows cached by one list when a disjoint list maps then the first rows still hit") {
            runTest {
                val mapper = testMemoUiMapper()
                val listA = listOf(memo("a-1"), memo("a-2"))
                val listB = listOf(memo("b-1"), memo("b-2"), memo("b-3"))

                mapper.mapToUiModels(listA, null, null, emptyMap())
                mapper.mapToUiModels(listB, null, null, emptyMap())

                // Another screen's list must not evict rows the first list still renders.
                mapper.peekCachedUiModel(listA[0], null, null, emptyMap(), listA[0].reminders) shouldNotBe null
                mapper.peekCachedUiModel(listA[1], null, null, emptyMap(), listA[1].reminders) shouldNotBe null
                mapper.peekCachedUiModel(listB[0], null, null, emptyMap(), listB[0].reminders) shouldNotBe null

                val stats = mapper.cacheStats()
                stats.evictions shouldBe 0L
                stats.size shouldBe 5
                stats.hits shouldBe 3L
            }
        }

        test("given more memos than the cache bound when all are mapped then the oldest entries are evicted") {
            runTest {
                val mapper = testMemoUiMapper()
                val memos = (0 until 300).map { index -> memo("m-$index") }

                mapper.mapToUiModels(memos, null, null, emptyMap())

                // 300 inserts exceed the 256-entry bound; the 44 oldest inserts are evicted.
                mapper.peekCachedUiModel(memos[0], null, null, emptyMap(), memos[0].reminders) shouldBe null
                mapper.peekCachedUiModel(memos[43], null, null, emptyMap(), memos[43].reminders) shouldBe null
                mapper.peekCachedUiModel(memos[44], null, null, emptyMap(), memos[44].reminders) shouldNotBe null
                mapper.peekCachedUiModel(memos[299], null, null, emptyMap(), memos[299].reminders) shouldNotBe null

                val stats = mapper.cacheStats()
                stats.size shouldBe 256
                stats.evictions shouldBe 44L
                stats.hits shouldBe 2L
                // 300 first-time map misses plus the two evicted peeks.
                stats.misses shouldBe 302L
            }
        }
    }

    private fun memo(id: String): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            content = "content-$id",
            rawContent = "content-$id",
            dateKey = "2026_04_02",
        )
}
