package com.lomo.data.engine.store

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.domain.model.EngineCommandFailure
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.GalleryMemo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException

/*
 * Behavior Contract:
 * - Unit under test: GalleryPagingSource + StorePort (fake).
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: bounded lightweight gallery paging with bidirectional keyset cursors,
 *   centered anchor refresh keys, COUNT_UNDEFINED placeholders, and fail-closed stale cursor
 *   invalidation without full-body hydration or COUNT(*).
 */
private class FakeGalleryStorePort : StorePort {
    var onQueryGalleryPage: (
        cursor: StorePageCursor?,
        pageSize: Int,
        startMemoId: String?,
        backward: Boolean,
    ) -> StoreGalleryPage = { _, _, _, _ ->
        StoreGalleryPage(emptyList(), null, null, 0L, "gallery:v1")
    }

    override fun queryGalleryPage(
        cursor: StorePageCursor?,
        pageSize: Int,
        startMemoId: String?,
        backward: Boolean,
    ): StoreGalleryPage = onQueryGalleryPage(cursor, pageSize, startMemoId, backward)

    override fun queryMemos(
        query: StoreMemoQuery,
        cursor: StorePageCursor?,
        pageSize: Int,
        startMemoId: String?,
        backward: Boolean,
    ): StoreMemoPage = error("gallery paging must not query full memo summaries")

    override fun getMemo(memoId: String): StoreMemoSnapshot? = null

    override fun queryCount(query: StoreMemoQuery): Long = error("gallery paging must not call queryCount")

    override fun sidebarProjection(): StoreSidebarProjection = StoreSidebarProjection(1u, 0, emptyList(), emptyList())

    override fun queryReminderPlan(nowUtcMs: Long): StoreReminderPlan = StoreReminderPlan(emptyList(), 0, "gen-test")

    override fun applyMemoCommand(
        command: StoreMemoCommand,
        onPublication: (StoreMemoCommandPublication) -> Unit,
    ): StoreMemoCommit = error("not used")

    override fun permanentDeleteMany(operationId: String, targets: List<StoreMemoDeleteTarget>): StoreMemoBatchCommit =
        error("not used")

    override fun emptyTrash(operationId: String, isCancelled: () -> Boolean): StoreEmptyTrashResult = error("not used")

    override fun snoozeReminder(opaqueId: String, interval: com.lomo.domain.model.ReminderInterval) = error("not used")

    override fun recoverReminderSnooze() = error("not used")

    override fun startRebuild(batchSize: Int): StoreRebuildResult = error("not used")
}

private fun galleryItem(id: String, pinned: Boolean = false): StoreGalleryItem = StoreGalleryItem(
    memoId = id,
    sourcePath = "memos/2026_10_01.md",
    timePart = "09:30:00",
    createdAtMs = 1_700_000_000_000L,
    updatedAtMs = 1_700_000_001_000L,
    isPinned = pinned,
    contentRevision = 4L,
    fileFingerprint = "fp-$id",
    imageUrls = listOf("images/$id.png", "https://cdn.example.com/$id.jpg"),
)

