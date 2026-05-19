package com.lomo.data.repository

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.DailyReviewSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Behavior Contract:
 * - Unit under test: DailyReviewSessionRepositoryImpl
 * - Behavior focus: atomic session reads from datastore flows and write-through persistence of date/seed/pageIndex.
 * - Observable outcomes: returned DailyReviewSession values (including pageIndex defaulting) and datastore update arguments.
 * - TDD proof: Fails before the fix when session fields are read via multiple sequential `first()` calls and can observe torn state.
 * - Excludes: DataStore file I/O internals and date parsing implementation details outside repository boundaries.
 */
class DailyReviewSessionRepositoryImplTest : DataFunSpec() {
    init {
        test("getSession returns null when date is invalid") { `getSession returns null when date is invalid`() }

        test("getSession defaults page index to zero when page index is missing") { `getSession defaults page index to zero when page index is missing`() }

        test("saveSession writes date seed and page index to datastore") { `saveSession writes date seed and page index to datastore`() }
    }


    private val dataStore: LomoDataStore = mockk(relaxed = true)
    private val repository = DailyReviewSessionRepositoryImpl(dataStore)

    private fun `getSession returns null when date is invalid`() =
        runTest {
            every { dataStore.dailyReviewSessionDate } returns flowOf("invalid-date")
            every { dataStore.dailyReviewSessionSeed } returns flowOf(11L)
            every { dataStore.dailyReviewSessionPageIndex } returns flowOf(3)

            val session = repository.getSession()

            session.shouldBeNull()
        }

    private fun `getSession defaults page index to zero when page index is missing`() =
        runTest {
            every { dataStore.dailyReviewSessionDate } returns flowOf("2026-04-27")
            every { dataStore.dailyReviewSessionSeed } returns flowOf(42L)
            every { dataStore.dailyReviewSessionPageIndex } returns flowOf(null)

            val session = repository.getSession()

            session shouldBe DailyReviewSession(
                    date = LocalDate.of(2026, 4, 27),
                    seed = 42L,
                    pageIndex = 0,
                )
        }

    private fun `saveSession writes date seed and page index to datastore`() =
        runTest {
            coEvery {
                dataStore.updateDailyReviewSession(any(), any(), any())
            } just runs
            val session = DailyReviewSession(date = LocalDate.of(2026, 4, 27), seed = 99L, pageIndex = 5)

            repository.saveSession(session)

            coVerify(exactly = 1) {
                dataStore.updateDailyReviewSession(
                    date = "2026-04-27",
                    seed = 99L,
                    pageIndex = 5,
                )
            }
        }
}
