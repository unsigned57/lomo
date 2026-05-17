package com.lomo.data.repository


import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: InteractionBehaviorPreferencesRepositoryImpl.
 * - Behavior focus: behavioral interaction preference flow exposure and datastore mutation
 *   delegation. The secure-wipe-before-delete preference is intentionally not part of this
 *   surface anymore — it was internalized as a non-configurable application behavior.
 * - Observable outcomes: emitted booleans for quick-save and scrollbar flags and the
 *   corresponding update* datastore calls.
 * - Red phase: green — regression coverage maintained while the interface shrank with the
 *   secure-wipe internalization.
 * - Excludes: DataStore serialization behavior and higher-level settings aggregation.
 */
class InteractionBehaviorPreferencesRepositoryImplTest : DataFunSpec() {
    init {
        val dataStore: LomoDataStore = mockk(relaxed = true)
        val repository = InteractionBehaviorPreferencesRepositoryImpl(dataStore)

        test("all interaction behavior preference flows are exposed from datastore") {
            runTest {
                every { dataStore.quickSaveOnBackEnabled } returns flowOf(true)
                every { dataStore.scrollbarEnabled } returns flowOf(false)

                repository.isQuickSaveOnBackEnabled().first() shouldBe true
                repository.isScrollbarEnabled().first() shouldBe false
            }
        }

        test("all interaction behavior preference setters delegate to datastore updates") {
            runTest {
                coEvery { dataStore.updateQuickSaveOnBackEnabled(true) } returns Unit
                coEvery { dataStore.updateScrollbarEnabled(false) } returns Unit

                repository.setQuickSaveOnBackEnabled(true)
                repository.setScrollbarEnabled(false)

                coVerify(exactly = 1) { dataStore.updateQuickSaveOnBackEnabled(true) }
                coVerify(exactly = 1) { dataStore.updateScrollbarEnabled(false) }
            }
        }
    }
}
