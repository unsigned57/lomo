package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: InputToolbarPreferencesRepositoryImpl
 * - Behavior focus: input toolbar drag order must round-trip through settings persistence with stable delimiter serialization.
 * - Observable outcomes: decoded toolbar id list and datastore update calls with trimmed, deduplicated payloads.
 * - Red phase: Fails before the fix because no repository contract or datastore preference exists for input toolbar ordering.
 * - Excludes: Compose toolbar rendering, reorder gesture physics, and DataStore file I/O.
 */
class InputToolbarPreferencesRepositoryTest {
    private val dataStore: LomoDataStore = mockk(relaxed = true)
    private val repository = InputToolbarPreferencesRepositoryImpl(dataStore)

    @Test
    fun `input toolbar order is decoded from datastore`() =
        runTest {
            every { dataStore.inputToolbarToolOrder } returns flowOf("backfill|camera|todo")

            assertEquals(
                listOf("backfill", "camera", "todo"),
                repository.getInputToolbarToolOrder().first(),
            )
        }

    @Test
    fun `input toolbar order update persists stable encoded order`() =
        runTest {
            coEvery { dataStore.updateInputToolbarToolOrder("backfill|camera|todo") } returns Unit

            repository.updateInputToolbarToolOrder(
                listOf("backfill", "camera", "todo", "backfill", " "),
            )

            coVerify(exactly = 1) { dataStore.updateInputToolbarToolOrder("backfill|camera|todo") }
        }
}
