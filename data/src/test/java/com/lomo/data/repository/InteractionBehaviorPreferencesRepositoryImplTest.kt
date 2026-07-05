package com.lomo.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: InteractionBehaviorPreferencesRepositoryImpl.
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: exposes behavioral interaction preferences from DataStore and persists updates
 *   through the repository contract.
 *
 * Scenarios:
 * - Given DataStore has quick-save, scrollbar, and foreground auto-input values, when repository
 *   flows are read, then each emitted value matches storage.
 * - Given repository setters update quick-save, scrollbar, and foreground auto-input, when
 *   DataStore flows are read, then each stored value reflects the repository command.
 *
 * Observable outcomes:
 * - emitted booleans from repository flows and persisted booleans from DataStore flows.
 *
 * TDD proof:
 * - RED: before foreground auto-input was modeled on InteractionBehaviorPreferencesRepository,
 *   the new flow and setter assertions could not compile.
 *
 * - Excludes: DataStore serialization behavior and higher-level settings aggregation.
 */
class InteractionBehaviorPreferencesRepositoryImplTest : DataFunSpec() {
    init {
        test("all interaction behavior preference flows are exposed from datastore") {
            runTest {
                val (dataStore, repository) = setUpTest()
                dataStore.updateQuickSaveOnBackEnabled(true)
                dataStore.updateScrollbarEnabled(false)
                dataStore.updateAutoOpenInputOnForeground(true)

                repository.isQuickSaveOnBackEnabled().first() shouldBe true
                repository.isScrollbarEnabled().first() shouldBe false
                repository.isAutoOpenInputOnForegroundEnabled().first() shouldBe true
            }
        }

        test("all interaction behavior preference setters delegate to datastore updates") {
            runTest {
                val (dataStore, repository) = setUpTest()

                repository.setQuickSaveOnBackEnabled(true)
                repository.setScrollbarEnabled(false)
                repository.setAutoOpenInputOnForegroundEnabled(true)

                dataStore.quickSaveOnBackEnabled.first() shouldBe true
                dataStore.scrollbarEnabled.first() shouldBe false
                dataStore.autoOpenInputOnForeground.first() shouldBe true
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.setUpTest(): Pair<LomoDataStore, InteractionBehaviorPreferencesRepositoryImpl> {
        val dataStore = createLomoDataStore(backgroundScope)
        val repository = InteractionBehaviorPreferencesRepositoryImpl(dataStore)
        return Pair(dataStore, repository)
    }

    private fun createLomoDataStore(scope: CoroutineScope): LomoDataStore {
        val backingFile = Files.createTempFile("lomo-datastore", ".preferences_pb").toFile().apply {
            deleteOnExit()
        }
        val realDataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { backingFile },
        )
        val constructor = LomoDataStore::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(realDataStore)
    }
}
