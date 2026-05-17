package com.lomo.data.source


import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: FileWorkspaceConfigSourceDelegate
 * - Behavior focus: content-uri detection resilience and storage-root-specific flow mapping.
 * - Observable outcomes: datastore update calls by root type and emitted root value preference per type.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: SAF `DocumentFile` lookups, Android `Uri.parse` behavior, and backend filesystem operations.
 */
class FileWorkspaceConfigSourceDelegateMoreTest : DataFunSpec() {
    init {
        test("setRoot recognizes uppercase content scheme for voice root") { `setRoot recognizes uppercase content scheme for voice root`() }

        test("setRoot treats malformed uri text as direct path") { `setRoot treats malformed uri text as direct path`() }

        test("getRootFlow reads image path when image uri is absent") { `getRootFlow reads image path when image uri is absent`() }

        test("getRootFlow prefers voice uri over voice path when both exist") { `getRootFlow prefers voice uri over voice path when both exist`() }

        test("getRootDisplayNameFlow keeps direct main path unchanged") { `getRootDisplayNameFlow keeps direct main path unchanged`() }
    }


    private val context = mockk<Context>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)
    private val backendResolver = mockk<FileStorageBackendResolver>(relaxed = true)

    private fun `setRoot recognizes uppercase content scheme for voice root`() =
        runTest {
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.setRoot(StorageRootType.VOICE, "CONTENT://tree/voice")

            coVerify(exactly = 1) { dataStore.updateVoiceUri("CONTENT://tree/voice") }
            coVerify(exactly = 1) { dataStore.updateVoiceDirectory(null) }
        }

    private fun `setRoot treats malformed uri text as direct path`() =
        runTest {
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.setRoot(StorageRootType.IMAGE, "not a valid uri % value")

            coVerify(exactly = 1) { dataStore.updateImageUri(null) }
            coVerify(exactly = 1) { dataStore.updateImageDirectory("not a valid uri % value") }
        }

    private fun `getRootFlow reads image path when image uri is absent`() =
        runTest {
            every { dataStore.imageUri } returns flowOf(null)
            every { dataStore.imageDirectory } returns flowOf("/images/path")
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.getRootFlow(StorageRootType.IMAGE).first() shouldBe "/images/path"
        }

    private fun `getRootFlow prefers voice uri over voice path when both exist`() =
        runTest {
            every { dataStore.voiceUri } returns flowOf("content://tree/voice")
            every { dataStore.voiceDirectory } returns flowOf("/voice/path")
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.getRootFlow(StorageRootType.VOICE).first() shouldBe "content://tree/voice"
        }

    private fun `getRootDisplayNameFlow keeps direct main path unchanged`() =
        runTest {
            every { dataStore.rootUri } returns flowOf(null)
            every { dataStore.rootDirectory } returns flowOf("/main/workspace")
            val delegate = FileWorkspaceConfigSourceDelegate(context, dataStore, backendResolver)

            delegate.getRootDisplayNameFlow(StorageRootType.MAIN).first() shouldBe "/main/workspace"
        }
}
