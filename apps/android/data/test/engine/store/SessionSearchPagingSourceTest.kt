package com.lomo.data.engine.store

/*
 * Behavior Contract:
 * - Unit under test: SessionSearchPagingSource + SessionNativeBridge (fake).
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: session fuzzy search paging with refresh/append cursor typing at the adapter entry.
 *
 * Scenarios:
 * - Given a refresh load, when load runs, then the session request carries no cursor.
 * - Given a refresh load carrying an identity key, when load runs, then the identity is not
 *   reinterpreted as a page cursor.
 * - Given an append load with a cursor key, when load runs, then the session request carries it.
 * - Given a prepend load, when load runs, then the result is an explicit error because search
 *   paging moves forward only.
 * - Given a discarded epoch, when load runs, then LoadResult.Invalid is returned.
 *
 * Observable outcomes:
 * - SessionSearchRequest cursor values per load type, LoadResult Page/Error/Invalid.
 *
 * TDD proof:
 * - Fails while the adapter collapses every load type into `params.key?.let` so a refresh
 *   identity key is reinterpreted as a page cursor and prepend is accepted as forward paging.
 *
 * Excludes:
 * - Real BoltFFI session lifecycle and Paging3 viewport behavior.
 */

import androidx.paging.PagingSource
import com.lomo.data.engine.SessionNativeBridge
import com.lomo.nativebridge.SessionSearchMode
import com.lomo.nativebridge.SessionSearchOutcome
import com.lomo.nativebridge.SessionSearchPage
import com.lomo.nativebridge.SessionSearchRequest
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

    private fun ready(): SessionSearchOutcome =
        SessionSearchOutcome.Ready(
            SessionSearchPage(
                queryEpoch = 1uL,
                mode = SessionSearchMode.FUZZY,
                items = emptyList(),
                nextCursor = null,
            ),
        )
}

class SessionSearchPagingSourceTest : FunSpec({
    fun source(session: FakeSessionNativeBridge): SessionSearchPagingSource =
        SessionSearchPagingSource(
            session = session,
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
        result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, com.lomo.domain.model.Memo>>()
        session.requests.single().cursor.shouldBeNull()
    }

    test("append load passes its cursor key through") {
        val session = FakeSessionNativeBridge()
        val result =
            source(session).load(
                PagingSource.LoadParams.Append(
                    key = "cursor-9",
                    loadSize = 30,
                    placeholdersEnabled = false,
                ),
            )
        result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, com.lomo.domain.model.Memo>>()
        session.requests.single().cursor?.encoded shouldBe "cursor-9"
    }

    test("refresh identity key is not reinterpreted as a page cursor") {
        val session = FakeSessionNativeBridge()
        val result =
            source(session).load(
                PagingSource.LoadParams.Refresh(
                    key = "memo-id-1",
                    loadSize = 30,
                    placeholdersEnabled = false,
                ),
            )
        result.shouldBeInstanceOf<PagingSource.LoadResult.Page<String, com.lomo.domain.model.Memo>>()
        session.requests.single().cursor.shouldBeNull()
    }

    test("prepend is rejected because search paging moves forward only") {
        val session = FakeSessionNativeBridge()
        val result =
            source(session).load(
                PagingSource.LoadParams.Prepend(
                    key = "cursor-1",
                    loadSize = 30,
                    placeholdersEnabled = false,
                ),
            )
        result.shouldBeInstanceOf<PagingSource.LoadResult.Error<String, com.lomo.domain.model.Memo>>()
        session.requests.isEmpty() shouldBe true
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
        result.shouldBeInstanceOf<PagingSource.LoadResult.Invalid<String, com.lomo.domain.model.Memo>>()
    }
})
