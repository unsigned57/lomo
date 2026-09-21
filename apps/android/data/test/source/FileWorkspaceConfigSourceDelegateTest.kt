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



import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.DataFunSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/*
 * Behavior Contract:
 * - Unit under test: FileWorkspaceConfigSourceDelegate
 * - Behavior focus: root path or URI persistence, root-flow precedence, display-name mapping, and directory creation failure policy.
 * - Observable outcomes: persisted root values in a real preference store, observed root values,
 *   created filesystem directories, and thrown IOException messages.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: SAF `DocumentFile` lookups (mocked Android framework seams), and DataStore persistence internals.
 */
class FileWorkspaceConfigSourceDelegateTest : DataFunSpec() {
    init {
        test("setRoot stores direct path for main storage and clears uri") {
            runTest {
                val (delegate, dataStore) = setUpDelegate()

                delegate.setRoot(StorageRootType.MAIN, "/memo/root")

                dataStore.rootUri.first() shouldBe null
                dataStore.rootDirectory.first() shouldBe "/memo/root"
            }
        }

        test("setRoot stores content uri for image storage and clears path") {
            runTest {
                val (delegate, dataStore) = setUpDelegate()

                delegate.setRoot(StorageRootType.IMAGE, "content://tree/images")

                dataStore.imageUri.first() shouldBe "content://tree/images"
                dataStore.imageDirectory.first() shouldBe null
            }
        }

        test("setRoot releases persisted permissions no longer backing any slot") {
            runTest {
                val activeUri = uriReturning("content://tree/active")
                val orphanUri = uriReturning("content://tree/orphan")
                stubPersistedUriPermissions(listOf(permissionFor(activeUri), permissionFor(orphanUri)))
                val (delegate, dataStore) = setUpDelegate()

                delegate.setRoot(StorageRootType.MAIN, "content://tree/active")

                verify(exactly = 1) { contentResolver.releasePersistableUriPermission(orphanUri, any()) }
                verify(exactly = 0) { contentResolver.releasePersistableUriPermission(activeUri, any()) }
                dataStore.rootUri.first() shouldBe "content://tree/active"
            }
        }

        test("setRoot keeps persisted permissions still backing a slot") {
            runTest {
                // The grant is held by a different slot (the S3 local sync directory), so it must survive
                // even though it is not the slot being changed.
                val sharedUri = uriReturning("content://tree/shared")
                val (delegate, dataStore) = setUpDelegate()
                dataStore.updateS3LocalSyncDirectory("content://tree/shared")
                stubPersistedUriPermissions(listOf(permissionFor(sharedUri)))

                delegate.setRoot(StorageRootType.MAIN, "content://tree/root")

                verify(exactly = 0) { contentResolver.releasePersistableUriPermission(sharedUri, any()) }
                dataStore.rootUri.first() shouldBe "content://tree/root"
            }
        }

        test("getRootFlow classifies a content uri and getRootDisplayNameFlow returns a filesystem path") {
            runTest {
                val (delegate, dataStore) = setUpDelegate()
                dataStore.updateRootUri("content://tree/root")
                dataStore.updateVoiceDirectory("/voice/root")

                delegate.getRootFlow(StorageRootType.MAIN).first() shouldBe "content://tree/root"
                delegate.getRootDisplayNameFlow(StorageRootType.VOICE).first() shouldBe "/voice/root"
            }
        }

        test("getRootDisplayNameFlow returns null when storage root is unset") {
            runTest {
                val (delegate, _) = setUpDelegate()

                delegate.getRootDisplayNameFlow(StorageRootType.MAIN).first() shouldBe null
            }
        }

        test("createDirectory delegates to workspace backend when present") {
            runTest {
                val (delegate, dataStore) = setUpDelegate()
                val workspaceRoot = Files.createTempDirectory("lomo-workspace-root").toFile().apply { deleteOnExit() }
                dataStore.updateRootDirectory(workspaceRoot.absolutePath)

                val created = delegate.createDirectory("archive")

                created shouldBe File(workspaceRoot, "archive").absolutePath
                File(created).isDirectory shouldBe true
            }
        }

        test("createDirectory fails closed when storage backend is unavailable") {
            runTest {
                val (delegate, _) = setUpDelegate()

                val thrown = runCatching { delegate.createDirectory("archive") }.exceptionOrNull()

                thrown.shouldBeInstanceOf<IOException>()
                thrown.message shouldBe "No storage configured"
            }
        }
    }

    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)

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

    private fun stubPersistedUriPermissions(permissions: List<UriPermission>) {
        every { context.contentResolver } returns contentResolver
        every { contentResolver.persistedUriPermissions } returns permissions
    }

    private fun uriReturning(value: String): Uri {
        val uri = mockk<Uri>()
        every { uri.toString() } returns value
        return uri
    }

    private fun permissionFor(permissionUri: Uri): UriPermission {
        val permission = mockk<UriPermission>()
        every { permission.uri } returns permissionUri
        return permission
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
