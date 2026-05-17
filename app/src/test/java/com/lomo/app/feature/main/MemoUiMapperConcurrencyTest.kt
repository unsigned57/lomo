package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/*
 * Test Contract:
 * - Unit under test: MemoUiMapper
 * - Behavior focus: thread-safety of the shared LRU cache when uiMemos / galleryUiMemos / pagedUiMemos flows
 *   concurrently invoke mapToUiModels on Dispatchers.Default.
 * - Observable outcomes: no ConcurrentModificationException, every concurrent call returns a result list
 *   whose size matches its input, and the per-index Memo identity is preserved on the result.
 * - Red phase: Fails before the fix because cachedModels (LinkedHashMap) is mutated by retainAll, put, and
 *   the LRU trim loop without any synchronization, producing a ConcurrentModificationException (or a
 *   corrupted result list) once the parallel workload churns the LRU eviction threshold.
 * - Excludes: markdown rendering correctness, image map resolution semantics, header recovery semantics
 *   (covered by sibling MemoUiMapperTest / MemoUiMapperStorageHeaderRecoveryTest).
 */
class MemoUiMapperConcurrencyTest : AppFunSpec() {
    init {
        test("mapToUiModels survives concurrent invocations that churn the LRU cache") {
            runBlocking { exerciseConcurrentMapping() }
        }
    }

    private suspend fun exerciseConcurrentMapping() {
        val mapper = MemoUiMapper(Dispatchers.Default)
        // DEFAULT_CACHE_SIZE is 256; use enough memos that retainAll churns plus eviction kicks in.
        val totalMemos = 320
        val memos = (1..totalMemos).map { index ->
            Memo(
                id = "memo-$index",
                timestamp = 0L,
                content = "x",
                rawContent = "x",
                dateKey = "2026_02_23",
                tags = emptyList(),
            )
        }
        // Pass a non-empty prioritizedMemoIds with no real matches so the mapper skips the
        // expensive markdown precompute path; we are exercising the cache, not rendering.
        val skipPrecomputeIds = setOf("__cache-only__")

        val parallelism = 4
        val iterationsPerWorker = 50
        val subsetSize = 30

        coroutineScope {
            (0 until parallelism).map { workerIndex ->
                async(Dispatchers.Default) {
                    repeat(iterationsPerWorker) { iteration ->
                        val offset = (workerIndex * 31 + iteration * 17) % totalMemos
                        val subset = List(subsetSize) { i ->
                            memos[(offset + i) % totalMemos]
                        }
                        val result = mapper.mapToUiModels(
                            memos = subset,
                            rootPath = null,
                            imagePath = null,
                            imageMap = emptyMap(),
                            prioritizedMemoIds = skipPrecomputeIds,
                        )
                        (result.size) shouldBe (subset.size)
                        result.forEachIndexed { index, model ->
                            (model.memo.id) shouldBe (subset[index].id)
                        }
                    }
                }
            }.awaitAll()
        }
    }
}
