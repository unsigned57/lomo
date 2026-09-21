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
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.DataFunSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/*
 * Behavior Contract:
 * - Unit under test: FileStorageBackendResolver
 * - Behavior focus: root-backend selection, cache invalidation on root changes, and typed media-root precedence.
 * - Observable outcomes: resolved backend types, returned configured SAF uri markers, nullability for
 *   missing roots, and cache reuse vs refresh, against a real preference store.
 * - TDD proof: Fails before the fix because the resolver does not expose an explicit WorkspaceVfs
 *   shape for resolved roots, leaving the media bridge to branch on concrete backend types.
 * - Excludes: SAF document traversal and backend file I/O.
 */
class FileStorageBackendResolverTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tearDown()
        }

        test("markdown and workspace backends are null when no root is configured") {
            runTest {
                setUpResolver()

                resolver.markdownBackend().shouldBeNull()
                resolver.workspaceBackend().shouldBeNull()
            }
        }

        test("markdown backend uses direct backend cache until root configuration changes") {
            runTest {
                setUpResolver()
                dataStore.updateRootDirectory("/vault/root-a")

                val first = resolver.markdownBackend().shouldBeInstanceOf<VfsStorageBackend>()
                val second = resolver.markdownBackend().shouldBeInstanceOf<VfsStorageBackend>()

                dataStore.updateRootDirectory("/vault/root-b")
                val refreshed = resolver.markdownBackend().shouldBeInstanceOf<VfsStorageBackend>()

                (second === first) shouldBe true
                (refreshed !== first) shouldBe true
            }
        }

        test("workspace backend prefers saf root when root uri is configured") {
            runTest {
                setUpResolver()
                dataStore.updateRootUri("content://tree/root")

                val backend = resolver.workspaceBackend().shouldBeInstanceOf<VfsStorageBackend>()
                val markdownBackend = resolver.markdownBackend()

                (markdownBackend === backend) shouldBe true
            }
        }

        test("root vfs prefers saf root when root uri is configured") {
            runTest {
                setUpResolver()
                dataStore.updateRootUri("content://tree/root")

                val vfs = resolver.rootVfs().shouldBeInstanceOf<WorkspaceVfs.Saf>()

                (vfs.rootUri === parsedUri("content://tree/root")) shouldBe true
            }
        }

        test("media backend returns direct backend and null marker for typed directory root") {
            runTest {
                setUpResolver()
                dataStore.updateImageDirectory("/typed/images")

                val imageRoot = resolver.resolvedMediaRoot(StorageRootType.IMAGE).shouldNotBeNull()

                imageRoot.backend.shouldBeInstanceOf<VfsStorageBackend>()
                imageRoot.configuredUriMarker.shouldBeNull()
            }
        }

        test("resolved media root exposes direct workspace vfs for typed directory root") {
            runTest {
                setUpResolver()
                dataStore.updateImageDirectory("/typed/images")

                val resolvedRoot = resolver.resolvedMediaRoot(StorageRootType.IMAGE).shouldNotBeNull()

                resolvedRoot.backend.shouldBeInstanceOf<VfsStorageBackend>()
                resolvedRoot.vfs.shouldBeInstanceOf<WorkspaceVfs.Direct>().rootDir.path shouldBe "/typed/images"
                resolvedRoot.configuredUriMarker.shouldBeNull()
            }
        }

        test("media backend uses typed uri when the unified location is a content uri") {
            runTest {
                setUpResolver()
                dataStore.updateImageUri("content://tree/images")

                val imageRoot = resolver.resolvedMediaRoot(StorageRootType.IMAGE).shouldNotBeNull()

                imageRoot.backend.shouldBeInstanceOf<VfsStorageBackend>()
                imageRoot.configuredUriMarker shouldBe "content://tree/images"
            }
        }
    }

    private val context = mockk<Context>(relaxed = true)
    private val uriCache = linkedMapOf<String, Uri>()

    private lateinit var dataStore: LomoDataStore
    private lateinit var resolver: FileStorageBackendResolver

    private fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { parsedUri(firstArg()) }
    }

    private fun tearDown() {
        unmockkStatic(Uri::class)
        uriCache.clear()
    }

    private fun TestScope.setUpResolver() {
        dataStore = createLomoDataStore(backgroundScope)
        resolver = FileStorageBackendResolver(context, dataStore)
    }

    private fun parsedUri(value: String): Uri =
        uriCache.getOrPut(value) {
            mockk<Uri>(relaxed = true).apply {
                every { scheme } returns value.substringBefore(':', "")
                every { path } returns value.substringAfter("://", value)
            }
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
