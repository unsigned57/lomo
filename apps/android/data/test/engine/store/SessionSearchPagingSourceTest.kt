package com.lomo.data.engine.store

/*
 * Behavior Contract:
 * - Unit under test: SessionSearchPagingSource + SessionNativeBridge (fake).
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: session dual-mode search paging with identity refresh keys and exclusive
 *   forward/backward cursors at the adapter entry.
 *
 * Scenarios:
 * - Given a refresh load, when load runs, then the session request carries no cursor.
 * - Given a refresh load carrying an identity key, when load runs, then the identity is passed
 *   as anchor and not reinterpreted as a page cursor.
 * - Given an append load with a cursor key, when load runs, then the session request carries it
 *   forward with itemsBefore undefined.
 * - Given a prepend load with a cursor key, when load runs, then the session request carries it
 *   backward with itemsAfter undefined.
 * - Given a prepend load with a blank key, when load runs, then LoadResult.Error is returned.
 * - Given a deep loaded window, when getRefreshKey runs, then it centers on a visible memo id.
 * - Given a discarded epoch, when load runs, then LoadResult.Invalid is returned.
 *
 * Observable outcomes:
 * - SessionSearchRequest cursor/anchor/backward values per load type, LoadResult Page/Error/Invalid,
 *   and refresh keys.
 *
 * Excludes:
 * - Real BoltFFI session lifecycle and Paging3 viewport behavior.
 */

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.engine.SessionNativeBridge
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoSearchMode
import com.lomo.nativebridge.SessionSearchHit
import com.lomo.nativebridge.SessionSearchMode
import com.lomo.nativebridge.SessionSearchOutcome
import com.lomo.nativebridge.SessionSearchPage
import com.lomo.nativebridge.SessionSearchRequest
import com.lomo.nativebridge.StoreMemoSummary
import com.lomo.nativebridge.StorePageCursor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private class FakeSessionNativeBridge : SessionNativeBridge {
    var outcome: SessionSearchOutcome = ready()
    val requests = mutableListOf<SessionSearchRequest>()

    override fun sessionSearch(request: SessionSearchRequest): SessionSearchOutcome {
        requests += request
        return outcome
    }

    companion object {
        fun ready(
            ids: List<String> = emptyList(),
            itemsBefore: ULong = 0uL,
            itemsAfter: ULong = 0uL,
            prevCursor: String? = null,
            nextCursor: String? = null,
        ): SessionSearchOutcome = SessionSearchOutcome.Ready(
            SessionSearchPage(
                queryEpoch = 1uL,
                mode = SessionSearchMode.FUZZY,
                items =
                ids.map { id ->
                    SessionSearchHit(
                        score = 100L,
                        summary =
                        StoreMemoSummary(
                            memoId = id,
                            sourcePath = "memos/$id.md",
                            timePart = "",
                            fileFingerprint = "fp-$id",
                            updatedAtMs = 2L,
                            createdAtMs = 1L,
                            hasTodo = false,
                            hasUrl = false,
                            hasAttachment = false,
                            isPinned = false,
                            isTrashed = false,
                            bodyPreview = id,
                            contentRevision = 1uL,
                            rank = 1.0,
                            tags = emptyList(),
                            imageUrls = emptyList(),
                            reminders = emptyList(),
                            isPending = false,
                            charCount = id.length.toLong(),
                        ),
                    )
                },
                nextCursor = nextCursor?.let(::StorePageCursor),
                prevCursor = prevCursor?.let(::StorePageCursor),
                itemsBefore = itemsBefore,
                itemsAfter = itemsAfter,
                total = itemsBefore + ids.size.toULong() + itemsAfter,
            ),
        )
    }
}

