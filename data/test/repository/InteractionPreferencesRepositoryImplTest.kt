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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope

/*
 * Behavior Contract:
 * - Unit under test: InteractionPreferencesRepositoryImpl
 * - Behavior focus: interaction preference flow exposure and datastore mutation delegation for editor interaction flags.
 * - Observable outcomes: emitted booleans for each preference flag and corresponding update* datastore calls.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Test Change Justification: reason category = pure refactor preserved behavior; removed quick-save assertions from this file because quick-save moved to InteractionBehaviorPreferencesRepositoryImpl, while coverage is retained in a new repository-specific test.
 * - Excludes: DataStore serialization behavior and coordinator-level mutual exclusion policy.
 */
class InteractionPreferencesRepositoryImplTest : DataFunSpec() {
    init {
        test("all interaction preference flows are exposed from datastore") {
            runTest {
                val (dataStore, repository) = setUpTest()
                dataStore.updateHapticFeedbackEnabled(true)
                dataStore.updateShowInputHints(false)
                dataStore.updateDoubleTapEditEnabled(true)
                dataStore.updateFreeTextCopyEnabled(false)

                repository.isHapticFeedbackEnabled().first() shouldBe true
                repository.isShowInputHintsEnabled().first() shouldBe false
                repository.isDoubleTapEditEnabled().first() shouldBe true
                repository.isFreeTextCopyEnabled().first() shouldBe false
            }
        }

        test("all interaction preference setters delegate to datastore updates") {
            runTest {
                val (dataStore, repository) = setUpTest()

                repository.setHapticFeedbackEnabled(true)
                repository.setShowInputHintsEnabled(false)
                repository.setDoubleTapEditEnabled(true)
                repository.setFreeTextCopyEnabled(false)

                dataStore.hapticFeedbackEnabled.first() shouldBe true
                dataStore.showInputHints.first() shouldBe false
                dataStore.doubleTapEditEnabled.first() shouldBe true
                dataStore.freeTextCopyEnabled.first() shouldBe false
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.setUpTest(): Pair<LomoDataStore, InteractionPreferencesRepositoryImpl> {
        val dataStore = createLomoDataStore(backgroundScope)
        val repository = InteractionPreferencesRepositoryImpl(dataStore)
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

