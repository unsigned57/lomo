package com.lomo.data.share

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
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: ShareAttachmentResolver
 * - Behavior focus: attachment URI resolution order, file-size/type validation, and sender-side error classification.
 * - Observable outcomes: success vs failure result, resolved URIs, resolved ShareAttachmentInfo metadata, and ShareTransferError codes.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: Content tree traversal via DocumentFile, LAN transport, and memo send orchestration.
 */
class ShareAttachmentResolverTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tearDown()
        }

        test("prepareAttachments returns attachment info for direct file uri") { `prepareAttachments returns attachment info for direct file uri`() }

        test("prepareAttachments resolves attachment from configured image directory") { `prepareAttachments resolves attachment from configured image directory`() }

        test("prepareAttachments reports missing attachment when no uri can be resolved") { `prepareAttachments reports missing attachment when no uri can be resolved`() }

        test("prepareAttachments rejects unsupported attachment type") { `prepareAttachments rejects unsupported attachment type`() }

        test("prepareAttachments rejects attachment that exceeds payload size limit") { `prepareAttachments rejects attachment that exceeds payload size limit`() }
    }


    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)
    private val uriCache = linkedMapOf<String, Uri>()

    private fun setUp() {
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

    private fun tearDown() {
        unmockkStatic(Uri::class)
        uriCache.clear()
    }

    private fun `prepareAttachments returns attachment info for direct file uri`() =
        runBlocking {
            val file = tempFile("photo", ".png", "png-bytes")
            val resolver = ShareAttachmentResolver(context, dataStore)

            val result =
                resolver.prepareAttachments(
                    rawAttachmentUris = mapOf("photo.png" to fileUriString(file)),
                )

            (result is ShareAttachmentResolver.AttachmentPreparationResult.Success).shouldBeTrue()
            val prepared =
                (result as ShareAttachmentResolver.AttachmentPreparationResult.Success).prepared
            prepared.uris shouldBe mapOf("photo.png" to fileUri(file))
            prepared.infos shouldBe listOf(
                    ShareAttachmentInfo(
                        name = "photo.png",
                        type = "image",
                        size = file.length(),
                    ),
                )
        }

    private fun `prepareAttachments resolves attachment from configured image directory`() =
        runBlocking {
            val directory = Files.createTempDirectory("lomo-share-images").toFile()
            val file = File(directory, "cover.jpg").apply { writeText("jpeg") }
            every { dataStore.imageDirectory } returns flowOf(directory.absolutePath)
            val resolver = ShareAttachmentResolver(context, dataStore)

            val result =
                resolver.prepareAttachments(
                    rawAttachmentUris = mapOf("cover.jpg" to "app://missing"),
                )

            (result is ShareAttachmentResolver.AttachmentPreparationResult.Success).shouldBeTrue()
            val prepared =
                (result as ShareAttachmentResolver.AttachmentPreparationResult.Success).prepared
            prepared.uris["cover.jpg"] shouldBe Uri.fromFile(file)
            prepared.infos.single().type shouldBe "image"
        }

    private fun `prepareAttachments reports missing attachment when no uri can be resolved`() =
        runBlocking {
            val resolver = ShareAttachmentResolver(context, dataStore)

            val result =
                resolver.prepareAttachments(
                    rawAttachmentUris = mapOf("ghost.jpg" to "app://missing"),
                )

            (result is ShareAttachmentResolver.AttachmentPreparationResult.Failure).shouldBeTrue()
            val error =
                (result as ShareAttachmentResolver.AttachmentPreparationResult.Failure).error
            error.code shouldBe ShareTransferErrorCode.ATTACHMENT_RESOLVE_FAILED
            error.missingAttachmentCount shouldBe 1
        }

    private fun `prepareAttachments rejects unsupported attachment type`() =
        runBlocking {
            val file = tempFile("note", ".txt", "plain-text")
            val resolver = ShareAttachmentResolver(context, dataStore)

            val result =
                resolver.prepareAttachments(
                    rawAttachmentUris = mapOf("note.txt" to fileUriString(file)),
                )

            (result is ShareAttachmentResolver.AttachmentPreparationResult.Failure).shouldBeTrue()
            val error =
                (result as ShareAttachmentResolver.AttachmentPreparationResult.Failure).error
            error.code shouldBe ShareTransferErrorCode.UNSUPPORTED_ATTACHMENT_TYPE
        }

    private fun `prepareAttachments rejects attachment that exceeds payload size limit`() =
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

            (result is ShareAttachmentResolver.AttachmentPreparationResult.Failure).shouldBeTrue()
            val error =
                (result as ShareAttachmentResolver.AttachmentPreparationResult.Failure).error
            error.code shouldBe ShareTransferErrorCode.ATTACHMENT_TOO_LARGE
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
