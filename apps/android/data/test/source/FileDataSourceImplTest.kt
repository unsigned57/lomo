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
import com.lomo.data.repository.ProcessWorkspaceMutationLease
import com.lomo.data.source.StorageRootType
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: FileDataSourceImpl delegation.
 * - Behavior focus: ensuring that setting storage roots (Image, Root, etc.) correctly
 *   dispatches to the underlying DataStore for both raw file paths and content URIs.
 * - Observable outcomes: persisted image-root uri/directory state in a real preference store.
 * - TDD proof: Fails before the fix because FileDataSourceImpl was not yet updated to
 *   handle the dual path/URI storage model, so setting a URI would attempt to persist it as a file path.
 * - Excludes: actual file I/O, SAF permission granting, and cross-backend resolution logic.
 */
class FileDataSourceImplTest : DataFunSpec() {
    init {
        test("setImageRoot with file path stores directory and clears uri") {
            runTest {
                setUpDataSource()

                dataSource.setRoot(StorageRootType.IMAGE, "/storage/emulated/0/Pictures/Lomo")

                dataStore.imageUri.first() shouldBe null
                dataStore.imageDirectory.first() shouldBe "/storage/emulated/0/Pictures/Lomo"
            }
        }

        test("setImageRoot with content uri stores uri and clears directory") {
            runTest {
                setUpDataSource()
                val uri = "content://com.android.externalstorage.documents/tree/primary%3APictures"

                dataSource.setRoot(StorageRootType.IMAGE, uri)

                dataStore.imageUri.first() shouldBe uri
                dataStore.imageDirectory.first() shouldBe null
            }
        }
    }

    private val context = mockk<Context>(relaxed = true)

    private lateinit var dataStore: LomoDataStore
    private lateinit var dataSource: FileDataSourceImpl

    private fun TestScope.setUpDataSource() {
        dataStore = createLomoDataStore(backgroundScope)
        val resolver = FileStorageBackendResolver(context, dataStore)
        dataSource =
            FileDataSourceImpl(
                workspaceConfigSource = FileWorkspaceConfigSourceDelegate(context, dataStore, resolver),
                markdownStorageDataSource = FileMarkdownStorageDataSourceDelegate(resolver, ProcessWorkspaceMutationLease(FakeEngineReadinessRepository())),
                mediaStorageDataSource = FileMediaStorageDataSourceDelegate(resolver),
            )
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
