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
import com.lomo.data.local.datastore.LomoDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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

        test("getRootFlow prefers uri and getRootDisplayNameFlow returns raw path when not using saf") { `getRootFlow prefers uri and getRootDisplayNameFlow returns raw path when not using saf`() }

        test("getRootDisplayNameFlow returns null when storage root is unset") { `getRootDisplayNameFlow returns null when storage root is unset`() }

        test("createDirectory delegates to workspace backend when present") { `createDirectory delegates to workspace backend when present`() }

        test("createDirectory fails closed when storage backend is unavailable") { `createDirectory fails closed when storage backend is unavailable`() }
    }


    private val context = mockk<Context>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)
    private val backendResolver = mockk<FileStorageBackendResolver>(relaxed = true)

    private fun `setRoot stores direct path for main storage and clears uri`() =
        runTest {
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.setRoot(StorageRootType.MAIN, "/memo/root")

            coVerify(exactly = 1) { dataStore.updateRootUri(null) }
            coVerify(exactly = 1) { dataStore.updateRootDirectory("/memo/root") }
        }

    private fun `setRoot stores content uri for image storage and clears path`() =
        runTest {
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.setRoot(StorageRootType.IMAGE, "content://tree/images")

            coVerify(exactly = 1) { dataStore.updateImageUri("content://tree/images") }
            coVerify(exactly = 1) { dataStore.updateImageDirectory(null) }
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
}
