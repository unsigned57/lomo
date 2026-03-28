package com.lomo.data.repository

import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.data.webdav.WebDavClient
import com.lomo.data.webdav.WebDavRemoteFile
import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncReason
import com.lomo.domain.model.WebDavSyncState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: WebDavSyncActionApplier
 * - Behavior focus: action routing across upload/download/delete directions, memo-vs-media branching, skip behavior, and failure mapping.
 * - Observable outcomes: ActionExecutionState result, sync state transitions, and side-effect targets (WebDAV client vs markdown/media data sources).
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: planner conflict detection, metadata persistence, and network protocol correctness.
 */
class WebDavSyncActionApplierTest {
    private val runtime: WebDavSyncRepositoryContext = mockk(relaxed = true)
    private val fileBridge: WebDavSyncFileBridge = mockk(relaxed = true)
    private val stateHolder = WebDavSyncStateHolder()
    private val markdownStorageDataSource: MarkdownStorageDataSource = mockk(relaxed = true)
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

    @Before
    fun setUp() {
        every { runtime.stateHolder } returns stateHolder
        every { runtime.markdownStorageDataSource } returns markdownStorageDataSource
        every { runtime.localMediaSyncStore } returns localMediaSyncStore
        applier = WebDavSyncActionApplier(runtime, fileBridge)
    }

    @Test
    fun `upload memo reads local markdown and writes remote with local hint`() =
        runTest {
            val path = "lomo/memos/note.md"
            val action = action(path, WebDavSyncDirection.UPLOAD)
            val localFiles = mapOf(path to LocalWebDavFile(path = path, lastModified = 123L))
            every { fileBridge.isMemoPath(path, layout) } returns true
            every { fileBridge.extractMemoFilename(path, layout) } returns "note.md"
            every { fileBridge.contentTypeForPath(path, layout) } returns WEBDAV_MARKDOWN_CONTENT_TYPE
            coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md") } returns "memo body"

            val result = applier.applyAction(action, client, layout, localFiles)

            assertEquals(ActionExecutionState.Applied(localChanged = false, remoteChanged = true), result)
            assertEquals(WebDavSyncState.Uploading, stateHolder.state.value)
            verify(exactly = 1) {
                client.put(
                    path = path,
                    bytes = "memo body".toByteArray(Charsets.UTF_8),
                    contentType = WEBDAV_MARKDOWN_CONTENT_TYPE,
                    lastModifiedHint = 123L,
                )
            }
            coVerify(exactly = 0) { localMediaSyncStore.readBytes(any(), any()) }
        }

    @Test
    fun `upload media reads local media bytes and writes remote`() =
        runTest {
            val path = "lomo/images/pic.png"
            val action = action(path, WebDavSyncDirection.UPLOAD)
            val bytes = byteArrayOf(1, 2, 3)
            every { fileBridge.isMemoPath(path, layout) } returns false
            every { fileBridge.contentTypeForPath(path, layout) } returns "image/png"
            coEvery { localMediaSyncStore.readBytes(path, layout) } returns bytes

            val result = applier.applyAction(action, client, layout, emptyMap())

            assertEquals(ActionExecutionState.Applied(localChanged = false, remoteChanged = true), result)
            assertEquals(WebDavSyncState.Uploading, stateHolder.state.value)
            verify(exactly = 1) {
                client.put(
                    path = path,
                    bytes = bytes,
                    contentType = "image/png",
                    lastModifiedHint = null,
                )
            }
            coVerify(exactly = 0) { markdownStorageDataSource.readFileIn(any(), any()) }
        }

