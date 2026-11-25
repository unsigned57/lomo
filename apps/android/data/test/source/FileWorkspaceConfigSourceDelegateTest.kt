package com.lomo.data.source

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

/*
 * Behavior Contract:
 * Scenarios:
 * - Given pending note or attachment candidates, when grants are pruned, then live candidate grants survive
 *   and orphan grants are released.
 * - Given a path or URI root update, when configuration is observed, then the selected location and display
 *   name persist.
 * - Given directory creation failure, when configuration creates a directory, then the filesystem failure is
 *   surfaced.
 * TDD proof (candidate grant pinning): target/lomo/reports/manual/directory-selection/transaction-red.log
 * exposes unjournaled media changes; verification-green.log covers both notes and attachment candidate grants.
 * Test Change Justification:
 * Reason category: candidate grant lifecycle within the unified storage journal.
 * Old behavior/assertion being replaced: root-only preparation supplied no explicit attachment binding.
 * Why old assertion is no longer correct: attachment preparation now shares this protocol and must pin its candidate grant.
 * Coverage preserved by: all existing grant sets and stored-location assertions are retained; pending media grant coverage is added.
 * Why this is not fitting the test to the implementation: Android may revoke a released grant, so uncommitted candidates remain live resources.
 * - Unit under test: FileWorkspaceConfigSourceDelegate
 * - Capability: root path or URI persistence, root-flow precedence, display-name mapping, and directory creation failure policy.
 * - Observable outcomes: persisted root values in a real preference store, observed root values,
 *   created filesystem directories, and thrown IOException messages.
 * - Given a pending note-root transition, when an independent image folder is configured,
 *   then both notes grants survive while genuinely orphaned grants are released.
 * - TDD proof (pending grant): RED this suite removed the candidate notes grant;
 *   GREEN after pending root transitions participate in the active-grant set.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: SAF `DocumentFile` lookups (mocked Android framework seams), and DataStore persistence internals.
 */
class FileWorkspaceConfigSourceDelegateTest : DataFunSpec() {
    init {
        beforeTest { grantHarness.permissions = emptyList() }
        test(
            "given a prepared image rebind when orphan grants are pruned then both attachment grants survive until commit",
        ) {
            runTest {
                val notes = uriReturning("content://provider/tree/notes")
                val previous = uriReturning("content://provider/tree/previous-images")
                val candidate = uriReturning("content://provider/tree/new-images")
                val inbox = uriReturning("content://provider/tree/inbox")
                val orphan = uriReturning("content://provider/tree/orphan")
                val harness = PersistedGrantHarness()
                harness.permissions = listOf(notes, previous, candidate, inbox, orphan).map(::permissionFor)
                val store = createLomoDataStore(backgroundScope)
                store.updateRootUri(notes.toString())
                store.updateImageUri(previous.toString())
                val location = com.lomo.domain.model.StorageLocation(notes.toString())
                store.prepareRootTransition(
                    location,
                    location,
                    com.lomo.domain.model.MediaDirectoryChange(
                        com.lomo.domain.model.StorageArea.IMAGE,
                        com.lomo.domain.model.StorageLocation(previous.toString()),
                        com.lomo.domain.model.StorageLocation(candidate.toString()),
                    ),
                )
                FileWorkspaceConfigSourceDelegate(
                    harness.context,
                    store,
                    FileStorageBackendResolver(harness.context, store),
                ).setRoot(StorageRootType.SYNC_INBOX, inbox.toString())

                harness.permissions.map { it.uri.toString() }.toSet() shouldBe
                    setOf(notes.toString(), previous.toString(), candidate.toString(), inbox.toString())
                store.imageUri.first() shouldBe previous.toString()
            }
        }
        test(
            "given a pending notes import when sibling media folders are selected then its read write grant survives",
        ) {
            runTest {
                val previous = uriReturning("content://provider/tree/previous-notes")
                val candidate = uriReturning("content://provider/tree/notes")
                val images = uriReturning("content://provider/tree/pictures")
                val orphan = uriReturning("content://provider/tree/orphan")
                val grantHarness = PersistedGrantHarness()
                val resolver = grantHarness.resolver
                val host = grantHarness.context
                grantHarness.permissions = listOf(previous, candidate, images, orphan).map(::permissionFor)
                val dataStore = createLomoDataStore(backgroundScope)
                dataStore.updateRootUri(previous.toString())
                val transition = dataStore.prepareRootTransition(
                    com.lomo.domain.model.StorageLocation(previous.toString()),
                    com.lomo.domain.model.StorageLocation(candidate.toString()),
                    mediaChange = null,
                )
                val delegate = FileWorkspaceConfigSourceDelegate(
                    host,
                    dataStore,
                    FileStorageBackendResolver(host, dataStore),
                )
                delegate.setRoot(StorageRootType.IMAGE, images.toString())
                grantHarness.permissions.map { it.uri.toString() }.toSet() shouldBe setOf(
                    previous.toString(),
                    candidate.toString(),
                    images.toString(),
                )
                dataStore.markRootTransitionActivated(transition.id)
                dataStore.commitRootTransition(transition.id)
                dataStore.rootUri.first() shouldBe candidate.toString()
            }
        }

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

        test(
            "setRoot keeps bare persisted grant backing a cross-user 0@ workspace root while releasing orphan grants",
        ) {
            runTest {
                val bareActiveUri = uriReturning("content://provider/tree/notes")
                val orphanUri = uriReturning("content://provider/tree/orphan")
                stubPersistedUriPermissions(listOf(permissionFor(bareActiveUri), permissionFor(orphanUri)))
                val (delegate, dataStore) = setUpDelegate()

                delegate.setRoot(StorageRootType.MAIN, "content://0@provider/tree/notes")

                verify(exactly = 1) { contentResolver.releasePersistableUriPermission(orphanUri, any()) }
                verify(exactly = 0) { contentResolver.releasePersistableUriPermission(bareActiveUri, any()) }
                dataStore.rootUri.first() shouldBe "content://0@provider/tree/notes"
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

    private val grantHarness = PersistedGrantHarness()
    private val context get() = grantHarness.context
    private val contentResolver get() = grantHarness.resolver

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
        grantHarness.permissions = permissions
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
        val constructor = LomoDataStore::class.java.getDeclaredConstructor(
            androidx.datastore.core.DataStore::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(realDataStore)
    }
}

private class PersistedGrantHarness {
    val context = mockk<Context>()
    val resolver = mockk<ContentResolver>()
    var permissions = emptyList<UriPermission>()

    init {
        every { context.contentResolver } returns resolver
        every { resolver.persistedUriPermissions } answers { permissions }
        every { resolver.releasePersistableUriPermission(any(), any()) } answers {
            val released = firstArg<Uri>()
            permissions = permissions.filterNot { it.uri == released }
        }
    }
}