class GalleryPagingSourceTest :
    FunSpec({
        test("refresh append prepend and anchor map lightweight gallery items with COUNT_UNDEFINED") {
            val recordedCalls = mutableListOf<List<Any?>>()
            val port =
                FakeGalleryStorePort().apply {
                    onQueryGalleryPage = { cursor, pageSize, startMemoId, backward ->
                        recordedCalls += listOf(cursor?.encoded, pageSize, startMemoId, backward)
                        when {
                            startMemoId != null ->
                                StoreGalleryPage(
                                    items = listOf(galleryItem(startMemoId)),
                                    nextCursor = StorePageCursor("next-$startMemoId"),
                                    prevCursor = StorePageCursor("prev-$startMemoId"),
                                    highWaterRevision = 10L,
                                    queryFingerprint = "gallery:v1",
                                )

                            backward ->
                                StoreGalleryPage(
                                    items = listOf(galleryItem("g0", pinned = true)),
                                    nextCursor = StorePageCursor("next-0"),
                                    prevCursor = null,
                                    highWaterRevision = 10L,
                                    queryFingerprint = "gallery:v1",
                                )

                            cursor == null ->
                                StoreGalleryPage(
                                    items = listOf(galleryItem("g1")),
                                    nextCursor = StorePageCursor("cur-1"),
                                    prevCursor = null,
                                    highWaterRevision = 10L,
                                    queryFingerprint = "gallery:v1",
                                )

                            else ->
                                StoreGalleryPage(
                                    items = listOf(galleryItem("g2")),
                                    nextCursor = null,
                                    prevCursor = StorePageCursor("cur-back"),
                                    highWaterRevision = 10L,
                                    queryFingerprint = "gallery:v1",
                                )
                        }
                    }
                }
            val source = GalleryPagingSource(port = port, pageSize = 20)

            val head =
                source.load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 20,
                        placeholdersEnabled = false,
                    ),
                ).shouldBeInstanceOf<PagingSource.LoadResult.Page<String, GalleryMemo>>()
            head.prevKey.shouldBeNull()
            head.nextKey shouldBe "cur-1"
            head.itemsBefore shouldBe PagingSource.LoadResult.Page.COUNT_UNDEFINED
            head.itemsAfter shouldBe PagingSource.LoadResult.Page.COUNT_UNDEFINED
            head.data.single().dateKey shouldBe "2026_10_01"
            head.data.single().displayTimestamp() shouldBe "2026_10_01 09:30:00"
            head.data.single().imageUrls shouldBe listOf("images/g1.png", "https://cdn.example.com/g1.jpg")

            val appended =
                source.load(
                    PagingSource.LoadParams.Append(
                        key = "cur-1",
                        loadSize = 20,
                        placeholdersEnabled = false,
                    ),
                ).shouldBeInstanceOf<PagingSource.LoadResult.Page<String, GalleryMemo>>()
            appended.prevKey shouldBe "cur-back"
            appended.nextKey.shouldBeNull()
            appended.data.single().id shouldBe "g2"

            val prepended =
                source.load(
                    PagingSource.LoadParams.Prepend(
                        key = "cur-back",
                        loadSize = 20,
                        placeholdersEnabled = false,
                    ),
                ).shouldBeInstanceOf<PagingSource.LoadResult.Page<String, GalleryMemo>>()
            prepended.prevKey.shouldBeNull()
            prepended.nextKey shouldBe "next-0"
            prepended.data.single().isPinned shouldBe true

            val anchored =
                source.load(
                    PagingSource.LoadParams.Refresh(
                        key = "g9",
                        loadSize = 20,
                        placeholdersEnabled = false,
                    ),
                ).shouldBeInstanceOf<PagingSource.LoadResult.Page<String, GalleryMemo>>()
            anchored.data.single().id shouldBe "g9"

            recordedCalls shouldBe
                listOf(
                    listOf(null, 20, null, false),
                    listOf("cur-1", 20, null, false),
                    listOf("cur-back", 20, null, true),
                    listOf(null, 20, "g9", false),
                )
        }

        test(
            "stale_cursor maps to LoadResult.Invalid while non-stale failure maps to Error and cancellation rethrows",
        ) {
            var mode = "stale"
            val port =
                FakeGalleryStorePort().apply {
                    onQueryGalleryPage = { _, _, _, _ ->
                        when (mode) {
                            "stale" ->
                                throw EngineCommandFailureException(
                                    EngineCommandFailure(
                                        category = EngineFailureCategory.CONFLICT,
                                        code = "stale_cursor",
                                        retryDisposition = EngineRetryDisposition.TRANSIENT,
                                        operationId = null,
                                        jobId = null,
                                        diagnostic = "revision advanced",
                                    ),
                                )

                            "error" -> error("io failed")

                            else -> throw CancellationException("cancelled")
                        }
                    }
                }
            val source = GalleryPagingSource(port = port, pageSize = 20)

            source.load(PagingSource.LoadParams.Append("c1", 20, false))
                .shouldBeInstanceOf<PagingSource.LoadResult.Invalid<String, GalleryMemo>>()

            mode = "error"
            source.load(PagingSource.LoadParams.Refresh(null, 20, false))
                .shouldBeInstanceOf<PagingSource.LoadResult.Error<String, GalleryMemo>>()

            mode = "cancel"
            io.kotest.assertions.throwables.shouldThrow<CancellationException> {
                source.load(PagingSource.LoadParams.Refresh(null, 20, false))
            }
        }

        test("invalidation bus invalidates gallery paging source and refresh key centers deep viewport") {
            val bus = StoreInvalidationBus()
            val port = FakeGalleryStorePort()
            val source = GalleryPagingSource(port = port, pageSize = 20, registerInvalidation = bus::register)

            val deepItems = (0 until 60).map { idx ->
                GalleryMemo(
                    id = "g-$idx",
                    timestamp = idx.toLong(),
                    dateKey = "2026_10_01",
                    imageUrls = listOf("images/$idx.png"),
                )
            }
            val config = PagingConfig(pageSize = 20, initialLoadSize = 20, enablePlaceholders = false)
            source.getRefreshKey(
                PagingState(
                    pages = listOf(PagingSource.LoadResult.Page(deepItems, prevKey = null, nextKey = null)),
                    anchorPosition = 5,
                    config = config,
                    leadingPlaceholderCount = 0,
                ),
            ).shouldBeNull()
            source.getRefreshKey(
                PagingState(
                    pages = listOf(PagingSource.LoadResult.Page(deepItems, prevKey = null, nextKey = null)),
                    anchorPosition = 35,
                    config = config,
                    leadingPlaceholderCount = 0,
                ),
            ) shouldBe "g-25"

            var invalidated = false
            source.registerInvalidatedCallback { invalidated = true }
            bus.publish(
                StoreMemoCommit(
                    operationId = "op-pin",
                    memoId = "g-1",
                    coreRevision = 2L,
                    eventSequence = 1L,
                    contentRevision = 2L,
                    fileFingerprint = "ff",
                    scopes = listOf(StoreInvalidationScope.MemoList),
                    idempotentReplay = false,
                ),
            )
            invalidated shouldBe true
        }
    })
