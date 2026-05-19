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
 * - Unit under test: MemoActionPreferencesRepositoryImpl
 * - Behavior focus: memo action auto-reorder preference exposure and persisted action-order serialization.
 * - Observable outcomes: emitted auto-reorder flag, decoded action-id list, and datastore update calls with stable serialized payloads.
 * - TDD proof: Fails before the fix because the interaction repository does not yet expose memo action ordering preferences or persist the action order payload.
 * - Excludes: Compose rendering, drag gestures, and coordinator-level state aggregation.
 */
class InteractionPreferencesRepositoryMemoActionOrderingTest : DataFunSpec() {
    init {
        test("memo action ordering preferences are exposed from datastore") {
            runTest {
                val (dataStore, repository) = setUpTest()
                dataStore.updateMemoActionAutoReorderEnabled(true)
                dataStore.updateMemoActionOrder("history|copy|edit")

                repository.isMemoActionAutoReorderEnabled().first() shouldBe true
                repository.getMemoActionOrder().first() shouldBe listOf("history", "copy", "edit")
            }
        }

        test("memo action ordering setters persist boolean and stable encoded order") {
            runTest {
                val (dataStore, repository) = setUpTest()

                repository.setMemoActionAutoReorderEnabled(false)
                repository.updateMemoActionOrder(listOf("edit", "history", "copy"))

                dataStore.memoActionAutoReorderEnabled.first() shouldBe false
                dataStore.memoActionOrder.first() shouldBe "edit|history|copy"
            }
        }

        test("blank persisted memo action order decodes to empty list") {
            runTest {
                val (dataStore, repository) = setUpTest()
                dataStore.updateMemoActionOrder("")

                repository.getMemoActionOrder().first() shouldBe emptyList<String>()
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.setUpTest(): Pair<LomoDataStore, MemoActionPreferencesRepositoryImpl> {
        val dataStore = createLomoDataStore(backgroundScope)
        val repository = MemoActionPreferencesRepositoryImpl(dataStore)
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
