package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.DailyReviewSession
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.LocalDate

/*
 * Behavior Contract:
 * - Unit under test: DailyReviewSessionRepositoryImpl
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: read and persist the daily review session atomically from datastore flows.
 *
 * Scenarios:
 * - Given an invalid stored date, when getSession runs, then the result is null.
 * - Given a valid date and missing page index, when getSession runs, then pageIndex is 0.
 * - Given a session value, when saveSession runs, then date, seed, and pageIndex are written together.
 *
 * Observable outcomes:
 * - null vs DailyReviewSession fields, recorded datastore update payload.
 *
 * TDD proof:
 * - Fails before the fix when session fields are read via multiple sequential first() calls and can
 *   observe torn state.
 *
 * Excludes:
 * - DataStore file I/O internals and date parsing outside the repository boundary.
 */
class DailyReviewSessionRepositoryImplTest : DataFunSpec() {
    init {
        test("getSession returns null when date is invalid") {
            runTest {
                val store = DailyReviewStoreFixture()
                store.date = "invalid-date"
                store.seed = 11L
                store.pageIndex = 3
                val repository = DailyReviewSessionRepositoryImpl(store.dataStore)

                repository.getSession().shouldBeNull()
            }
        }

        test("getSession defaults page index to zero when page index is missing") {
            runTest {
                val store = DailyReviewStoreFixture()
                store.date = "2026-04-27"
                store.seed = 42L
                store.pageIndex = null
                val repository = DailyReviewSessionRepositoryImpl(store.dataStore)

                repository.getSession() shouldBe
                    DailyReviewSession(
                        date = LocalDate.of(2026, 4, 27),
                        seed = 42L,
                        pageIndex = 0,
                    )
            }
        }

        test("saveSession writes date seed and page index to datastore") {
            runTest {
                val store = DailyReviewStoreFixture()
                val repository = DailyReviewSessionRepositoryImpl(store.dataStore)
                val session =
                    DailyReviewSession(
                        date = LocalDate.of(2026, 4, 27),
                        seed = 99L,
                        pageIndex = 5,
                    )

                repository.saveSession(session)

                store.lastUpdate shouldBe
                    DailyReviewUpdate(
                        date = "2026-04-27",
                        seed = 99L,
                        pageIndex = 5,
                    )
            }
        }
    }
}

private data class DailyReviewUpdate(
    val date: String?,
    val seed: Long?,
    val pageIndex: Int?,
)

private class DailyReviewStoreFixture {
    var date: String? = null
    var seed: Long? = null
    var pageIndex: Int? = null
    var lastUpdate: DailyReviewUpdate? = null

    val dataStore: LomoDataStore = mockk()

    init {
        every { dataStore.dailyReviewSessionDate } answers { flowOf(date) }
        every { dataStore.dailyReviewSessionSeed } answers { flowOf(seed) }
        every { dataStore.dailyReviewSessionPageIndex } answers { flowOf(pageIndex) }
        coEvery { dataStore.updateDailyReviewSession(any(), any(), any()) } answers {
            lastUpdate =
                DailyReviewUpdate(
                    date = arg(0),
                    seed = arg(1),
                    pageIndex = arg(2),
                )
        }
    }
}
