package com.lomo.data.source

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



import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.DataFunSpec
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: FileWorkspaceConfigSourceDelegate
 * - Behavior focus: content-uri detection resilience and storage-root-specific flow mapping.
 * - Observable outcomes: persisted root values per storage type in a real preference store and
 *   emitted root value preference per type.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: SAF `DocumentFile` lookups, Android `Uri.parse` behavior, and backend filesystem operations.
 */
class FileWorkspaceConfigSourceDelegateMoreTest : DataFunSpec() {
    init {
        test("setRoot recognizes uppercase content scheme for voice root") {
            runTest {
                val (delegate, dataStore) = setUpDelegate()

                delegate.setRoot(StorageRootType.VOICE, "CONTENT://tree/voice")

                dataStore.voiceUri.first() shouldBe "CONTENT://tree/voice"
                dataStore.voiceDirectory.first() shouldBe null
            }
        }

        test("setRoot treats malformed uri text as direct path") {
            runTest {
                val (delegate, dataStore) = setUpDelegate()

                delegate.setRoot(StorageRootType.IMAGE, "not a valid uri % value")

                dataStore.imageUri.first() shouldBe null
                dataStore.imageDirectory.first() shouldBe "not a valid uri % value"
            }
        }

        test("getRootFlow reads image path when image uri is absent") {
            runTest {
                val (delegate, dataStore) = setUpDelegate()
                dataStore.updateImageDirectory("/images/path")

                delegate.getRootFlow(StorageRootType.IMAGE).first() shouldBe "/images/path"
            }
        }

        test("getRootFlow prefers voice uri over voice path when both exist") {
            runTest {
                val (delegate, dataStore) = setUpDelegate()
                dataStore.updateVoiceUri("content://tree/voice")
                dataStore.updateVoiceDirectory("/voice/path")

                delegate.getRootFlow(StorageRootType.VOICE).first() shouldBe "content://tree/voice"
            }
        }

        test("getRootDisplayNameFlow keeps direct main path unchanged") {
            runTest {
                val (delegate, dataStore) = setUpDelegate()
                dataStore.updateRootDirectory("/main/workspace")

                delegate.getRootDisplayNameFlow(StorageRootType.MAIN).first() shouldBe "/main/workspace"
            }
        }
    }

    private val context = mockk<Context>(relaxed = true)

    private fun TestScope.setUpDelegate(): Pair<FileWorkspaceConfigSourceDelegate, LomoDataStore> {
        val dataStore = createLomoDataStore(backgroundScope)
        val delegate =
            FileWorkspaceConfigSourceDelegate(
                context = context,
                dataStore = dataStore,
                backendResolver = FileStorageBackendResolver(context, dataStore),
            )
        return Pair(delegate, dataStore)
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
