package com.lomo.data.webdav

import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.SyncDirectoryLayout
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/*
 * Test Contract:
 * - Unit under test: LocalMediaSyncStore
 * - Behavior focus: direct-root media discovery, read or write routing, deletion, and media-path classification.
 * - Observable outcomes: configured category sets, listed relative paths, file byte content, thrown IOException messages, and content-type classification.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: SAF DocumentFile behavior, Android ContentResolver streams, and WebDAV transport.
 */
class LocalMediaSyncStoreTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)
    private val layout =
        SyncDirectoryLayout(
            memoFolder = "memo",
            imageFolder = "images",
            voiceFolder = "voice",
            allSameDirectory = false,
        )

    @Test
    fun `configuredCategories and listFiles include only accepted direct media files`() =
        runTest {
            val imageRoot = tempFolder.newFolder("images-root")
            val voiceRoot = tempFolder.newFolder("voice-root")
            File(imageRoot, "cover.PNG").writeText("image")
            File(imageRoot, "notes.txt").writeText("ignore")
            File(voiceRoot, "clip.MP3").writeText("voice")
            File(voiceRoot, "photo.jpg").writeText("ignore")
            configureRoots(imageRoot = imageRoot, voiceRoot = voiceRoot)

            val store = LocalMediaSyncStore(context, dataStore)

            assertEquals(linkedSetOf(MediaSyncCategory.IMAGE, MediaSyncCategory.VOICE), store.configuredCategories())
            val files = store.listFiles(layout)
            assertEquals(setOf("images/cover.PNG", "voice/clip.MP3"), files.keys)
        }

    @Test
    fun `direct roots support write read delete and folder-based media classification`() =
        runTest {
            val imageRoot = tempFolder.newFolder("images-root")
            val voiceRoot = tempFolder.newFolder("voice-root")
            configureRoots(imageRoot = imageRoot, voiceRoot = voiceRoot)
            val store = LocalMediaSyncStore(context, dataStore)
            val bytes = byteArrayOf(1, 2, 3, 4)

            store.writeBytes("lomo/images/photo.PNG", bytes, layout)

            assertArrayEquals(bytes, File(imageRoot, "photo.PNG").readBytes())
            assertArrayEquals(bytes, store.readBytes("images/photo.PNG", layout))
            assertTrue(store.isMediaPath("lomo/images/photo.PNG", layout))
            assertTrue(store.isMediaPath("voice/clip.bin", layout))
            assertFalse(store.isMediaPath("memo/note.md", layout))
            assertEquals("image/png", store.contentTypeForPath("lomo/images/photo.PNG", layout))
            assertEquals("audio/mp4", store.contentTypeForPath("voice/clip.bin", layout))
            assertEquals("application/octet-stream", store.contentTypeForPath("memo/note.md", layout))

            store.delete("lomo/images/photo.PNG", layout)

            assertFalse(File(imageRoot, "photo.PNG").exists())
        }

    @Test
    fun `writeBytes fails closed when no media root is configured for the path`() =
        runTest {
            configureRoots(imageRoot = null, voiceRoot = null)
            val store = LocalMediaSyncStore(context, dataStore)

            var thrown: Throwable? = null
            try {
                store.writeBytes("images/photo.jpg", byteArrayOf(9), layout)
            } catch (error: Throwable) {
                thrown = error
            }

            assertTrue(thrown is IOException)
            assertEquals("Media root not configured for: images/photo.jpg", thrown?.message)
        }

    private fun configureRoots(
        imageRoot: File?,
        voiceRoot: File?,
    ) {
        every { dataStore.imageDirectory } returns flowOf(imageRoot?.absolutePath)
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(voiceRoot?.absolutePath)
        every { dataStore.voiceUri } returns flowOf(null)
    }
}
