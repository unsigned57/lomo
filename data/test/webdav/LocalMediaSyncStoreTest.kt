package com.lomo.data.webdav

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
import com.lomo.data.sync.SyncDirectoryLayout
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.KotestTemporaryFolder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Behavior Contract:
 * - Unit under test: LocalMediaSyncStore
 * - Behavior focus: direct-root media discovery, read or write routing, deletion, and media-path classification.
 * - Observable outcomes: configured category sets, listed relative paths, file byte content, thrown IOException messages, and content-type classification.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: SAF DocumentFile behavior, Android ContentResolver streams, and WebDAV transport.
 */
class LocalMediaSyncStoreTest : DataFunSpec() {
    init {
        beforeTest {
            tempFolder = KotestTemporaryFolder()
        }

        afterTest {
            tempFolder.cleanup()
        }

        test("configuredCategories and listFiles include only accepted direct media files") { `configuredCategories and listFiles include only accepted direct media files`() }

        test("direct roots support write read delete and folder-based media classification") { `direct roots support write read delete and folder-based media classification`() }

        test("writeBytes fails closed when no media root is configured for the path") { `writeBytes fails closed when no media root is configured for the path`() }
    }


    private lateinit var tempFolder: KotestTemporaryFolder
    private val context = mockk<Context>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)
    private val layout =
        SyncDirectoryLayout(
            memoFolder = "memo",
            imageFolder = "images",
            voiceFolder = "voice",
            allSameDirectory = false,
        )

    private fun `configuredCategories and listFiles include only accepted direct media files`() =
        runTest {
            val imageRoot = tempFolder.newFolder("images-root")
            val voiceRoot = tempFolder.newFolder("voice-root")
            File(imageRoot, "cover.PNG").writeText("image")
            File(imageRoot, "notes.txt").writeText("ignore")
            File(voiceRoot, "clip.MP3").writeText("voice")
            File(voiceRoot, "photo.jpg").writeText("ignore")
            configureRoots(imageRoot = imageRoot, voiceRoot = voiceRoot)

            val store = LocalMediaSyncStore(context, dataStore)

            store.configuredCategories() shouldBe linkedSetOf(MediaSyncCategory.IMAGE, MediaSyncCategory.VOICE)
            val files = store.listFiles(layout)
            files.keys shouldBe setOf("images/cover.PNG", "voice/clip.MP3")
        }

    private fun `direct roots support write read delete and folder-based media classification`() =
        runTest {
            val imageRoot = tempFolder.newFolder("images-root")
            val voiceRoot = tempFolder.newFolder("voice-root")
            configureRoots(imageRoot = imageRoot, voiceRoot = voiceRoot)
            val store = LocalMediaSyncStore(context, dataStore)
            val bytes = byteArrayOf(1, 2, 3, 4)

            store.writeBytes("lomo/images/photo.PNG", bytes, layout)

            File(imageRoot, "photo.PNG").readBytes() shouldBe bytes
            store.readBytes("images/photo.PNG", layout) shouldBe bytes
            (store.isMediaPath("lomo/images/photo.PNG", layout)).shouldBeTrue()
            (store.isMediaPath("voice/clip.bin", layout)).shouldBeTrue()
            (store.isMediaPath("memo/note.md", layout)).shouldBeFalse()
            store.contentTypeForPath("lomo/images/photo.PNG", layout) shouldBe "image/png"
            store.contentTypeForPath("voice/clip.bin", layout) shouldBe "audio/mp4"
            store.contentTypeForPath("memo/note.md", layout) shouldBe "application/octet-stream"

            store.delete("lomo/images/photo.PNG", layout)

            (File(imageRoot, "photo.PNG").exists()).shouldBeFalse()
        }

    private fun `writeBytes fails closed when no media root is configured for the path`() =
        runTest {
            configureRoots(imageRoot = null, voiceRoot = null)
            val store = LocalMediaSyncStore(context, dataStore)

            var thrown: Throwable? = null
            try {
                store.writeBytes("images/photo.jpg", byteArrayOf(9), layout)
            } catch (error: Throwable) {
                thrown = error
            }

            (thrown is IOException).shouldBeTrue()
            thrown?.message shouldBe "Media root not configured for: images/photo.jpg"
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
