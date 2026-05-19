package com.lomo.data.repository

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



import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.webdav.LocalMediaSyncFile
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.data.webdav.WebDavClient
import com.lomo.data.webdav.WebDavRemoteResource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: WebDavSyncFileBridge
 * - Behavior focus: stable local fingerprints and remote listings should reuse short-lived caches instead of re-reading unchanged local files or re-listing the same folders immediately; distinct remote folders should be PROPFIND-ed concurrently so end-to-end latency approaches max(per-folder) instead of sum.
 * - Observable outcomes: repeated localFiles()/remoteFiles() calls return the same snapshot while markdown/media reads and WebDAV list calls stay at one per unchanged path/folder, and remoteFiles() wall-clock time across N distinct folders is well under N * single-folder latency.
 * - TDD proof: Fails before the fix because (1) each repeated call recomputes md5 from storage and reissues the same per-folder PROPFIND requests, and (2) distinctFolders are listed sequentially, so total elapsed time grows linearly with folder count.
 * - Excludes: planner decisions, metadata persistence, transport protocol correctness, and cache expiry timing beyond same-turn reuse.
 */
class WebDavSyncFileBridgeTest : DataFunSpec() {
    init {
        test("localFiles reuses cached fingerprints for unchanged memo and media metadata") { `localFiles reuses cached fingerprints for unchanged memo and media metadata`() }

        test("localFiles computes media fingerprint without loading whole media bytes") { `localFiles computes media fingerprint without loading whole media bytes`() }

        test("remoteFiles reuses short lived folder listings") { `remoteFiles reuses short lived folder listings`() }

        test("remoteFiles lists distinct folders concurrently") { `remoteFiles lists distinct folders concurrently`() }
    }


    private fun `localFiles reuses cached fingerprints for unchanged memo and media metadata`() =
        runTest {
            val markdownStorageDataSource = com.lomo.data.testing.fakes.FakeFileDataSource()
            val localMediaSyncStore: LocalMediaSyncStore = mockk(relaxed = true)
            val runtime = mockRuntime(markdownStorageDataSource, localMediaSyncStore)
            val bridge = WebDavSyncFileBridge(runtime)
            val layout = standardLayout()
            markdownStorageDataSource.listMetadataInResult = {
                listOf(FileMetadata(filename = "note.md", lastModified = 10L, size = 7L))
            }
            markdownStorageDataSource.readFileInResult = { _, _ -> "# note" }
            coEvery { localMediaSyncStore.listFiles(layout) } returns
                mapOf("images/pic.png" to LocalMediaSyncFile(relativePath = "images/pic.png", lastModified = 20L, size = 3L))
            coEvery { localMediaSyncStore.md5Hex("lomo/images/pic.png", layout) } returns "010203"

            val first = bridge.localFiles(layout)
            val second = bridge.localFiles(layout)

            second shouldBe first
            markdownStorageDataSource.readFileInCalls.size shouldBe 1
            coVerify(exactly = 1) { localMediaSyncStore.md5Hex("lomo/images/pic.png", layout) }
            coVerify(exactly = 0) { localMediaSyncStore.readBytes("lomo/images/pic.png", layout) }
        }

    private fun `localFiles computes media fingerprint without loading whole media bytes`() =
        runTest {
            val markdownStorageDataSource = com.lomo.data.testing.fakes.FakeFileDataSource()
            val localMediaSyncStore: LocalMediaSyncStore = mockk(relaxed = true)
            val runtime = mockRuntime(markdownStorageDataSource, localMediaSyncStore)
            val bridge = WebDavSyncFileBridge(runtime)
            val layout = standardLayout()
            markdownStorageDataSource.listMetadataInResult = { emptyList() }
            coEvery { localMediaSyncStore.listFiles(layout) } returns
                mapOf("images/pic.png" to LocalMediaSyncFile(relativePath = "images/pic.png", lastModified = 20L, size = 3L))
            coEvery { localMediaSyncStore.md5Hex("lomo/images/pic.png", layout) } returns "010203"

            val files = bridge.localFiles(layout)

            files.getValue("lomo/images/pic.png").localFingerprint shouldBe "010203"
            coVerify(exactly = 1) { localMediaSyncStore.md5Hex("lomo/images/pic.png", layout) }
            coVerify(exactly = 0) { localMediaSyncStore.readBytes("lomo/images/pic.png", layout) }
        }

