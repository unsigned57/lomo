package com.lomo.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.datastore.LomoDataStoreKeys
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.QuickBallAction
import com.lomo.domain.model.QuickBallConfig
import com.lomo.domain.model.QuickBallGesture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/*
 * Behavior Contract:
 * Capability: atomically persist quick-ball bindings and expose the same mapping in app snapshots;
 * owning layer: data; priority: P1.
 * Scenarios:
 * - Given concurrent updates to different gestures, when persisted, then neither binding is lost.
 * - Given a saved mapping, when the store is reopened, then it retains the mapping; reset restores defaults.
 * - Given malformed storage, when read or changed, then it is rejected without overwriting the bytes.
 * Observable outcomes: durable preference bytes, typed repository and app snapshot values, failures.
 * TDD proof: feature RED in QuickBallConfigTest (missing catalog descriptor); GREEN runs this adapter
 * against real Preferences DataStore, including close/reopen and concurrent writes.
 * GREEN: ./kotlin test --include-module=app --include-module=data --include-module=domain
 * --include-module=ui-components; all 2598 discovered tests passed (including both domain targets),
 * evidence: /tmp/lomo-quick-ball-final-tests.stdout.
 * Excludes: Android process lifecycle, Compose input delivery and encrypted export file transport.
 */
class QuickBallPreferencesTest : DataFunSpec() {
    init {
        test("given concurrent gesture updates when saved then the app snapshot contains both") {
            runTest {
                val file = Files.createTempFile("lomo-quick-ball", ".preferences_pb").toFile().apply { deleteOnExit() }
                val store = quickBallStore(
                    PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }),
                )
                val repository = InteractionBehaviorPreferencesRepositoryImpl(store)
                coroutineScope {
                    launch { repository.setQuickBallAction(QuickBallGesture.UP, QuickBallAction.NONE) }
                    launch { repository.setQuickBallAction(QuickBallGesture.RIGHT, QuickBallAction.ACTION_MENU) }
                }
                val expected = QuickBallConfig.defaults().copy(
                    up = QuickBallAction.NONE,
                    right = QuickBallAction.ACTION_MENU,
                )
                repository.getQuickBallConfig().first() shouldBe expected
                AppPreferencesSnapshotRepositoryImpl(
                    store,
                ).observeAppPreferenceSnapshot().first().quickBallConfig shouldBe expected
            }
        }

        test("given a stored mapping when reopened then the selection persists and reset restores defaults") {
            runTest {
                val file = Files.createTempFile(
                    "lomo-quick-ball-reopen",
                    ".preferences_pb",
                ).toFile().apply { deleteOnExit() }
                val firstJob = Job()
                val firstScope = CoroutineScope(coroutineContext + firstJob)
                val firstStore = quickBallStore(
                    PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }),
                )
                InteractionBehaviorPreferencesRepositoryImpl(
                    firstStore,
                ).setQuickBallAction(QuickBallGesture.LEFT, QuickBallAction.GALLERY)
                firstScope.cancel()
                firstJob.join()
                val reopened = quickBallStore(
                    PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }),
                )
                val repository = InteractionBehaviorPreferencesRepositoryImpl(reopened)
                repository.getQuickBallConfig().first().left shouldBe QuickBallAction.GALLERY
                repository.resetQuickBallConfig()
                repository.getQuickBallConfig().first() shouldBe QuickBallConfig.defaults()
            }
        }

        test("given malformed stored bindings when read or updated then bytes are preserved and errors surface") {
            runTest {
                val file = Files.createTempFile(
                    "lomo-quick-ball-invalid",
                    ".preferences_pb",
                ).toFile().apply { deleteOnExit() }
                val rawStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
                rawStore.edit { it[LomoDataStoreKeys.QUICK_BALL_CONFIG] = "invalid" }
                val repository = InteractionBehaviorPreferencesRepositoryImpl(quickBallStore(rawStore))
                shouldThrow<IllegalArgumentException> { repository.getQuickBallConfig().first() }
                shouldThrow<IllegalArgumentException> {
                    repository.setQuickBallAction(
                        QuickBallGesture.UP,
                        QuickBallAction.SEARCH,
                    )
                }
                rawStore.data.first()[LomoDataStoreKeys.QUICK_BALL_CONFIG] shouldBe "invalid"
            }
        }
    }
}

private fun quickBallStore(backing: DataStore<Preferences>): LomoDataStore {
    val constructor = LomoDataStore::class.java.getDeclaredConstructor(DataStore::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(backing)
}