    @Test
    fun `upload memo skips when local memo content is missing`() =
        runTest {
            val path = "lomo/memos/missing.md"
            val action = action(path, WebDavSyncDirection.UPLOAD)
            every { fileBridge.isMemoPath(path, layout) } returns true
            every { fileBridge.extractMemoFilename(path, layout) } returns "missing.md"
            coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "missing.md") } returns null

            val result = applier.applyAction(action, client, layout, emptyMap())

            assertEquals(ActionExecutionState.Skipped, result)
            verify(exactly = 0) { client.put(any(), any(), any(), any()) }
        }

    @Test
    fun `download memo saves markdown content to memo directory`() =
        runTest {
            val path = "lomo/memos/download.md"
            val action = action(path, WebDavSyncDirection.DOWNLOAD)
            every { fileBridge.isMemoPath(path, layout) } returns true
            every { fileBridge.extractMemoFilename(path, layout) } returns "download.md"
            every { client.get(path) } returns WebDavRemoteFile(path, "remote memo".toByteArray(), "etag", 200L)

            val result = applier.applyAction(action, client, layout, emptyMap())

            assertEquals(ActionExecutionState.Applied(localChanged = true, remoteChanged = false), result)
            assertEquals(WebDavSyncState.Downloading, stateHolder.state.value)
            coVerify(exactly = 1) {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "download.md",
                    content = "remote memo",
                )
            }
            coVerify(exactly = 0) { localMediaSyncStore.writeBytes(any(), any(), any()) }
        }

    @Test
    fun `download media writes bytes to local media store`() =
        runTest {
            val path = "lomo/images/download.png"
            val bytes = byteArrayOf(9, 8, 7)
            val action = action(path, WebDavSyncDirection.DOWNLOAD)
            every { fileBridge.isMemoPath(path, layout) } returns false
            every { client.get(path) } returns WebDavRemoteFile(path, bytes, "etag", 300L)

            val result = applier.applyAction(action, client, layout, emptyMap())

            assertEquals(ActionExecutionState.Applied(localChanged = true, remoteChanged = false), result)
            assertEquals(WebDavSyncState.Downloading, stateHolder.state.value)
            coVerify(exactly = 1) { localMediaSyncStore.writeBytes(path, bytes, layout) }
            coVerify(exactly = 0) { markdownStorageDataSource.saveFileIn(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `delete local memo removes markdown file`() =
        runTest {
            val path = "lomo/memos/deleted.md"
            val action = action(path, WebDavSyncDirection.DELETE_LOCAL)
            every { fileBridge.isMemoPath(path, layout) } returns true
            every { fileBridge.extractMemoFilename(path, layout) } returns "deleted.md"

            val result = applier.applyAction(action, client, layout, emptyMap())

            assertEquals(ActionExecutionState.Applied(localChanged = true, remoteChanged = false), result)
            assertEquals(WebDavSyncState.Deleting, stateHolder.state.value)
            coVerify(exactly = 1) {
                markdownStorageDataSource.deleteFileIn(MemoDirectoryType.MAIN, "deleted.md")
            }
            coVerify(exactly = 0) { localMediaSyncStore.delete(any(), any()) }
        }

    @Test
    fun `delete local media removes local media file`() =
        runTest {
            val path = "lomo/voice/clip.m4a"
            val action = action(path, WebDavSyncDirection.DELETE_LOCAL)
            every { fileBridge.isMemoPath(path, layout) } returns false

            val result = applier.applyAction(action, client, layout, emptyMap())

            assertEquals(ActionExecutionState.Applied(localChanged = true, remoteChanged = false), result)
            assertEquals(WebDavSyncState.Deleting, stateHolder.state.value)
            coVerify(exactly = 1) { localMediaSyncStore.delete(path, layout) }
            coVerify(exactly = 0) { markdownStorageDataSource.deleteFileIn(any(), any(), any()) }
        }

    @Test
    fun `delete remote removes remote file`() =
        runTest {
            val path = "lomo/memos/remove-remote.md"
            val action = action(path, WebDavSyncDirection.DELETE_REMOTE)

            val result = applier.applyAction(action, client, layout, emptyMap())

            assertEquals(ActionExecutionState.Applied(localChanged = false, remoteChanged = true), result)
            assertEquals(WebDavSyncState.Deleting, stateHolder.state.value)
            verify(exactly = 1) { client.delete(path) }
        }

    @Test
    fun `none and conflict directions are skipped without side effects`() =
        runTest {
            val noneAction = action("lomo/memos/none.md", WebDavSyncDirection.NONE)
            val conflictAction = action("lomo/memos/conflict.md", WebDavSyncDirection.CONFLICT)

            val noneResult = applier.applyAction(noneAction, client, layout, emptyMap())
            val conflictResult = applier.applyAction(conflictAction, client, layout, emptyMap())

            assertEquals(ActionExecutionState.Skipped, noneResult)
            assertEquals(ActionExecutionState.Skipped, conflictResult)
            verify(exactly = 0) { client.get(any()) }
            verify(exactly = 0) { client.put(any(), any(), any(), any()) }
            verify(exactly = 0) { client.delete(any()) }
            coVerify(exactly = 0) { markdownStorageDataSource.saveFileIn(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { localMediaSyncStore.writeBytes(any(), any(), any()) }
        }

    @Test
    fun `exception during action is mapped to failed state`() =
        runTest {
            val path = "lomo/memos/boom.md"
            val action = action(path, WebDavSyncDirection.DELETE_REMOTE)
            every { client.delete(path) } throws IllegalStateException("boom")

            val result = applier.applyAction(action, client, layout, emptyMap())

            assertTrue(result is ActionExecutionState.Failed)
            assertEquals(path, (result as ActionExecutionState.Failed).path)
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
