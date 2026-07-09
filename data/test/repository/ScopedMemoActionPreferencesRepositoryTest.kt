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
import com.lomo.domain.repository.MemoActionPreferencesRepository
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
 * - Unit under test: MemoActionPreferencesRepositoryImpl scoped order persistence.
 * - Behavior focus: non-main memo action rows must persist independent order histories while the existing
 *   legacy memo_action_order key remains the main-list order.
 * - Observable outcomes: decoded order by scope, aggregate order map, and serialized DataStore writes that
 *   preserve other scopes.
 * - TDD proof: Fails before the fix because only one global memo_action_order value exists, so non-main
 *   screen reorders overwrite main-list order instead of writing a scoped payload.
 * - Excludes: App coordinator promotion policy, Compose menus, and DataStore runtime behavior.
 */
class ScopedMemoActionPreferencesRepositoryTest : DataFunSpec() {
    init {
        test("scoped memo action orders decode independently from legacy main order") {
            runTest {
                val (dataStore, repository) = setUpTest()
                dataStore.updateMemoActionOrder("copy|edit")
                dataStore.updateMemoActionOrdersByScope("""{"orders":{"gallery":["jump","copy"],"search":["edit"]}}""")

                repository.getMemoActionOrder().first() shouldBe listOf("copy", "edit")
                repository.getMemoActionOrder("main").first() shouldBe listOf("copy", "edit")
                repository.getMemoActionOrder("gallery").first() shouldBe listOf("jump", "copy")
                repository.getMemoActionOrdersByScope().first() shouldBe mapOf(
                    "gallery" to listOf("jump", "copy"),
                    "search" to listOf("edit"),
                    "main" to listOf("copy", "edit"),
                )
            }
        }

        test("updating one scoped order preserves the other scoped orders") {
            runTest {
                val (dataStore, repository) = setUpTest()
                dataStore.updateMemoActionOrdersByScope("""{"orders":{"gallery":["jump"],"search":["copy"]}}""")

                repository.updateMemoActionOrder(
                    scope = "search",
                    actionOrder = listOf("edit", "copy", "edit", " "),
                )

                dataStore.memoActionOrdersByScope.first() shouldBe """{"orders":{"gallery":["jump"],"search":["edit","copy"]}}"""
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.setUpTest(): Pair<LomoDataStore, MemoActionPreferencesRepository> {
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
