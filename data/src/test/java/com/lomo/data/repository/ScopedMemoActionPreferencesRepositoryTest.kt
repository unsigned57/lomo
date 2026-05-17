package com.lomo.data.repository


import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.repository.MemoActionPreferencesRepository
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
 * - Unit under test: MemoActionPreferencesRepositoryImpl scoped order persistence.
 * - Behavior focus: non-main memo action rows must persist independent order histories while the existing
 *   legacy memo_action_order key remains the main-list order.
 * - Observable outcomes: decoded order by scope, aggregate order map, and serialized DataStore writes that
 *   preserve other scopes.
 * - Red phase: Fails before the fix because only one global memo_action_order value exists, so non-main
 *   screen reorders overwrite main-list order instead of writing a scoped payload.
 * - Excludes: App coordinator promotion policy, Compose menus, and DataStore runtime behavior.
 */
class ScopedMemoActionPreferencesRepositoryTest : DataFunSpec() {
    init {
        test("scoped memo action orders decode independently from legacy main order") { `scoped memo action orders decode independently from legacy main order`() }

        test("updating one scoped order preserves the other scoped orders") { `updating one scoped order preserves the other scoped orders`() }
    }


    private val dataStore: LomoDataStore = mockk(relaxed = true)
    private val repository: MemoActionPreferencesRepository = MemoActionPreferencesRepositoryImpl(dataStore)

    private fun `scoped memo action orders decode independently from legacy main order`() =
        runTest {
            every { dataStore.memoActionOrder } returns flowOf("copy|edit")
            every { dataStore.memoActionOrdersByScope } returns
                flowOf("""{"orders":{"gallery":["jump","copy"],"search":["edit"]}}""")

            repository.getMemoActionOrder().first() shouldBe listOf("copy", "edit")
            repository.getMemoActionOrder("main").first() shouldBe listOf("copy", "edit")
            repository.getMemoActionOrder("gallery").first() shouldBe listOf("jump", "copy")
            repository.getMemoActionOrdersByScope().first() shouldBe mapOf(
                    "gallery" to listOf("jump", "copy"),
                    "search" to listOf("edit"),
                    "main" to listOf("copy", "edit"),
                )
        }

    private fun `updating one scoped order preserves the other scoped orders`() =
        runTest {
            every { dataStore.memoActionOrdersByScope } returns
                flowOf("""{"orders":{"gallery":["jump"],"search":["copy"]}}""")
            coEvery {
                dataStore.updateMemoActionOrdersByScope(
                    """{"orders":{"gallery":["jump"],"search":["edit","copy"]}}""",
                )
            } returns Unit

            repository.updateMemoActionOrder(
                scope = "search",
                actionOrder = listOf("edit", "copy", "edit", " "),
            )

            coVerify(exactly = 1) {
                dataStore.updateMemoActionOrdersByScope(
                    """{"orders":{"gallery":["jump"],"search":["edit","copy"]}}""",
                )
            }
            coVerify(exactly = 0) { dataStore.updateMemoActionOrder(any()) }
        }
}
