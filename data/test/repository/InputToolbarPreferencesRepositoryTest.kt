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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: InputToolbarPreferencesRepositoryImpl
 * - Behavior focus: input toolbar drag order must round-trip through settings persistence with stable delimiter serialization.
 * - Observable outcomes: decoded toolbar id list and datastore update calls with trimmed, deduplicated payloads.
 * - TDD proof: Fails before the fix because no repository contract or datastore preference exists for input toolbar ordering.
 * - Excludes: Compose toolbar rendering, reorder gesture physics, and DataStore file I/O.
 */
class InputToolbarPreferencesRepositoryTest : DataFunSpec() {
    init {
        test("input toolbar order is decoded from datastore") { `input toolbar order is decoded from datastore`() }

        test("input toolbar order update persists stable encoded order") { `input toolbar order update persists stable encoded order`() }
    }


    private val dataStore: LomoDataStore = mockk(relaxed = true)
    private val repository = InputToolbarPreferencesRepositoryImpl(dataStore)

    private fun `input toolbar order is decoded from datastore`() =
        runTest {
            every { dataStore.inputToolbarToolOrder } returns flowOf("backfill|camera|todo")

            repository.getInputToolbarToolOrder().first() shouldBe listOf("backfill", "camera", "todo")
        }

    private fun `input toolbar order update persists stable encoded order`() =
        runTest {
            coEvery { dataStore.updateInputToolbarToolOrder("backfill|camera|todo") } returns Unit

            repository.updateInputToolbarToolOrder(
                listOf("backfill", "camera", "todo", "backfill", " "),
            )

            coVerify(exactly = 1) { dataStore.updateInputToolbarToolOrder("backfill|camera|todo") }
        }
}
