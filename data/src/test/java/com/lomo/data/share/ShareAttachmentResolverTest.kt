package com.lomo.data.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.ShareAttachmentInfo
import com.lomo.domain.model.ShareTransferErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: ShareAttachmentResolver
 * - Behavior focus: attachment URI resolution order, file-size/type validation, and sender-side error classification.
 * - Observable outcomes: success vs failure result, resolved URIs, resolved ShareAttachmentInfo metadata, and ShareTransferError codes.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: Content tree traversal via DocumentFile, LAN transport, and memo send orchestration.
 */
class ShareAttachmentResolverTest {
    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)
    private val uriCache = linkedMapOf<String, Uri>()

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } answers { fileUri(firstArg()) }
        every { Uri.parse(any()) } answers { parsedUri(firstArg()) }
        every { context.contentResolver } returns contentResolver
        every { dataStore.imageDirectory } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(null)
        every { dataStore.rootDirectory } returns flowOf(null)
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceUri } returns flowOf(null)
        every { dataStore.rootUri } returns flowOf(null)
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
        uriCache.clear()
    }

    @Test
    fun `prepareAttachments returns attachment info for direct file uri`() =
        runBlocking {
            val file = tempFile("photo", ".png", "png-bytes")
            val resolver = ShareAttachmentResolver(context, dataStore)

            val result =
                resolver.prepareAttachments(
                    rawAttachmentUris = mapOf("photo.png" to fileUriString(file)),
                )

            assertTrue(result is ShareAttachmentResolver.AttachmentPreparationResult.Success)
            val prepared =
                (result as ShareAttachmentResolver.AttachmentPreparationResult.Success).prepared
            assertEquals(mapOf("photo.png" to fileUri(file)), prepared.uris)
            assertEquals(
                listOf(
                    ShareAttachmentInfo(
                        name = "photo.png",
                        type = "image",
                        size = file.length(),
                    ),
                ),
                prepared.infos,
            )
        }

    @Test
    fun `prepareAttachments resolves attachment from configured image directory`() =
        runBlocking {
            val directory = Files.createTempDirectory("lomo-share-images").toFile()
            val file = File(directory, "cover.jpg").apply { writeText("jpeg") }
            every { dataStore.imageDirectory } returns flowOf(directory.absolutePath)
            val resolver = ShareAttachmentResolver(context, dataStore)

            val result =
                resolver.prepareAttachments(
                    rawAttachmentUris = mapOf("cover.jpg" to "app://missing"),
                )

            assertTrue(result is ShareAttachmentResolver.AttachmentPreparationResult.Success)
            val prepared =
                (result as ShareAttachmentResolver.AttachmentPreparationResult.Success).prepared
            assertEquals(Uri.fromFile(file), prepared.uris["cover.jpg"])
            assertEquals("image", prepared.infos.single().type)
        }

    @Test
    fun `prepareAttachments reports missing attachment when no uri can be resolved`() =
        runBlocking {
            val resolver = ShareAttachmentResolver(context, dataStore)

            val result =
                resolver.prepareAttachments(
                    rawAttachmentUris = mapOf("ghost.jpg" to "app://missing"),
                )

            assertTrue(result is ShareAttachmentResolver.AttachmentPreparationResult.Failure)
            val error =
                (result as ShareAttachmentResolver.AttachmentPreparationResult.Failure).error
            assertEquals(ShareTransferErrorCode.ATTACHMENT_RESOLVE_FAILED, error.code)
            assertEquals(1, error.missingAttachmentCount)
        }

    @Test
    fun `prepareAttachments rejects unsupported attachment type`() =
        runBlocking {
            val file = tempFile("note", ".txt", "plain-text")
            val resolver = ShareAttachmentResolver(context, dataStore)

            val result =
                resolver.prepareAttachments(
                    rawAttachmentUris = mapOf("note.txt" to fileUriString(file)),
                )

            assertTrue(result is ShareAttachmentResolver.AttachmentPreparationResult.Failure)
            val error =
                (result as ShareAttachmentResolver.AttachmentPreparationResult.Failure).error
            assertEquals(ShareTransferErrorCode.UNSUPPORTED_ATTACHMENT_TYPE, error.code)
        }

    @Test
    fun `prepareAttachments rejects attachment that exceeds payload size limit`() =
        runBlocking {
            val file = Files.createTempFile("lomo-share-huge", ".png").toFile()
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(101L * 1024L * 1024L)
            }
            val resolver = ShareAttachmentResolver(context, dataStore)

            val result =
                resolver.prepareAttachments(
                    rawAttachmentUris = mapOf("huge.png" to fileUriString(file)),
                )

            assertTrue(result is ShareAttachmentResolver.AttachmentPreparationResult.Failure)
            val error =
                (result as ShareAttachmentResolver.AttachmentPreparationResult.Failure).error
            assertEquals(ShareTransferErrorCode.ATTACHMENT_TOO_LARGE, error.code)
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
        uriCache.getOrPut("file://${file.absolutePath}") {
            mockk<Uri>(relaxed = true).apply {
                every { scheme } returns "file"
                every { path } returns file.absolutePath
            }
        }

    private fun fileUriString(file: File): String = "file://${file.absolutePath}"

    private fun parsedUri(value: String): Uri =
        when {
            value.startsWith("file://") -> fileUri(File(value.removePrefix("file://")))
            value.startsWith("app://") ->
                uriCache.getOrPut(value) {
                    mockk<Uri>(relaxed = true).apply {
                        every { scheme } returns "app"
                        every { path } returns "/${value.removePrefix("app://")}"
                    }
                }

            else ->
                uriCache.getOrPut(value) {
                    mockk<Uri>(relaxed = true).apply {
                        every { scheme } returns value.substringBefore(':', "")
                        every { path } returns value.substringAfter("://", value)
                    }
                }
        }
}
