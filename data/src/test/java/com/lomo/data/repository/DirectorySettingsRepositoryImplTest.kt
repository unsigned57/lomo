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
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope

/*
 * Behavior Contract:
 * - Unit under test: DirectorySettingsRepositoryImpl
 * - Behavior focus: storage-area to root-type mapping, uri-vs-path precedence for current location, and display/apply delegation.
 * - Observable outcomes: observed StorageLocation raw values, null fallthrough, selected datastore field priority, and setRoot arguments.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: filesystem/SAF validity checks and DataStore persistence internals.
 */
class DirectorySettingsRepositoryImplTest : DataFunSpec() {
    init {
        test("observeLocation maps ROOT area to MAIN root flow and wraps raw location") {
            runTest {
                val (dataSource, _, repository) = setUpTest()
                dataSource.setRoot(StorageRootType.MAIN, "/vault/root")

                val location = repository.observeLocation(StorageArea.ROOT).first()

                requireNotNull(location)
                location.raw shouldBe "/vault/root"
            }
        }

        test("observeLocation returns null when underlying flow emits null") {
            runTest {
                val (_, _, repository) = setUpTest()

                repository.observeLocation(StorageArea.IMAGE).first().shouldBeNull()
            }
        }

        test("currentLocation prefers uri over directory for each storage area") {
            runTest {
                val (_, dataStore, repository) = setUpTest()
                dataStore.updateRootUri("content://root")
                dataStore.updateRootDirectory("/root")
                dataStore.updateImageUri("content://images")
                dataStore.updateImageDirectory("/images")
                dataStore.updateVoiceUri("content://voice")
                dataStore.updateVoiceDirectory("/voice")

                repository.currentLocation(StorageArea.ROOT)?.raw shouldBe "content://root"
                repository.currentLocation(StorageArea.IMAGE)?.raw shouldBe "content://images"
                repository.currentLocation(StorageArea.VOICE)?.raw shouldBe "content://voice"
            }
        }

        test("currentLocation falls back to directory when uri is null") {
            runTest {
                val (_, dataStore, repository) = setUpTest()
                dataStore.updateRootDirectory("/root-only")
                dataStore.updateImageDirectory("/images-only")
                dataStore.updateVoiceDirectory("/voice-only")

                repository.currentLocation(StorageArea.ROOT)?.raw shouldBe "/root-only"
                repository.currentLocation(StorageArea.IMAGE)?.raw shouldBe "/images-only"
                repository.currentLocation(StorageArea.VOICE)?.raw shouldBe "/voice-only"
            }
        }

        test("observeDisplayName maps area to matching root type") {
            runTest {
                val (dataSource, _, repository) = setUpTest()
                dataSource.setRoot(StorageRootType.VOICE, "Voice Dir")

                repository.observeDisplayName(StorageArea.VOICE).first() shouldBe "Voice Dir"
            }
        }

        test("applyLocation delegates update using mapped storage root type") {
            runTest {
                val (dataSource, _, repository) = setUpTest()
                val update = StorageAreaUpdate(StorageArea.IMAGE, StorageLocation("content://tree/images"))

                repository.applyLocation(update)

                dataSource.getRootFlow(StorageRootType.IMAGE).first() shouldBe "content://tree/images"
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.setUpTest(): Triple<DirectoryFakeWorkspaceConfigSource, LomoDataStore, DirectorySettingsRepositoryImpl> {
        val dataSource = DirectoryFakeWorkspaceConfigSource()
        val dataStore = createLomoDataStore(backgroundScope)
        val repository =
            DirectorySettingsRepositoryImpl(
                dataSource = dataSource,
                dataStore = dataStore,
            )
        return Triple(dataSource, dataStore, repository)
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

private class DirectoryFakeWorkspaceConfigSource : WorkspaceConfigSource {
    private val roots = MutableStateFlow<Map<StorageRootType, String>>(emptyMap())
    var throwOnSetRoot = false

    override suspend fun setRoot(type: StorageRootType, pathOrUri: String) {
        if (throwOnSetRoot) {
            throw IllegalStateException("setRoot failed")
        }
        roots.value = roots.value + (type to pathOrUri)
    }

    override fun getRootFlow(type: StorageRootType): Flow<String?> =
        roots.map { it[type] }

    override fun getRootDisplayNameFlow(type: StorageRootType): Flow<String?> =
        roots.map { it[type]?.substringAfterLast('/') }

    override suspend fun createDirectory(name: String): String = name
}

