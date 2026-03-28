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
 * - Unit under test: InteractionPreferencesRepositoryImpl
 * - Behavior focus: interaction preference flow exposure and datastore mutation delegation.
 * - Observable outcomes: emitted booleans for each preference flag and corresponding update* datastore calls.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: DataStore serialization behavior and coordinator-level mutual exclusion policy.
 */
class InteractionPreferencesRepositoryImplTest {
    private val dataStore: LomoDataStore = mockk(relaxed = true)
    private val repository = InteractionPreferencesRepositoryImpl(dataStore)

    @Test
    fun `all interaction preference flows are exposed from datastore`() =
        runTest {
            every { dataStore.hapticFeedbackEnabled } returns flowOf(true)
            every { dataStore.showInputHints } returns flowOf(false)
            every { dataStore.doubleTapEditEnabled } returns flowOf(true)
            every { dataStore.freeTextCopyEnabled } returns flowOf(false)
            every { dataStore.quickSaveOnBackEnabled } returns flowOf(true)

            assertEquals(true, repository.isHapticFeedbackEnabled().first())
            assertEquals(false, repository.isShowInputHintsEnabled().first())
            assertEquals(true, repository.isDoubleTapEditEnabled().first())
            assertEquals(false, repository.isFreeTextCopyEnabled().first())
            assertEquals(true, repository.isQuickSaveOnBackEnabled().first())
        }

    @Test
    fun `all interaction preference setters delegate to datastore updates`() =
        runTest {
            coEvery { dataStore.updateHapticFeedbackEnabled(true) } returns Unit
            coEvery { dataStore.updateShowInputHints(false) } returns Unit
            coEvery { dataStore.updateDoubleTapEditEnabled(true) } returns Unit
            coEvery { dataStore.updateFreeTextCopyEnabled(false) } returns Unit
            coEvery { dataStore.updateQuickSaveOnBackEnabled(true) } returns Unit

            repository.setHapticFeedbackEnabled(true)
            repository.setShowInputHintsEnabled(false)
            repository.setDoubleTapEditEnabled(true)
            repository.setFreeTextCopyEnabled(false)
            repository.setQuickSaveOnBackEnabled(true)

            coVerify(exactly = 1) { dataStore.updateHapticFeedbackEnabled(true) }
            coVerify(exactly = 1) { dataStore.updateShowInputHints(false) }
            coVerify(exactly = 1) { dataStore.updateDoubleTapEditEnabled(true) }
            coVerify(exactly = 1) { dataStore.updateFreeTextCopyEnabled(false) }
            coVerify(exactly = 1) { dataStore.updateQuickSaveOnBackEnabled(true) }
        }
}

