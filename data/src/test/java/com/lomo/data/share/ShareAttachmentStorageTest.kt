package com.lomo.data.share


import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.MediaStorageDataSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Test Contract:
 * - Unit under test: ShareAttachmentStorage
 * - Behavior focus: image/audio attachment save routing, duplicate filename resolution, filename sanitization, blank-name fallback, and failure fallback.
 * - Observable outcomes: returned saved filename or null, bytes copied to the destination stream, resolved audio filename passed to MediaStorageDataSource, and unsupported-type handling.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: Document tree traversal, actual media store persistence backends, and LAN transfer orchestration.
 */
class ShareAttachmentStorageTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tearDown()
        }

        test("saveAttachmentFile delegates image attachments to saveImage") { `saveAttachmentFile delegates image attachments to saveImage`() }

        test("saveAttachmentFile sanitizes audio filename resolves duplicates and copies bytes") { `saveAttachmentFile sanitizes audio filename resolves duplicates and copies bytes`() }

        test("saveAttachmentFile falls back to generated attachment name when source name is blank") { `saveAttachmentFile falls back to generated attachment name when source name is blank`() }

        test("saveAttachmentFile returns null for unsupported attachment type") { `saveAttachmentFile returns null for unsupported attachment type`() }

        test("saveAttachmentFile returns null when audio output stream cannot be opened") { `saveAttachmentFile returns null when audio output stream cannot be opened`() }
    }


    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val dataSource = mockk<MediaStorageDataSource>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)
    private val uriCache = linkedMapOf<String, Uri>()

    private lateinit var storage: ShareAttachmentStorage

    private fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } answers { fileUri(firstArg()) }
        every { context.contentResolver } returns contentResolver
        every { dataStore.voiceDirectory } returns flowOf(null)
        every { dataStore.rootDirectory } returns flowOf(null)
        every { dataStore.voiceUri } returns flowOf(null)
        every { dataStore.rootUri } returns flowOf(null)
        storage = ShareAttachmentStorage(context, dataSource, dataStore)
    }

    private fun tearDown() {
        unmockkStatic(Uri::class)
        uriCache.clear()
    }

    private fun `saveAttachmentFile delegates image attachments to saveImage`() =
        runBlocking {
            val payloadFile = tempFile("share-image", ".png", "image-bytes")
            val payloadUri = fileUri(payloadFile)
            coEvery { dataSource.saveImage(payloadUri) } returns "images/cover.png"

            val result = storage.saveAttachmentFile("cover.png", "image", payloadFile)

            result shouldBe "images/cover.png"
            coVerify(exactly = 1) { dataSource.saveImage(payloadUri) }
        }

    private fun `saveAttachmentFile sanitizes audio filename resolves duplicates and copies bytes`() =
        runBlocking {
            val voiceDir = Files.createTempDirectory("share-voice").toFile()
            File(voiceDir, "voice_note.m4a").writeText("existing")
            every { dataStore.voiceDirectory } returns flowOf(voiceDir.absolutePath)
            val payloadFile = tempFile("share-audio", ".m4a", "audio-payload")
            val output = ByteArrayOutputStream()
            val createdUri = mockk<Uri>(relaxed = true)
            every { contentResolver.openOutputStream(createdUri) } returns output
            coEvery { dataSource.createVoiceFile("voice_note_1.m4a") } returns createdUri

            val result = storage.saveAttachmentFile("../voice note.m4a", "audio", payloadFile)

            result shouldBe "voice_note_1.m4a"
            output.toString(Charsets.UTF_8) shouldBe "audio-payload"
        }

    private fun `saveAttachmentFile falls back to generated attachment name when source name is blank`() =
        runBlocking {
            val payloadFile = tempFile("share-blank", ".bin", "voice")
            val output = ByteArrayOutputStream()
            val createdUri = mockk<Uri>(relaxed = true)
            every { contentResolver.openOutputStream(createdUri) } returns output
            coEvery { dataSource.createVoiceFile(match { it.startsWith("attachment_") }) } returns createdUri

            val result = storage.saveAttachmentFile("   ", "audio", payloadFile)

            (result != null && result.startsWith("attachment_")).shouldBeTrue()
            output.toString(Charsets.UTF_8) shouldBe "voice"
        }

    private fun `saveAttachmentFile returns null for unsupported attachment type`() =
        runBlocking {
            val payloadFile = tempFile("share-note", ".txt", "plain")

            val result = storage.saveAttachmentFile("note.txt", "text", payloadFile)

            result.shouldBeNull()
            coVerify(exactly = 0) { dataSource.saveImage(any()) }
            coVerify(exactly = 0) { dataSource.createVoiceFile(any()) }
        }

    private fun `saveAttachmentFile returns null when audio output stream cannot be opened`() =
        runBlocking {
            val payloadFile = tempFile("share-error", ".m4a", "voice")
            val createdUri = mockk<Uri>(relaxed = true)
            every { contentResolver.openOutputStream(createdUri) } returns null
            coEvery { dataSource.createVoiceFile("voice.m4a") } returns createdUri

            val result = storage.saveAttachmentFile("voice.m4a", "audio", payloadFile)

            result.shouldBeNull()
        }

    private fun tempFile(
        prefix: String,
        suffix: String,
        contents: String,
    ): File =
        Files.createTempFile(prefix, suffix).toFile().apply {
            writeText(contents)
        }

    private fun fileUri(file: File): Uri =
        uriCache.getOrPut(file.absolutePath) {
            mockk<Uri>(relaxed = true).apply {
                every { scheme } returns "file"
                every { path } returns file.absolutePath
            }
        }
}
