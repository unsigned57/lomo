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


import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.testing.fakes.FakeFileDataSource
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.data.webdav.WebDavClient
import com.lomo.data.webdav.WebDavRemoteResource
import com.lomo.data.webdav.WebDavSmallRemoteFile
import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncReason
import com.lomo.domain.model.WebDavSyncState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: WebDavSyncActionApplier
 * - Behavior focus: action routing across upload/download/delete directions, memo-vs-media branching, skip behavior, and failure mapping.
 * - Observable outcomes: ActionExecutionState result, sync state transitions, and side-effect targets (WebDAV client vs markdown/media data sources).
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: planner conflict detection, metadata persistence, and network protocol correctness.
 */
class WebDavSyncActionApplierTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("upload memo reads local markdown and writes remote with local hint") { `upload memo reads local markdown and writes remote with local hint`() }

        test("upload media reads local media bytes and writes remote") { `upload media reads local media bytes and writes remote`() }

        test("upload memo skips when local memo content is missing") { `upload memo skips when local memo content is missing`() }

        test("download memo saves markdown content to memo directory") { `download memo saves markdown content to memo directory`() }

        test("download media writes bytes to local media store") { `download media writes bytes to local media store`() }

        test("delete local memo removes markdown file") { `delete local memo removes markdown file`() }

        test("delete local media removes local media file") { `delete local media removes local media file`() }

        test("delete remote removes remote file") { `delete remote removes remote file`() }

        test("none and conflict directions are skipped without side effects") { `none and conflict directions are skipped without side effects`() }

        test("exception during action is mapped to failed state") { `exception during action is mapped to failed state`() }
    }


    private val runtime: WebDavSyncRepositoryContext = mockk(relaxed = true)
    private val fileBridge: WebDavSyncFileBridge = mockk(relaxed = true)
    private val stateHolder = WebDavSyncStateHolder()
    private val markdownStorageDataSource = FakeFileDataSource()
    private val localMediaSyncStore: LocalMediaSyncStore = mockk(relaxed = true)
    private val client: WebDavClient = mockk(relaxed = true)

    private val layout =
        SyncDirectoryLayout(
            memoFolder = "memos",
            imageFolder = "images",
            voiceFolder = "voice",
            allSameDirectory = false,
        )

    private lateinit var applier: WebDavSyncActionApplier

    private fun setUp() {
        every { runtime.stateHolder } returns stateHolder
        every { runtime.markdownStorageDataSource } returns markdownStorageDataSource
        every { runtime.localMediaSyncStore } returns localMediaSyncStore
        applier = WebDavSyncActionApplier(runtime, fileBridge)
    }

    private fun `upload memo reads local markdown and writes remote with local hint`() =
        runTest {
            val path = "lomo/memos/note.md"
            val action = action(path, WebDavSyncDirection.UPLOAD)
            val localFiles = mapOf(path to LocalWebDavFile(path = path, lastModified = 123L))
            val remoteFiles = mapOf(path to RemoteWebDavFile(path = path, etag = "etag-1", lastModified = 120L))
            every { fileBridge.isMemoPath(path, layout) } returns true
            every { fileBridge.extractMemoFilename(path, layout) } returns "note.md"
            every { fileBridge.contentTypeForPath(path, layout) } returns WEBDAV_MARKDOWN_CONTENT_TYPE
            markdownStorageDataSource.saveFileIn(MemoDirectoryType.MAIN, "note.md", "memo body", false)

            val result = applier.applyAction(action, client, layout, localFiles, remoteFiles)

            result shouldBe ActionExecutionState.Applied(localChanged = false, remoteChanged = true)
            stateHolder.state.value shouldBe WebDavSyncState.Uploading
            verify(exactly = 1) {
                client.putSmallFile(
                    path = path,
                    bytes = "memo body".toByteArray(Charsets.UTF_8),
                    contentType = WEBDAV_MARKDOWN_CONTENT_TYPE,
                    lastModifiedHint = 123L,
                    expectedEtag = "etag-1",
                    requireAbsent = false,
                )
            }
            coVerify(exactly = 0) { localMediaSyncStore.readBytes(any(), any()) }
        }

    private fun `upload media reads local media bytes and writes remote`() =
        runTest {
            val path = "lomo/images/pic.png"
            val action = action(path, WebDavSyncDirection.UPLOAD)
            every { fileBridge.isMemoPath(path, layout) } returns false
            every { localMediaSyncStore.contentTypeForPath(path, layout) } returns "image/png"
            coEvery { localMediaSyncStore.exportToFile(path, layout, any()) } returns Unit

            val result = applier.applyAction(action, client, layout, emptyMap(), emptyMap())

            result shouldBe ActionExecutionState.Applied(localChanged = false, remoteChanged = true)
            stateHolder.state.value shouldBe WebDavSyncState.Uploading
            verify(exactly = 1) {
                client.putFile(
                    path = path,
                    file = any(),
                    contentType = "image/png",
                    lastModifiedHint = null,
                    expectedEtag = null,
                    requireAbsent = true,
                )
            }
            coVerify(exactly = 1) { localMediaSyncStore.exportToFile(path, layout, any()) }
            markdownStorageDataSource.files.isEmpty() shouldBe true
        }

    private fun `upload memo skips when local memo content is missing`() =
        runTest {
            val path = "lomo/memos/missing.md"
            val action = action(path, WebDavSyncDirection.UPLOAD)
            every { fileBridge.isMemoPath(path, layout) } returns true
            every { fileBridge.extractMemoFilename(path, layout) } returns "missing.md"

            val result = applier.applyAction(action, client, layout, emptyMap(), emptyMap())

            result shouldBe ActionExecutionState.Skipped
            verify(exactly = 0) { client.putSmallFile(any(), any(), any(), any(), any(), any()) }
        }

    private fun `download memo saves markdown content to memo directory`() =
        runTest {
            val path = "lomo/memos/download.md"
            val action = action(path, WebDavSyncDirection.DOWNLOAD)
            every { fileBridge.isMemoPath(path, layout) } returns true
            every { fileBridge.extractMemoFilename(path, layout) } returns "download.md"
            every { client.getSmallFile(path) } returns WebDavSmallRemoteFile(path, "remote memo".toByteArray(), "etag", 200L)

            val result = applier.applyAction(action, client, layout, emptyMap(), emptyMap())

            result shouldBe ActionExecutionState.Applied(localChanged = true, remoteChanged = false)
            stateHolder.state.value shouldBe WebDavSyncState.Downloading
            markdownStorageDataSource.files[Pair(MemoDirectoryType.MAIN, "download.md")] shouldBe "remote memo"
            coVerify(exactly = 0) { localMediaSyncStore.writeBytes(any(), any(), any()) }
        }

    private fun `download media writes bytes to local media store`() =
        runTest {
            val path = "lomo/images/download.png"
            val action = action(path, WebDavSyncDirection.DOWNLOAD)
            every { fileBridge.isMemoPath(path, layout) } returns false
            every { client.getToFile(path, any()) } returns
                WebDavRemoteResource(
                    path = path,
                    isDirectory = false,
                    etag = "etag-download",
                    lastModified = 200L,
                )

            val result = applier.applyAction(action, client, layout, emptyMap(), emptyMap())

            result shouldBe ActionExecutionState.Applied(localChanged = true, remoteChanged = false)
            stateHolder.state.value shouldBe WebDavSyncState.Downloading
            verify(exactly = 1) { client.getToFile(path, any()) }
            coVerify(exactly = 1) { localMediaSyncStore.importFromFile(path, any(), layout) }
            markdownStorageDataSource.files.isEmpty() shouldBe true
        }

    private fun `delete local memo removes markdown file`() =
        runTest {
            val path = "lomo/memos/deleted.md"
            val action = action(path, WebDavSyncDirection.DELETE_LOCAL)
            every { fileBridge.isMemoPath(path, layout) } returns true
            every { fileBridge.extractMemoFilename(path, layout) } returns "deleted.md"
            markdownStorageDataSource.saveFileIn(MemoDirectoryType.MAIN, "deleted.md", "hello", false)

            val result = applier.applyAction(action, client, layout, emptyMap(), emptyMap())

            result shouldBe ActionExecutionState.Applied(localChanged = true, remoteChanged = false)
            stateHolder.state.value shouldBe WebDavSyncState.Deleting
            markdownStorageDataSource.files.containsKey(Pair(MemoDirectoryType.MAIN, "deleted.md")) shouldBe false
            coVerify(exactly = 0) { localMediaSyncStore.delete(any(), any()) }
        }

    private fun `delete local media removes local media file`() =
        runTest {
            val path = "lomo/voice/clip.m4a"
            val action = action(path, WebDavSyncDirection.DELETE_LOCAL)
            every { fileBridge.isMemoPath(path, layout) } returns false

            val result = applier.applyAction(action, client, layout, emptyMap(), emptyMap())

            result shouldBe ActionExecutionState.Applied(localChanged = true, remoteChanged = false)
            stateHolder.state.value shouldBe WebDavSyncState.Deleting
            coVerify(exactly = 1) { localMediaSyncStore.delete(path, layout) }
            markdownStorageDataSource.files.isEmpty() shouldBe true
        }

    private fun `delete remote removes remote file`() =
        runTest {
            val path = "lomo/memos/remove-remote.md"
            val action = action(path, WebDavSyncDirection.DELETE_REMOTE)
            val remoteFiles = mapOf(path to RemoteWebDavFile(path = path, etag = "etag-2", lastModified = 300L))

            val result = applier.applyAction(action, client, layout, emptyMap(), remoteFiles)

            result shouldBe ActionExecutionState.Applied(localChanged = false, remoteChanged = true)
            stateHolder.state.value shouldBe WebDavSyncState.Deleting
            verify(exactly = 1) { client.delete(path, "etag-2") }
        }

    private fun `none and conflict directions are skipped without side effects`() =
        runTest {
            val noneAction = action("lomo/memos/none.md", WebDavSyncDirection.NONE)
            val conflictAction = action("lomo/memos/conflict.md", WebDavSyncDirection.CONFLICT)

            val noneResult = applier.applyAction(noneAction, client, layout, emptyMap(), emptyMap())
            val conflictResult = applier.applyAction(conflictAction, client, layout, emptyMap(), emptyMap())

            noneResult shouldBe ActionExecutionState.Skipped
            conflictResult shouldBe ActionExecutionState.Skipped
            verify(exactly = 0) { client.getSmallFile(any()) }
            verify(exactly = 0) { client.putSmallFile(any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { client.delete(any(), any()) }
            markdownStorageDataSource.files.isEmpty() shouldBe true
            coVerify(exactly = 0) { localMediaSyncStore.writeBytes(any(), any(), any()) }
        }

    private fun `exception during action is mapped to failed state`() =
        runTest {
            val path = "lomo/memos/boom.md"
            val action = action(path, WebDavSyncDirection.DELETE_REMOTE)
            every { client.delete(path, any()) } throws IllegalStateException("boom")

            val result = applier.applyAction(action, client, layout, emptyMap(), emptyMap())

            (result is ActionExecutionState.Failed).shouldBeTrue()
            (result as ActionExecutionState.Failed).path shouldBe path
        }

    private fun action(
        path: String,
        direction: WebDavSyncDirection,
        reason: WebDavSyncReason = WebDavSyncReason.LOCAL_ONLY,
    ): WebDavSyncAction =
        WebDavSyncAction(
            path = path,
            direction = direction,
            reason = reason,
        )
}
