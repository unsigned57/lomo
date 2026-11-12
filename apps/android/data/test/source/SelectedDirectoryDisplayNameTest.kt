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
                        val context = mockk<Context>()
                        val treeRaw = "content://provider/tree/library"
                        val childRaw = "$treeRaw/document/library%2F$name"
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
                    } finally {
                        directory.deleteRecursively()
                    }
                }
            }
        }
    })
