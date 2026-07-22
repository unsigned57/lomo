package com.lomo.data.engine.store

/*
 * Behavior Contract:
 * - Unit under test: StorePagingSource + StorePort (fake).
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: bounded memo page loads through the store port with cursor keys and load errors.
 *
 * Scenarios:
 * - Given a first page with a next cursor, when load runs, then items and next key are returned.
 * - Given a subsequent cursor, when load runs, then the following page is returned.
 * - Given the store throws, when load runs, then LoadResult.Error is returned.
 *
 * Observable outcomes:
 * - PagingSource LoadResult page items, next/prev keys, and Error.
 *
 * TDD proof:
 * - Fails before StorePagingSource maps StorePort pages and failures into Paging LoadResult.
 *
 * Excludes:
 * - Real BoltFFI handle lifecycle and device UI scrolling.
 *
 * Test Change Justification:
 * - Reason category: StorePort surface grew history attachment and media-adjacent methods.
 * - Old behavior/assertion being replaced: fake StorePort without listHistoryAttachmentRefs stub.
 * - Why old assertion is no longer correct: production StorePort requires history attachment refs
 *   after stage-4 media refcount wiring; fakes must compile against the expanded port.
 * - Coverage preserved by: first/next page keys and LoadResult.Error still asserted.
 * - Why this is not fitting the test to the implementation: still locks observable paging results,
 *   not media orphan sweep internals.
 */

import androidx.paging.PagingSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private class FakeStorePort : StorePort {
    var pages: MutableList<StoreMemoPage> = mutableListOf()
    var throwOnLoad: Boolean = false

    override fun queryMemos(
        query: StoreMemoQuery,
        cursor: StorePageCursor?,
        pageSize: Int,
    ): StoreMemoPage {
        if (throwOnLoad) error("store unavailable")
        return if (cursor == null) {
            pages.firstOrNull()
                ?: StoreMemoPage(emptyList(), null, 0L, "fp")
        } else {
            pages.getOrNull(1) ?: StoreMemoPage(emptyList(), null, 0L, "fp")
        }
    }

    override fun getMemo(memoId: String): StoreMemoSnapshot? = null

    override fun listHistoryAttachmentRefs(): List<StoreHistoryAttachmentRef> = emptyList()

    override fun applyMemoCommand(command: StoreMemoCommand): StoreMemoCommit =
        error("not used")

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
})
