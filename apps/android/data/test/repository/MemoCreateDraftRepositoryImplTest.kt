package com.lomo.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.datastore.LomoDataStoreKeys
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.MemoCreateDraft
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MemoCreateDraftRepositoryImpl
 * - Owning layer: data
 * - Capability: persist the modeled create-draft record and import the retired plain-text key once.
 * - Scenarios:
 *   - Given a retired plain-text draft and an empty modeled slot, When the draft is read, Then the
 *     same text is imported once and the retired key is gone.
 *   - Given a written create draft, When it is read back, Then the modeled record round-trips.
 *   - Given a corrupt payload, When it is read, Then it fails closed instead of returning empty.
 * - Observable outcomes: the modeled draft content and the durable preference state.
 * - TDD proof: the retired key still carries text before the import, so an unchanged reader would
 *   return no draft at all.
 * - Excludes: the editor UI and the edit-draft slot.
 * Test Change Justification:
 * - Reason category: production API signature changed.
 * - Old behavior/assertion being replaced: the prior create-draft call shape.
 * - Why old assertion is no longer correct: the draft API surface changed with the session command path.
 * - Coverage preserved by: the same draft assertions through the updated call shape.
 * - Why this is not fitting the test to the implementation: it tracks the session-command contract.
 */
class MemoCreateDraftRepositoryImplTest : DataFunSpec() {
    init {
        test("given a retired plain text draft when read then it is imported once and removed") {
            runTest {
                val (store, preferences) = createStore(backgroundScope)
                preferences.edit { it[LomoDataStoreKeys.RETIRED_DRAFT_TEXT] = "unsent body" }
                val repository = MemoCreateDraftRepositoryImpl(store)

                repository.read()?.content shouldBe "unsent body"
                repository.read()?.content shouldBe "unsent body"
                preferences.data.first()[LomoDataStoreKeys.RETIRED_DRAFT_TEXT].shouldBeNull()
            }
        }

        test("given a written create draft when read then the modeled record round-trips") {
            runTest {
                val (store, _) = createStore(backgroundScope)
                val repository = MemoCreateDraftRepositoryImpl(store)

                repository.write(MemoCreateDraft("kept body"))

                repository.read() shouldBe MemoCreateDraft("kept body")
                repository.clear()
                repository.read().shouldBeNull()
            }
        }

        test("given a corrupt create draft when read then it fails closed") {
            runTest {
                val (store, preferences) = createStore(backgroundScope)
                preferences.edit { it[LomoDataStoreKeys.MEMO_CREATE_DRAFT] = "{not json" }
                val repository = MemoCreateDraftRepositoryImpl(store)

                val failure = runCatching { repository.read() }.exceptionOrNull()

                failure.shouldBeInstanceOf<IllegalStateException>()
            }
        }
    }

    private fun createStore(scope: CoroutineScope): Pair<LomoDataStore, DataStore<Preferences>> {
        val backingFile = Files.createTempFile("lomo-create-draft", ".preferences_pb").toFile().apply {
            deleteOnExit()
        }
        val preferences = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { backingFile },
        )
        val constructor =
            LomoDataStore::class.java.getDeclaredConstructor(DataStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(preferences) to preferences
    }
}
