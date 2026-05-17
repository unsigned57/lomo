package com.lomo.data.repository


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
 * Test Contract:
 * - Unit under test: InteractionPreferencesRepositoryImpl
 * - Behavior focus: interaction preference flow exposure and datastore mutation delegation for editor interaction flags.
 * - Observable outcomes: emitted booleans for each preference flag and corresponding update* datastore calls.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Test Change Justification: reason category = pure refactor preserved behavior; removed quick-save assertions from this file because quick-save moved to InteractionBehaviorPreferencesRepositoryImpl, while coverage is retained in a new repository-specific test.
 * - Excludes: DataStore serialization behavior and coordinator-level mutual exclusion policy.
 */
class InteractionPreferencesRepositoryImplTest : DataFunSpec() {
    init {
        test("all interaction preference flows are exposed from datastore") { `all interaction preference flows are exposed from datastore`() }

        test("all interaction preference setters delegate to datastore updates") { `all interaction preference setters delegate to datastore updates`() }
    }


    private val dataStore: LomoDataStore = mockk(relaxed = true)
    private val repository = InteractionPreferencesRepositoryImpl(dataStore)

    private fun `all interaction preference flows are exposed from datastore`() =
        runTest {
            every { dataStore.hapticFeedbackEnabled } returns flowOf(true)
            every { dataStore.showInputHints } returns flowOf(false)
            every { dataStore.doubleTapEditEnabled } returns flowOf(true)
            every { dataStore.freeTextCopyEnabled } returns flowOf(false)

            repository.isHapticFeedbackEnabled().first() shouldBe true
            repository.isShowInputHintsEnabled().first() shouldBe false
            repository.isDoubleTapEditEnabled().first() shouldBe true
            repository.isFreeTextCopyEnabled().first() shouldBe false
        }

    private fun `all interaction preference setters delegate to datastore updates`() =
        runTest {
            coEvery { dataStore.updateHapticFeedbackEnabled(true) } returns Unit
            coEvery { dataStore.updateShowInputHints(false) } returns Unit
            coEvery { dataStore.updateDoubleTapEditEnabled(true) } returns Unit
            coEvery { dataStore.updateFreeTextCopyEnabled(false) } returns Unit

            repository.setHapticFeedbackEnabled(true)
            repository.setShowInputHintsEnabled(false)
            repository.setDoubleTapEditEnabled(true)
            repository.setFreeTextCopyEnabled(false)

            coVerify(exactly = 1) { dataStore.updateHapticFeedbackEnabled(true) }
            coVerify(exactly = 1) { dataStore.updateShowInputHints(false) }
            coVerify(exactly = 1) { dataStore.updateDoubleTapEditEnabled(true) }
            coVerify(exactly = 1) { dataStore.updateFreeTextCopyEnabled(false) }
        }
}
