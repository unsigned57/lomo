package com.lomo.data.source

import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/*
 * Test Contract:
 * - Unit under test: FileWorkspaceConfigSourceDelegate
 * - Behavior focus: root path or URI persistence, root-flow precedence, display-name mapping, and directory creation failure policy.
 * - Observable outcomes: datastore mutation calls, observed root values, resolved display names, and thrown IOException messages.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: concrete storage backend implementations, Android SAF internals, and UI rendering.
 */
class FileWorkspaceConfigSourceDelegateTest {
    private val context = mockk<Context>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)
    private val backendResolver = mockk<FileStorageBackendResolver>(relaxed = true)

    @Test
    fun `setRoot stores direct path for main storage and clears uri`() =
        runTest {
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.setRoot(StorageRootType.MAIN, "/memo/root")

            coVerify(exactly = 1) { dataStore.updateRootUri(null) }
            coVerify(exactly = 1) { dataStore.updateRootDirectory("/memo/root") }
        }

    @Test
    fun `setRoot stores content uri for image storage and clears path`() =
        runTest {
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.setRoot(StorageRootType.IMAGE, "content://tree/images")

            coVerify(exactly = 1) { dataStore.updateImageUri("content://tree/images") }
            coVerify(exactly = 1) { dataStore.updateImageDirectory(null) }
        }

    @Test
    fun `getRootFlow prefers uri and getRootDisplayNameFlow returns raw path when not using saf`() =
        runTest {
            every { dataStore.rootUri } returns flowOf("content://tree/root")
            every { dataStore.rootDirectory } returns flowOf("/memo/root")
            every { dataStore.voiceUri } returns flowOf(null)
            every { dataStore.voiceDirectory } returns flowOf("/voice/root")
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            assertEquals("content://tree/root", delegate.getRootFlow(StorageRootType.MAIN).first())
            assertEquals("/voice/root", delegate.getRootDisplayNameFlow(StorageRootType.VOICE).first())
        }

    @Test
    fun `getRootDisplayNameFlow returns null when storage root is unset`() =
        runTest {
            every { dataStore.rootUri } returns flowOf(null)
            every { dataStore.rootDirectory } returns flowOf(null)
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            assertEquals(null, delegate.getRootDisplayNameFlow(StorageRootType.MAIN).first())
        }

    @Test
    fun `createDirectory delegates to workspace backend when present`() =
        runTest {
            val backend = mockk<WorkspaceConfigBackend>()
            coEvery { backendResolver.workspaceBackend() } returns backend
            coEvery { backend.createDirectory("archive") } returns "/memo/archive"
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            val created = delegate.createDirectory("archive")

            assertEquals("/memo/archive", created)
        }

    @Test
    fun `createDirectory fails closed when storage backend is unavailable`() =
        runTest {
            coEvery { backendResolver.workspaceBackend() } returns null
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            var thrown: Throwable? = null
            try {
                delegate.createDirectory("archive")
            } catch (error: Throwable) {
                thrown = error
            }

            assertTrue(thrown is IOException)
            assertEquals("No storage configured", thrown?.message)
        }
}
