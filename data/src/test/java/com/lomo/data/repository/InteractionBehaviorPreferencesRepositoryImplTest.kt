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
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope

/*
 * Behavior Contract:
 * - Unit under test: InteractionBehaviorPreferencesRepositoryImpl.
 * - Behavior focus: behavioral interaction preference flow exposure and datastore mutation
 *   delegation. The secure-wipe-before-delete preference is intentionally not part of this
 *   surface anymore — it was internalized as a non-configurable application behavior.
 * - Observable outcomes: emitted booleans for quick-save and scrollbar flags and the
 *   corresponding update* datastore calls.
 * - TDD proof: green — regression coverage maintained while the interface shrank with the
 *   secure-wipe internalization.
 * - Excludes: DataStore serialization behavior and higher-level settings aggregation.
 */
class InteractionBehaviorPreferencesRepositoryImplTest : DataFunSpec() {
    init {
        test("all interaction behavior preference flows are exposed from datastore") {
            runTest {
                val (dataStore, repository) = setUpTest()
                dataStore.updateQuickSaveOnBackEnabled(true)
                dataStore.updateScrollbarEnabled(false)

                repository.isQuickSaveOnBackEnabled().first() shouldBe true
                repository.isScrollbarEnabled().first() shouldBe false
            }
        }

        test("all interaction behavior preference setters delegate to datastore updates") {
            runTest {
                val (dataStore, repository) = setUpTest()

                repository.setQuickSaveOnBackEnabled(true)
                repository.setScrollbarEnabled(false)

                dataStore.quickSaveOnBackEnabled.first() shouldBe true
                dataStore.scrollbarEnabled.first() shouldBe false
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