class SessionSearchPagingSourceTest :
    FunSpec({
        fun source(session: FakeSessionNativeBridge): SessionSearchPagingSource = SessionSearchPagingSource(
            session = session,
            mode = MemoSearchMode.Fuzzy,
            filters = StoreMemoFilters(),
            queryEpoch = 1uL,
            text = "needle",
        )

        test("refresh load carries no cursor") {
            val session = FakeSessionNativeBridge()
            val result =
                source(session).load(
                    PagingSource.LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = false),
                )
            result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, Memo>>()
            val req = session.requests.single()
            req.cursor.shouldBeNull()
            req.anchor.shouldBeNull()
            req.backward shouldBe false
        }

        test("append load passes its cursor key forward") {
            val session = FakeSessionNativeBridge()
            val result =
                source(session).load(
                    PagingSource.LoadParams.Append(
                        key = "cursor-9",
                        loadSize = 30,
                        placeholdersEnabled = false,
                    ),
                )
            val page = result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, Memo>>()
            val req = session.requests.single()
            req.cursor?.encoded shouldBe "cursor-9"
            req.anchor.shouldBeNull()
            req.backward shouldBe false
            page.itemsBefore shouldBe PagingSource.LoadResult.Page.COUNT_UNDEFINED
        }

        test("refresh identity key is passed as anchor and not reinterpreted as a page cursor") {
            val session = FakeSessionNativeBridge()
            val result =
                source(session).load(
                    PagingSource.LoadParams.Refresh(
                        key = "memo-id-1",
                        loadSize = 30,
                        placeholdersEnabled = false,
                    ),
                )
            result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, Memo>>()
            val req = session.requests.single()
            req.cursor.shouldBeNull()
            req.anchor shouldBe "memo-id-1"
            req.backward shouldBe false
        }

        test("prepend load passes its cursor key backward") {
            val session = FakeSessionNativeBridge().apply {
                outcome = FakeSessionNativeBridge.ready(
                    ids = listOf("m0"),
                    itemsBefore = 0uL,
                    itemsAfter = 4uL,
                    nextCursor = "cursor-1",
                )
            }
            val result =
                source(session).load(
                    PagingSource.LoadParams.Prepend(
                        key = "cursor-1",
                        loadSize = 30,
                        placeholdersEnabled = true,
                    ),
                )
            val page = result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, Memo>>()
            val req = session.requests.single()
            req.cursor?.encoded shouldBe "cursor-1"
            req.anchor.shouldBeNull()
            req.backward shouldBe true
            page.itemsAfter shouldBe PagingSource.LoadResult.Page.COUNT_UNDEFINED
            page.data.single().id shouldBe "m0"
        }

        test("prepend with blank cursor fails closed") {
            val session = FakeSessionNativeBridge()
            val result =
                source(session).load(
                    PagingSource.LoadParams.Prepend(
                        key = "",
                        loadSize = 30,
                        placeholdersEnabled = false,
                    ),
                )
            result.shouldBeInstanceOf<PagingSource.LoadResult.Error<String, Memo>>()
            session.requests.isEmpty() shouldBe true
        }

        test("getRefreshKey returns null when anchorPosition is null or near head") {
            val session = FakeSessionNativeBridge().apply {
                outcome = FakeSessionNativeBridge.ready(
                    ids = (0 until 30).map { "m$it" },
                    itemsBefore = 0uL,
                    itemsAfter = 70uL,
                    nextCursor = "next",
                )
            }
            val pagingSource = source(session)
            val loaded = pagingSource
                .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = true))
                .shouldBeInstanceOf<PagingSource.LoadResult.Page<String, Memo>>()

            val noAnchor = PagingState(
                pages = listOf(loaded),
                anchorPosition = null,
                config = PagingConfig(pageSize = 30, initialLoadSize = 30, enablePlaceholders = true),
                leadingPlaceholderCount = 0,
            )
            pagingSource.getRefreshKey(noAnchor).shouldBeNull()

            val nearHead = PagingState(
                pages = listOf(loaded),
                anchorPosition = 6,
                config = PagingConfig(pageSize = 30, initialLoadSize = 30, enablePlaceholders = true),
                leadingPlaceholderCount = 0,
            )
            pagingSource.getRefreshKey(nearHead).shouldBeNull()
        }

        test("getRefreshKey centers on visible memo identity in a deep loaded window") {
            val session = FakeSessionNativeBridge().apply {
                outcome = FakeSessionNativeBridge.ready(
                    ids = (300 until 330).map { "m$it" },
                    itemsBefore = 300uL,
                    itemsAfter = 670uL,
                    prevCursor = "prev",
                    nextCursor = "next",
                )
            }
            val pagingSource = source(session)
            val loaded = pagingSource
                .load(PagingSource.LoadParams.Refresh(key = "m300", loadSize = 30, placeholdersEnabled = true))
                .shouldBeInstanceOf<PagingSource.LoadResult.Page<String, Memo>>()
            val state = PagingState(
                pages = listOf(loaded),
                anchorPosition = 320,
                config = PagingConfig(pageSize = 30, initialLoadSize = 30, enablePlaceholders = true),
                leadingPlaceholderCount = 300,
            )

            pagingSource.getRefreshKey(state) shouldBe "m305"
        }

        test("discarded epoch invalidates the paging source") {
            val session =
                FakeSessionNativeBridge().apply {
                    outcome = SessionSearchOutcome.Discarded(queryEpoch = 1uL, activeEpoch = 2uL)
                }
            val result =
                source(session).load(
                    PagingSource.LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = false),
                )
            result.shouldBeInstanceOf<PagingSource.LoadResult.Invalid<String, Memo>>()
        }
    })
