package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: InputToolbarPreferencesRepositoryImpl
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: round-trip input toolbar order through settings persistence.
 *
 * Scenarios:
 * - Given a delimited datastore payload, when getInputToolbarToolOrder is collected, then ids decode in order.
 * - Given duplicate and blank ids, when updateInputToolbarToolOrder runs, then the persisted payload is trimmed and deduped.
 *
 * Observable outcomes:
 * - decoded toolbar id list, recorded datastore payload.
 *
 * TDD proof:
 * - Fails before the fix because no repository contract or datastore preference exists for input
 *   toolbar ordering.
 *
 * Excludes:
 * - Compose toolbar rendering, reorder gesture physics, and DataStore file I/O.
 */
class InputToolbarPreferencesRepositoryTest : DataFunSpec() {
    init {
        test("input toolbar order is decoded from datastore") {
            runTest {
                val store = InputToolbarStoreFixture()
                store.order = "backfill|camera|todo"
                val repository = InputToolbarPreferencesRepositoryImpl(store.dataStore)

                repository.getInputToolbarToolOrder().first() shouldBe listOf("backfill", "camera", "todo")
            }
        }

        test("input toolbar order update persists stable encoded order") {
            runTest {
                val store = InputToolbarStoreFixture()
                val repository = InputToolbarPreferencesRepositoryImpl(store.dataStore)

                repository.updateInputToolbarToolOrder(
                    listOf("backfill", "camera", "todo", "backfill", " "),
                )

                store.lastPersistedOrder shouldBe "backfill|camera|todo"
            }
        }
    }
}

private class InputToolbarStoreFixture {
    var order: String = ""
    var lastPersistedOrder: String? = null

    val dataStore: LomoDataStore = mockk()

    init {
        every { dataStore.inputToolbarToolOrder } answers { flowOf(order) }
        coEvery { dataStore.updateInputToolbarToolOrder(any()) } answers {
            lastPersistedOrder = arg(0)
        }
    }
}
