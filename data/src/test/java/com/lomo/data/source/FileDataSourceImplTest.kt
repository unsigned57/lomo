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
import com.lomo.data.source.StorageRootType
import io.mockk.MockKAnnotations
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec

/*
 * Behavior Contract:
 * - Unit under test: FileDataSourceImpl delegation.
 * - Behavior focus: ensuring that setting storage roots (Image, Root, etc.) correctly
 *   dispatches to the underlying DataStore for both raw file paths and content URIs.
 * - Observable outcomes: DataStore update calls for directory paths and URI strings.
 * - TDD proof: Fails before the fix because FileDataSourceImpl was not yet updated to
 *   handle the dual path/URI storage model, so setting a URI would attempt to persist it as a file path.
 * - Excludes: actual file I/O, SAF permission granting, and cross-backend resolution logic.
 */
class FileDataSourceImplTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("setImageRoot with file path stores directory and clears uri") { `setImageRoot with file path stores directory and clears uri`() }

        test("setImageRoot with content uri stores uri and clears directory") { `setImageRoot with content uri stores uri and clears directory`() }
    }


    @MockK(relaxed = true)
    private lateinit var context: Context

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    private lateinit var dataSource: FileDataSourceImpl

    private fun setUp() {
        MockKAnnotations.init(this)
        // setRoot prunes orphaned persisted permissions, which reads every configured slot.
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceUri } returns flowOf(null)
        every { dataStore.syncInboxUri } returns flowOf(null)
        every { dataStore.s3LocalSyncDirectory } returns flowOf(null)
        val resolver = FileStorageBackendResolver(context, dataStore)
        dataSource =
            FileDataSourceImpl(
                workspaceConfigSource = FileWorkspaceConfigSourceDelegate(context, dataStore, resolver),
                markdownStorageDataSource = FileMarkdownStorageDataSourceDelegate(resolver),
                mediaStorageDataSource = FileMediaStorageDataSourceDelegate(context, resolver),
            )
    }

    private fun `setImageRoot with file path stores directory and clears uri`() =
        runTest {
            dataSource.setRoot(StorageRootType.IMAGE, "/storage/emulated/0/Pictures/Lomo")

            coVerify(exactly = 1) { dataStore.updateImageDirectory("/storage/emulated/0/Pictures/Lomo") }
            coVerify(exactly = 1) { dataStore.updateImageUri(null) }
        }

    private fun `setImageRoot with content uri stores uri and clears directory`() =
        runTest {
            val uri = "content://com.android.externalstorage.documents/tree/primary%3APictures"

            dataSource.setRoot(StorageRootType.IMAGE, uri)

            coVerify(exactly = 1) { dataStore.updateImageUri(uri) }
            coVerify(exactly = 1) { dataStore.updateImageDirectory(null) }
        }
}
