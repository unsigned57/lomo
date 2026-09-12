package com.lomo.data.engine.store

/*
 * Behavior Contract:
 * - Unit under test: StorePagingSource + StorePort (fake).
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: bounded memo page loads through the store port with identity refresh keys,
 *   exclusive append/prepend cursors, and positional placeholder counts.
 *
 * Scenarios:
 * - Given a first page with a next cursor, when load runs, then items and next key are returned.
 * - Given a subsequent cursor, when load runs, then the following page is returned.
 * - Given the store throws, when load runs, then LoadResult.Error is returned.
 * - Given a registered source, when a matching store commit is published, then the source is invalidated.
 * - Given a viewport away from the true head, when getRefreshKey runs, then the closest memo id is returned.
 * - Given the true head, when getRefreshKey runs, then null is returned so refresh starts at head.
 * - Given a refresh identity, when load runs, then the store is queried from that memo id.
 * - Given an append cursor, when load runs, then the store is queried forward from that cursor.
 * - Given a prepend cursor, when load runs, then the store is queried backward from that cursor.
 *
 * Observable outcomes:
 * - PagingSource LoadResult page items, next/prev keys, itemsBefore/itemsAfter, Error, and refresh keys.
 *
 * TDD proof:
 * - Fails before StorePagingSource maps StorePort pages and failures into Paging LoadResult.
 * - A-PAGING-001 RED: StoreInvalidationBus previously advanced only a Flow tick and left active
 *   PagingSource instances valid after a workspace mutation/rebuild.
 * - Fails while getRefreshKey is null and LoadResult.Page omits itemsBefore/itemsAfter.
 *
 * Excludes:
 * - Real BoltFFI handle lifecycle and device UI scrolling.
 *
 * Test Change Justification:
 * - Reason category: systemic behavior replacement.
 * - Old behavior/assertion being replaced: Refresh.key was unused and LoadResult.Page omitted ranks.
 * - Why old assertion is no longer correct: user position is memo identity plus rank in the current
 *   query; exclusive cursors stay on append/prepend only.
 * - Coverage preserved by: first/next page keys, LoadResult.Error, and invalidation still asserted.
 * - Why this is not fitting the test to the implementation: asserts store start encoding and
 *   placeholder counts that Paging3 consumes, not paging-library internals.
 */

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.repository.StoreInvalidationBus
import kotlinx.coroutines.CancellationException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private class FakeStorePort : StorePort {
    var pages: MutableList<StoreMemoPage> = mutableListOf()
    var throwOnLoad: Boolean = false
    var cancelOnLoad: Boolean = false
    var lastCursor: StorePageCursor? = null
    var lastStartMemoId: String? = null
    var lastBackward: Boolean = false

    override fun queryMemos(
        query: StoreMemoQuery,
        cursor: StorePageCursor?,
        pageSize: Int,
        startMemoId: String?,
        backward: Boolean,
    ): StoreMemoPage {
        lastCursor = cursor
        lastStartMemoId = startMemoId
        lastBackward = backward
        if (cancelOnLoad) throw CancellationException("caller cancelled")
        if (throwOnLoad) error("store unavailable")
        return if (cursor == null) {
            pages.firstOrNull()
                ?: StoreMemoPage(emptyList(), null, 0L, "fp")
        } else {
            pages.getOrNull(1) ?: StoreMemoPage(emptyList(), null, 0L, "fp")
        }
    }

    override fun getMemo(memoId: String): StoreMemoSnapshot? = null

    override fun queryCount(query: StoreMemoQuery): Long = 0L

    override fun memoStatisticsRows(): List<StoreMemoStatisticsRow> = emptyList()

    override fun sidebarProjection(): StoreSidebarProjection =
        StoreSidebarProjection(1u, 0, emptyList(), emptyList())

    override fun listHistoryAttachmentRefs(): List<StoreHistoryAttachmentRef> = emptyList()

    override fun listMemoHistory(
        memoId: String,
        cursor: String?,
        limit: Int,
    ): StoreMemoHistoryPage = StoreMemoHistoryPage(emptyList(), null)

    override fun queryReminderPlan(query: StoreReminderQuery): StoreReminderPlan =
        StoreReminderPlan(emptyList(), query.workspaceGeneration.toString())

    override fun applyMemoCommand(
        command: StoreMemoCommand,
        onPublication: (StoreMemoCommit) -> Unit,
    ): StoreMemoCommit = error("not used")

    override fun permanentDeleteMany(
        operationId: String,
        targets: List<StoreMemoDeleteTarget>,
    ): StoreMemoBatchCommit = error("not used")

    override fun commitDocumentMutation(
        mutation: com.lomo.domain.model.MemoDocumentMutation,
    ): StoreMemoCommit = error("not used")

    override fun startRebuild(batchSize: Int): StoreRebuildResult =
        StoreRebuildResult(
            memosIndexed = 0,
            fileCount = 0,
            attachmentCount = 0,
            workspaceDigest = "empty",
            storeDigest = "empty",
            corruptLomoIsolated = 0,
            highWaterRevision = 0,
        )
}

