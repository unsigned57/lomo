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
 * - Unit under test: InteractionBehaviorPreferencesRepositoryImpl
 * - Behavior focus: behavioral interaction preference flow exposure and datastore mutation delegation.
 * - Observable outcomes: emitted booleans for quick-save and scrollbar flags and corresponding update* datastore calls.
 * - Red phase: Green - regression coverage added while adapting interface split introduced by production refactor.
 * - Excludes: DataStore serialization behavior and higher-level settings aggregation.
 */
class InteractionBehaviorPreferencesRepositoryImplTest {
    private val dataStore: LomoDataStore = mockk(relaxed = true)
    private val repository = InteractionBehaviorPreferencesRepositoryImpl(dataStore)

    @Test
    fun `all interaction behavior preference flows are exposed from datastore`() =
        runTest {
            every { dataStore.quickSaveOnBackEnabled } returns flowOf(true)
            every { dataStore.scrollbarEnabled } returns flowOf(false)

            assertEquals(true, repository.isQuickSaveOnBackEnabled().first())
            assertEquals(false, repository.isScrollbarEnabled().first())
        }

    @Test
    fun `all interaction behavior preference setters delegate to datastore updates`() =
        runTest {
            coEvery { dataStore.updateQuickSaveOnBackEnabled(true) } returns Unit
            coEvery { dataStore.updateScrollbarEnabled(false) } returns Unit

            repository.setQuickSaveOnBackEnabled(true)
            repository.setScrollbarEnabled(false)

            coVerify(exactly = 1) { dataStore.updateQuickSaveOnBackEnabled(true) }
            coVerify(exactly = 1) { dataStore.updateScrollbarEnabled(false) }
        }
}
