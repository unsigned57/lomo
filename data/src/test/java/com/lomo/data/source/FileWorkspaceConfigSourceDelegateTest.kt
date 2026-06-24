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
import com.lomo.data.local.datastore.LomoDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: FileWorkspaceConfigSourceDelegate
 * - Behavior focus: root path or URI persistence, root-flow precedence, display-name mapping, and directory creation failure policy.
 * - Observable outcomes: datastore mutation calls, observed root values, resolved display names, and thrown IOException messages.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: concrete storage backend implementations, Android SAF internals, and UI rendering.
 */
class FileWorkspaceConfigSourceDelegateTest : DataFunSpec() {
    init {
        test("setRoot stores direct path for main storage and clears uri") { `setRoot stores direct path for main storage and clears uri`() }

        test("setRoot stores content uri for image storage and clears path") { `setRoot stores content uri for image storage and clears path`() }

        test("setRoot releases persisted permissions no longer backing any slot") { `setRoot releases persisted permissions no longer backing any slot`() }

        test("setRoot keeps persisted permissions still backing a slot") { `setRoot keeps persisted permissions still backing a slot`() }

        test("getRootFlow prefers uri and getRootDisplayNameFlow returns raw path when not using saf") { `getRootFlow prefers uri and getRootDisplayNameFlow returns raw path when not using saf`() }

        test("getRootDisplayNameFlow returns null when storage root is unset") { `getRootDisplayNameFlow returns null when storage root is unset`() }

        test("createDirectory delegates to workspace backend when present") { `createDirectory delegates to workspace backend when present`() }

        test("createDirectory fails closed when storage backend is unavailable") { `createDirectory fails closed when storage backend is unavailable`() }
    }


    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)
    private val backendResolver = mockk<FileStorageBackendResolver>(relaxed = true)

    private fun `setRoot stores direct path for main storage and clears uri`() =
        runTest {
            stubNoConfiguredSlots()
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.setRoot(StorageRootType.MAIN, "/memo/root")

            coVerify(exactly = 1) { dataStore.updateRootUri(null) }
            coVerify(exactly = 1) { dataStore.updateRootDirectory("/memo/root") }
        }

    private fun `setRoot stores content uri for image storage and clears path`() =
        runTest {
            stubNoConfiguredSlots()
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.setRoot(StorageRootType.IMAGE, "content://tree/images")

            coVerify(exactly = 1) { dataStore.updateImageUri("content://tree/images") }
            coVerify(exactly = 1) { dataStore.updateImageDirectory(null) }
        }

    private fun `setRoot releases persisted permissions no longer backing any slot`() =
        runTest {
            val activeUri = uriReturning("content://tree/active")
            val orphanUri = uriReturning("content://tree/orphan")
            stubConfiguredSlots(rootUri = "content://tree/active")
            every { contentResolver.persistedUriPermissions } returns
                listOf(permissionFor(activeUri), permissionFor(orphanUri))
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.setRoot(StorageRootType.MAIN, "content://tree/active")

            verify(exactly = 1) { contentResolver.releasePersistableUriPermission(orphanUri, any()) }
            verify(exactly = 0) { contentResolver.releasePersistableUriPermission(activeUri, any()) }
        }

    private fun `setRoot keeps persisted permissions still backing a slot`() =
        runTest {
            // The grant is held by a different slot (the S3 local sync directory), so it must survive
            // even though it is not the slot being changed.
            val sharedUri = uriReturning("content://tree/shared")
            stubConfiguredSlots(rootUri = "content://tree/root", s3LocalSyncDirectory = "content://tree/shared")
            every { contentResolver.persistedUriPermissions } returns listOf(permissionFor(sharedUri))
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.setRoot(StorageRootType.MAIN, "content://tree/root")

            verify(exactly = 0) { contentResolver.releasePersistableUriPermission(sharedUri, any()) }
        }

    private fun `getRootFlow prefers uri and getRootDisplayNameFlow returns raw path when not using saf`() =
        runTest {
            every { dataStore.rootUri } returns flowOf("content://tree/root")
            every { dataStore.rootDirectory } returns flowOf("/memo/root")
            every { dataStore.voiceUri } returns flowOf(null)
            every { dataStore.voiceDirectory } returns flowOf("/voice/root")
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.getRootFlow(StorageRootType.MAIN).first() shouldBe "content://tree/root"
            delegate.getRootDisplayNameFlow(StorageRootType.VOICE).first() shouldBe "/voice/root"
        }

    private fun `getRootDisplayNameFlow returns null when storage root is unset`() =
        runTest {
            every { dataStore.rootUri } returns flowOf(null)
            every { dataStore.rootDirectory } returns flowOf(null)
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.getRootDisplayNameFlow(StorageRootType.MAIN).first() shouldBe null
        }

    private fun `createDirectory delegates to workspace backend when present`() =
        runTest {
            val backend = mockk<WorkspaceConfigBackend>()
            coEvery { backendResolver.workspaceBackend() } returns backend
            coEvery { backend.createDirectory("archive") } returns "/memo/archive"
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            val created = delegate.createDirectory("archive")

            created shouldBe "/memo/archive"
        }

    private fun `createDirectory fails closed when storage backend is unavailable`() =
        runTest {
            coEvery { backendResolver.workspaceBackend() } returns null
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            var thrown: Throwable? = null
            try {
                delegate.createDirectory("archive")
            } catch (error: Throwable) {
                thrown = error
            }

            (thrown is IOException).shouldBeTrue()
            thrown?.message shouldBe "No storage configured"
        }

    private fun stubNoConfiguredSlots() = stubConfiguredSlots()

    private fun stubConfiguredSlots(
        rootUri: String? = null,
        imageUri: String? = null,
        voiceUri: String? = null,
        syncInboxUri: String? = null,
        s3LocalSyncDirectory: String? = null,
    ) {
        every { context.contentResolver } returns contentResolver
        every { dataStore.rootUri } returns flowOf(rootUri)
        every { dataStore.imageUri } returns flowOf(imageUri)
        every { dataStore.voiceUri } returns flowOf(voiceUri)
        every { dataStore.syncInboxUri } returns flowOf(syncInboxUri)
        every { dataStore.s3LocalSyncDirectory } returns flowOf(s3LocalSyncDirectory)
        every { contentResolver.persistedUriPermissions } returns emptyList()
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
}