class StorePagingSourceTest : FunSpec({
    test("first page returns items and next cursor key") {
        val port =
            FakeStorePort().apply {
                pages +=
                    StoreMemoPage(
                        items =
                            listOf(
                                StoreMemoSummary(
                                    memoId = "m1",
                                    sourcePath = "memos/2026_01_01.md",
                                    fileFingerprint = "fp1",
                                    updatedAtMs = 2L,
                                    createdAtMs = 1L,
                                    hasTodo = false,
                                    hasUrl = false,
                                    hasAttachment = true,
                                    isPinned = false,
                                    isTrashed = false,
                                    bodyPreview = "hello",
                                    contentRevision = 1L,
                                    tags = listOf("ship"),
                                    imageUrls = listOf("images/cover.png"),
                                ),
                            ),
                        nextCursor = StorePageCursor("cursor-2"),
                        highWaterRevision = 9L,
                        queryFingerprint = "q",
                        itemsBefore = 0,
                        itemsAfter = 4,
                    )
            }
        val source = StorePagingSource(port, StoreMemoQuery())
        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = false))
        val page =
            result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, com.lomo.domain.model.Memo>>()
        page.data.size shouldBe 1
        page.data[0].id shouldBe "m1"
        page.data[0].tags shouldBe listOf("ship")
        page.data[0].imageUrls shouldBe listOf("images/cover.png")
        page.nextKey shouldBe "cursor-2"
        page.prevKey.shouldBeNull()
        page.itemsBefore shouldBe 0
        page.itemsAfter shouldBe 4
    }

    test("empty page ends paging") {
        val port = FakeStorePort()
        val source = StorePagingSource(port, StoreMemoQuery())
        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = false))
        val page =
            result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, com.lomo.domain.model.Memo>>()
        page.data.isEmpty() shouldBe true
        page.nextKey.shouldBeNull()
    }

    test("store failure surfaces as LoadResult.Error") {
        val port = FakeStorePort().apply { throwOnLoad = true }
        val source = StorePagingSource(port, StoreMemoQuery())
        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = false))
        result.shouldBeInstanceOf<PagingSource.LoadResult.Error<String, com.lomo.domain.model.Memo>>()
    }

    test("caller cancellation propagates instead of becoming a paging error") {
        val port = FakeStorePort().apply { cancelOnLoad = true }
        val source = StorePagingSource(port, StoreMemoQuery())

        io.kotest.assertions.throwables.shouldThrow<CancellationException> {
            source.load(
                PagingSource.LoadParams.Refresh(
                    key = null,
                    loadSize = 30,
                    placeholdersEnabled = false,
                ),
            )
        }
    }

    test("invalidation bus invalidates a registered paging source") {
        val bus = StoreInvalidationBus()
        val source =
            StorePagingSource(
                FakeStorePort(),
                StoreMemoQuery(),
                registerInvalidation = { pagingSource ->
                    bus.register(pagingSource, setOf(StoreInvalidationScope.MemoList))
                },
            )

        source.invalid shouldBe false
        bus.publish(
            StoreMemoCommit(
                operationId = "op-1",
                memoId = "m1",
                coreRevision = 1L,
                eventSequence = 1L,
                contentRevision = 1L,
                fileFingerprint = "fp",
                scopes = listOf(StoreInvalidationScope.MemoList),
                idempotentReplay = false,
            ),
        )
        source.invalid shouldBe true
    }

    test("refresh at the true head uses a null key so the next generation loads from head") {
        val port =
            FakeStorePort().apply {
                pages += samplePage(ids = listOf("m1", "m2"), itemsBefore = 0, itemsAfter = 3)
            }
        val source = StorePagingSource(port, StoreMemoQuery())
        val loaded =
            source
                .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = true))
                .shouldBeInstanceOf<PagingSource.LoadResult.Page<String, com.lomo.domain.model.Memo>>()
        val state =
            PagingState(
                pages = listOf(loaded),
                anchorPosition = 0,
                config = PagingConfig(pageSize = 30, enablePlaceholders = true),
                leadingPlaceholderCount = 0,
            )
        source.getRefreshKey(state).shouldBeNull()
    }

    test("refresh away from head uses the closest memo identity") {
        val port =
            FakeStorePort().apply {
                pages += samplePage(ids = listOf("m1", "m2"), itemsBefore = 0, itemsAfter = 3)
            }
        val source = StorePagingSource(port, StoreMemoQuery())
        val loaded =
            source
                .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = true))
                .shouldBeInstanceOf<PagingSource.LoadResult.Page<String, com.lomo.domain.model.Memo>>()
        val state =
            PagingState(
                pages = listOf(loaded),
                anchorPosition = 1,
                config = PagingConfig(pageSize = 30, enablePlaceholders = true),
                leadingPlaceholderCount = 0,
            )
        source.getRefreshKey(state) shouldBe "m2"
    }

    test("append load queries the store forward from the page cursor") {
        val port =
            FakeStorePort().apply {
                pages += samplePage(ids = listOf("m1"), itemsBefore = 0, itemsAfter = 4)
                pages += samplePage(ids = listOf("m2"), itemsBefore = 1, itemsAfter = 3)
            }
        val source = StorePagingSource(port, StoreMemoQuery())
        val result =
            source.load(
                PagingSource.LoadParams.Append(key = "cursor-next", loadSize = 30, placeholdersEnabled = true),
            )
        result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, com.lomo.domain.model.Memo>>()
        port.lastCursor?.encoded shouldBe "cursor-next"
        port.lastStartMemoId.shouldBeNull()
        port.lastBackward shouldBe false
    }

    test("refresh load starts at the requested memo identity") {
        val port =
            FakeStorePort().apply {
                pages += samplePage(ids = listOf("m3"), itemsBefore = 2, itemsAfter = 2)
            }
        val source = StorePagingSource(port, StoreMemoQuery())
        val result =
            source.load(
                PagingSource.LoadParams.Refresh(key = "m3", loadSize = 30, placeholdersEnabled = true),
            )
        val page =
            result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, com.lomo.domain.model.Memo>>()
        port.lastStartMemoId shouldBe "m3"
        port.lastCursor.shouldBeNull()
        port.lastBackward shouldBe false
        page.itemsBefore shouldBe 2
        page.itemsAfter shouldBe 2
        page.data[0].id shouldBe "m3"
    }

    test("prepend load queries the store backward from the page cursor") {
        val port =
            FakeStorePort().apply {
                pages += samplePage(ids = listOf("m1"), itemsBefore = 0, itemsAfter = 4)
                pages +=
                    samplePage(
                        ids = listOf("m0"),
                        itemsBefore = 0,
                        itemsAfter = 4,
                        prevCursor = null,
                    )
            }
        val source = StorePagingSource(port, StoreMemoQuery())
        val result =
            source.load(
                PagingSource.LoadParams.Prepend(key = "cursor-prev", loadSize = 30, placeholdersEnabled = true),
            )
        result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, com.lomo.domain.model.Memo>>()
        port.lastCursor?.encoded shouldBe "cursor-prev"
        port.lastStartMemoId.shouldBeNull()
        port.lastBackward shouldBe true
    }
})

private fun samplePage(
    ids: List<String>,
    itemsBefore: Long,
    itemsAfter: Long,
    prevCursor: StorePageCursor? = if (itemsBefore > 0) StorePageCursor("prev") else null,
    nextCursor: StorePageCursor? = if (itemsAfter > 0) StorePageCursor("next") else null,
): StoreMemoPage =
    StoreMemoPage(
        items =
            ids.map { id ->
                StoreMemoSummary(
                    memoId = id,
                    sourcePath = "memos/${id}.md",
                    fileFingerprint = "fp-$id",
                    updatedAtMs = 2L,
                    createdAtMs = 1L,
                    hasTodo = false,
                    hasUrl = false,
                    hasAttachment = false,
                    isPinned = false,
                    isTrashed = false,
                    bodyPreview = id,
                    contentRevision = 1L,
                )
            },
        nextCursor = nextCursor,
        highWaterRevision = 9L,
        queryFingerprint = "q",
        prevCursor = prevCursor,
        itemsBefore = itemsBefore,
        itemsAfter = itemsAfter,
    )
