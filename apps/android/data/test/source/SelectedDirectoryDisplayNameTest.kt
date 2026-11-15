package com.lomo.data.source

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.local.datastore.LomoDataStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory

/*
 * Behavior Contract:
 * Capability: settings name the selected media directory rather than its broader grant;
 * owning layer: data; priority: P1.
 * Scenarios:
 * - Given a selected child image or voice document, when settings observes its name,
 *   then it displays that child's name; a tree-only selection retains the tree's name.
 * Observable outcomes: names emitted from real persisted media selections.
 * TDD proof: target/lomo/reports/manual/directory-interaction-bugs/lomo-directory-name-red.log
 * returns the grant parent's name; the final data suite verifies both URI forms.
 * Excludes: provider permission validation and settings pixel layout.
 * Test Change Justification:
 * Reason category: behavior-preserving organization of Android boundary fixtures.
 * Old behavior/assertion being replaced: inline platform stubbing; scenario names and outcomes stay unchanged.
 * Why old assertion is no longer correct: existing outcomes remain correct; platform setup belongs
 * in a scenario fixture, and successful validation/broadcast delivery needs an explicit outcome assertion.
 * Coverage preserved by: the same selected-directory, validation, or widget routing scenarios,
 * with all former success and rejection expectations retained.
 * Why this is not fitting the test to the implementation: fixtures expose provider results or
 * recorded broadcast payloads; production code and the behavior contract are unchanged.
 * Regression preservation: initial CI verification passed app 952/data 855 tests before this
 * fixture cleanup. After cleanup, data passed 855 tests and the widget dispatch target passed
 * all six scenarios; the final CI gate rechecks the complete workspace.
 * Evidence: reports/manual/directory-tidy-2026-11-14/test-fixtures.stdout.log and widget-fixture.stdout.log.
 */
class SelectedDirectoryDisplayNameTest :
    FunSpec({
        afterTest { unmockkStatic(Uri::class, DocumentFile::class, DocumentsContract::class) }
        for ((type, name) in listOf(StorageRootType.IMAGE to "images", StorageRootType.VOICE to "voice")) {
            test("given selected $name child when its display name is observed then its grant parent is not shown") {
                runTest {
                    val directory = createTempDirectory("lomo-directory-label").toFile()
                    try {
                        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
                            directory.resolve("settings.preferences_pb")
                        }
                        val constructor = LomoDataStore::class.java.getDeclaredConstructor(DataStore::class.java)
                        constructor.isAccessible = true
                        val store = constructor.newInstance(preferences)
                        with(DirectoryDisplayNameFixture(name)) {
                            val source = FileWorkspaceConfigSourceDelegate(
                                context,
                                store,
                                FileStorageBackendResolver(context, store),
                            )
                            if (type == StorageRootType.IMAGE) {
                                store.updateImageUri(
                                    childRaw,
                                )
                            } else {
                                store.updateVoiceUri(childRaw)
                            }
                            source.getRootDisplayNameFlow(type).first() shouldBe name
                            if (type == StorageRootType.IMAGE) {
                                store.updateImageUri(
                                    treeRaw,
                                )
                            } else {
                                store.updateVoiceUri(treeRaw)
                            }
                            source.getRootDisplayNameFlow(type).first() shouldBe "library"
                        }
                    } finally {
                        directory.deleteRecursively()
                    }
                }
            }
        }
    })

private class DirectoryDisplayNameFixture(name: String) {
    val context = mockk<Context>()
    val treeRaw = "content://provider/tree/library"
    val childRaw = "$treeRaw/document/library%2F$name"

    init {
        val treeUri = mockk<Uri>()
        val childUri = mockk<Uri>()
        val tree = mockk<DocumentFile>()
        val child = mockk<DocumentFile>()
        mockkStatic(Uri::class, DocumentFile::class, DocumentsContract::class)
        every { Uri.parse(treeRaw) } returns treeUri
        every { Uri.parse(childRaw) } returns childUri
        every { DocumentsContract.isDocumentUri(context, childUri) } returns true
        every { DocumentsContract.isDocumentUri(context, treeUri) } returns false
        every { DocumentFile.fromTreeUri(context, any()) } returns tree
        every { DocumentFile.fromSingleUri(context, childUri) } returns child
        every { tree.name } returns "library"
        every { child.name } returns name
    }
}
