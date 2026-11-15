package com.lomo.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.datastore.LomoDataStoreKeys
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.MemoEditDraft
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: MemoEditDraftRepositoryImpl
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: persist durable memo edit drafts, lazily migrate legacy edit drafts without a
 *   DraftId once, fail closed on corrupt payloads, and clear only the matching memo id.
 * - Production scope: apps/android/data/src/repository/MemoEditDraftRepositoryImpl.kt
 * - Scenarios:
 *   - Given an empty or blank slot, when read runs, then null is returned.
 *   - Given a written MemoEditDraft, when read runs and then clear runs for another memo id vs the
 *     same memo id, then only the matching memo id removes the slot.
 *   - Given a legacy draft JSON without draftId, when read runs, then a DraftId is minted,
 *     canonical JSON is written back, and subsequent reads reuse the same minted DraftId.
 *   - Given corrupt JSON or blank memoId to clear, when read or clear runs, then it fails closed.
 * - Observable outcomes: decoded MemoEditDraft, minted DraftId persistence, DataStore slot contents,
 *   and thrown IllegalStateException / IllegalArgumentException.
 * - TDD proof: locks legacy edit-draft one-shot migration, targeted clear semantics, and fail-closed
 *   corruption handling that previously had zero host test coverage.
 * - Excludes: editor UI state synchronization and staged media orphan sweeping.
 */
class MemoEditDraftRepositoryImplTest :
    FunSpec({
        fun createStore(scope: CoroutineScope): Pair<LomoDataStore, DataStore<Preferences>> {
            val backingFile =
                Files.createTempFile("lomo-edit-draft", ".preferences_pb").toFile().apply {
                    deleteOnExit()
                }
            val preferences =
                PreferenceDataStoreFactory.create(
                    scope = scope,
                    produceFile = { backingFile },
                )
            val constructor = LomoDataStore::class.java.getDeclaredConstructor(DataStore::class.java)
            constructor.isAccessible = true
            return constructor.newInstance(preferences) to preferences
        }

        test("given empty or blank preference value when read then null is returned") {
            runTest {
                val (store, preferences) = createStore(backgroundScope)
                val repository = MemoEditDraftRepositoryImpl(store)

                repository.read().shouldBeNull()

                preferences.edit { it[LomoDataStoreKeys.MEMO_EDIT_DRAFT] = "   " }
                repository.read().shouldBeNull()
            }
        }

        test("given written edit draft when cleared for another vs matching memo then only matching clears") {
            runTest {
                val (store, _) = createStore(backgroundScope)
                val repository = MemoEditDraftRepositoryImpl(store)
                val draft =
                    MemoEditDraft(
                        draftId = DraftId("edit-draft-1"),
                        memoId = "memo-alpha",
                        baselineRevision = 7L,
                        baselineFingerprint = "fp-alpha",
                        content = "updated draft text",
                    )

                repository.write(draft)
                repository.read() shouldBe draft

                repository.clear("memo-other")
                repository.read() shouldBe draft

                repository.clear("memo-alpha")
                repository.read().shouldBeNull()
            }
        }

        test("given legacy edit draft without draftId when read then it migrates once canonically") {
            runTest {
                val (store, preferences) = createStore(backgroundScope)
                val legacyPayload =
                    """{"memoId":"memo-legacy","baselineRevision":4,"baselineFingerprint":"fp-4","content":"body"}"""
                preferences.edit { it[LomoDataStoreKeys.MEMO_EDIT_DRAFT] = legacyPayload }
                val repository = MemoEditDraftRepositoryImpl(store)

                val firstRead = repository.read().shouldNotBeNull()
                val secondRead = repository.read().shouldNotBeNull()
                val persistedJson =
                    Json.parseToJsonElement(
                        preferences.data.first()[LomoDataStoreKeys.MEMO_EDIT_DRAFT].shouldNotBeNull(),
                    ).jsonObject

                firstRead.memoId shouldBe "memo-legacy"
                firstRead.baselineRevision shouldBe 4L
                firstRead.baselineFingerprint shouldBe "fp-4"
                firstRead.content shouldBe "body"
                secondRead shouldBe firstRead
                persistedJson.containsKey("draftId") shouldBe true
            }
        }

        test("given corrupt edit draft json when read then it fails closed") {
            runTest {
                val (store, preferences) = createStore(backgroundScope)
                preferences.edit { it[LomoDataStoreKeys.MEMO_EDIT_DRAFT] = """{"unknownField":true}""" }
                val repository = MemoEditDraftRepositoryImpl(store)

                shouldThrow<IllegalStateException> {
                    repository.read()
                }
            }
        }

        test("given blank memoId when clear runs then IllegalArgumentException is thrown") {
            runTest {
                val (store, _) = createStore(backgroundScope)
                val repository = MemoEditDraftRepositoryImpl(store)

                shouldThrow<IllegalArgumentException> {
                    repository.clear("  ")
                }
            }
        }
    })
