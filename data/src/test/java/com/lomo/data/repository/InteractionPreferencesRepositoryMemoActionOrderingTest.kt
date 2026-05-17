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
 * - Unit under test: MemoActionPreferencesRepositoryImpl
 * - Behavior focus: memo action auto-reorder preference exposure and persisted action-order serialization.
 * - Observable outcomes: emitted auto-reorder flag, decoded action-id list, and datastore update calls with stable serialized payloads.
 * - Red phase: Fails before the fix because the interaction repository does not yet expose memo action ordering preferences or persist the action order payload.
 * - Excludes: Compose rendering, drag gestures, and coordinator-level state aggregation.
 */
class InteractionPreferencesRepositoryMemoActionOrderingTest : DataFunSpec() {
    init {
        test("memo action ordering preferences are exposed from datastore") { `memo action ordering preferences are exposed from datastore`() }

        test("memo action ordering setters persist boolean and stable encoded order") { `memo action ordering setters persist boolean and stable encoded order`() }

        test("blank persisted memo action order decodes to empty list") { `blank persisted memo action order decodes to empty list`() }
    }


    private val dataStore: LomoDataStore = mockk(relaxed = true)
    private val repository = MemoActionPreferencesRepositoryImpl(dataStore)

    private fun `memo action ordering preferences are exposed from datastore`() =
        runTest {
            every { dataStore.memoActionAutoReorderEnabled } returns flowOf(true)
            every { dataStore.memoActionOrder } returns flowOf("history|copy|edit")

            repository.isMemoActionAutoReorderEnabled().first() shouldBe true
            repository.getMemoActionOrder().first() shouldBe listOf("history", "copy", "edit")
        }

    private fun `memo action ordering setters persist boolean and stable encoded order`() =
        runTest {
            coEvery { dataStore.updateMemoActionAutoReorderEnabled(false) } returns Unit
            coEvery { dataStore.updateMemoActionOrder("edit|history|copy") } returns Unit

            repository.setMemoActionAutoReorderEnabled(false)
            repository.updateMemoActionOrder(listOf("edit", "history", "copy"))

            coVerify(exactly = 1) { dataStore.updateMemoActionAutoReorderEnabled(false) }
            coVerify(exactly = 1) { dataStore.updateMemoActionOrder("edit|history|copy") }
        }

    private fun `blank persisted memo action order decodes to empty list`() =
        runTest {
            every { dataStore.memoActionOrder } returns flowOf("")

            repository.getMemoActionOrder().first() shouldBe emptyList<String>()
        }
}