    private fun `remoteFiles reuses short lived folder listings`() =
        runBlocking {
            val client =
                object : WebDavClient {
                    val calls = ConcurrentHashMap<String, Int>()

                    override fun ensureDirectory(path: String) = Unit

                    override fun list(path: String): List<WebDavRemoteResource> {
                        calls.merge(path, 1, Int::plus)
                        return listOf(
                            WebDavRemoteResource(
                                path = "$path/note.md".trimStart('/'),
                                isDirectory = false,
                                etag = "etag-$path",
                                lastModified = 10L,
                                size = 7L,
                            ),
                        )
                    }

                    override fun get(path: String) = error("not used")

                    override fun put(
                        path: String,
                        bytes: ByteArray,
                        contentType: String,
                        lastModifiedHint: Long?,
                        expectedEtag: String?,
                        requireAbsent: Boolean,
                    ) = Unit

                    override fun delete(
                        path: String,
                        expectedEtag: String?,
                    ) = Unit

                    override fun move(
                        sourcePath: String,
                        targetPath: String,
                        overwrite: Boolean,
                    ) = Unit

                    override fun copy(
                        sourcePath: String,
                        targetPath: String,
                        overwrite: Boolean,
                    ) = Unit

                    override fun testConnection() = Unit
                }
            val bridge = WebDavSyncFileBridge(mockRuntime())
            val layout = standardLayout()

            val first = bridge.remoteFiles(client, layout)
            val second = bridge.remoteFiles(client, layout)

            second shouldBe first
            client.calls shouldBe mapOf("lomo/memos" to 1, "lomo/images" to 1, "lomo/voice" to 1)
        }

    private fun `remoteFiles lists distinct folders concurrently`() =
        runBlocking {
            val callCount = AtomicInteger()
            val client =
                object : WebDavClient {
                    override fun ensureDirectory(path: String) = Unit

                    override fun list(path: String): List<WebDavRemoteResource> {
                        callCount.incrementAndGet()
                        Thread.sleep(PARALLEL_LIST_DELAY_MS)
                        return emptyList()
                    }

                    override fun get(path: String) = error("not used")

                    override fun put(
                        path: String,
                        bytes: ByteArray,
                        contentType: String,
                        lastModifiedHint: Long?,
                        expectedEtag: String?,
                        requireAbsent: Boolean,
                    ) = Unit

                    override fun delete(
                        path: String,
                        expectedEtag: String?,
                    ) = Unit

                    override fun move(
                        sourcePath: String,
                        targetPath: String,
                        overwrite: Boolean,
                    ) = Unit

                    override fun copy(
                        sourcePath: String,
                        targetPath: String,
                        overwrite: Boolean,
                    ) = Unit

                    override fun testConnection() = Unit
                }
            val bridge = WebDavSyncFileBridge(mockRuntime())
            val layout = standardLayout()

            val startedAt = System.nanoTime()
            bridge.remoteFiles(client, layout)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            callCount.get() shouldBe 3
            withClue("expected concurrent listings (elapsed=${elapsedMs}ms, threshold=${PARALLEL_LIST_ELAPSED_LIMIT_MS}ms)") { (elapsedMs < PARALLEL_LIST_ELAPSED_LIMIT_MS).shouldBeTrue() }
        }

    private fun mockRuntime(
        markdownStorageDataSource: MarkdownStorageDataSource = mockk(relaxed = true),
        localMediaSyncStore: LocalMediaSyncStore = mockk(relaxed = true),
    ): WebDavSyncRepositoryContext {
        val runtime: WebDavSyncRepositoryContext = mockk(relaxed = true)
        every { runtime.markdownStorageDataSource } returns markdownStorageDataSource
        every { runtime.localMediaSyncStore } returns localMediaSyncStore
        every { runtime.performanceTuner } returns DisabledSyncPerformanceTuner
        return runtime
    }

    private fun standardLayout() =
        SyncDirectoryLayout(
            memoFolder = "memos",
            imageFolder = "images",
            voiceFolder = "voice",
            allSameDirectory = false,
        )

    private companion object {
        const val PARALLEL_LIST_DELAY_MS = 120L
        const val PARALLEL_LIST_ELAPSED_LIMIT_MS = 300L
    }
}
