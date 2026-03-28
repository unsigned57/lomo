package com.lomo.data.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareAttachmentInfo
import com.lomo.domain.model.SharePayload
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: ShareTransferPayloadBuilder, ShareTransferRequestExecutor, ShareTransferRequestProcessor, and LomoShareServer attachment handling.
 * - Behavior focus: multipart attachment upload in open and E2E modes, attachment decryption/copy, saved memo attachment mappings, and rollback of persisted attachments when memo commit fails.
 * - Observable outcomes: transferred attachment bytes, saved attachment metadata, saved memo mappings, incoming prepare payload attachments, and rollback callback invocations after failed memo save.
 * - Red phase: Fails before the fix because the LAN transfer flow has no rollback callback and leaves saved attachments committed when memo persistence fails.
 * - Excludes: sender-side attachment discovery policy, UI approval dialogs, and persistence backend details after save callbacks return or rollback finishes.
 */
class ShareLanAttachmentFlowIntegrationTest {
    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val cacheDir = Files.createTempDirectory("lomo-share-cache").toFile()

    init {
        every { context.contentResolver } returns contentResolver
        every { context.cacheDir } returns cacheDir
        every { contentResolver.openInputStream(any()) } answers {
            val uri = firstArg<Uri>()
            val file = requireNotNull(uri.path) { "File URI path is required" }
            File(file).inputStream()
        }
    }

    @Test
    fun `open mode attachment transfer copies bytes and saves memo mapping`() =
        runBlocking {
            val attachmentFile = tempFile("share-open", ".png", "open-image-payload")
            val attachmentUri = fileUri(attachmentFile)
            val incomingPayloads = mutableListOf<SharePayload>()
            val savedAttachments = mutableListOf<SavedAttachment>()
            val savedMemos = mutableListOf<SavedMemo>()
            val server =
                LomoShareServer().apply {
                    isE2eEnabled = { false }
                    getPairingKeyHex = { null }
                    onIncomingPrepare = { payload ->
                        incomingPayloads += payload
                        acceptIncoming()
                    }
                    onSaveAttachment = { name, type, payloadFile ->
                        savedAttachments += SavedAttachment(name, type, payloadFile.readBytes().decodeToString())
                        "/store/$name"
                    }
                    onSaveMemo = { content, timestamp, attachmentMappings ->
                        savedMemos += SavedMemo(content, timestamp, attachmentMappings)
                    }
                }
            val port = server.start()
            val client = LomoShareClient(context = context) { null }

            try {
                val prepared =
                    client
                        .prepare(
                            device = device(port),
                            content = "memo with open attachment",
                            timestamp = 11L,
                            senderName = "Open Sender",
                            attachments =
                                listOf(
                                    ShareAttachmentInfo(
                                        name = "photo.png",
                                        type = "image",
                                        size = attachmentFile.length(),
                                    ),
                                ),
                            e2eEnabled = false,
                        ).getOrThrow()
                val transferred =
                    client.transfer(
                        device = device(port),
                        content = "memo with open attachment",
                        timestamp = 11L,
                        sessionToken = requireNotNull(prepared.sessionToken),
                        attachmentUris = mapOf("photo.png" to attachmentUri),
                        e2eEnabled = false,
                    )

                assertTrue(transferred)
                assertEquals(
                    listOf(
                        SharePayload(
                            content = "memo with open attachment",
                            timestamp = 11L,
                            senderName = "Open Sender",
                            attachments =
                                listOf(
                                    ShareAttachmentInfo(
                                        name = "photo.png",
                                        type = "image",
                                        size = attachmentFile.length(),
                                    ),
                                ),
                        ),
                    ),
                    incomingPayloads,
                )
                assertEquals(
                    listOf(
                        SavedAttachment(
                            name = "photo.png",
                            type = "image",
                            contents = "open-image-payload",
                        ),
                    ),
                    savedAttachments,
                )
                assertEquals(
                    listOf(
                        SavedMemo(
                            content = "memo with open attachment",
                            timestamp = 11L,
                            attachmentMappings = mapOf("photo.png" to "/store/photo.png"),
                        ),
                    ),
                    savedMemos,
                )
            } finally {
                client.close()
                server.stop()
            }
        }

    @Test
    fun `encrypted attachment transfer decrypts bytes before save callback`() =
        runBlocking {
            val pairingMaterial = requireNotNull(ShareAuthUtils.deriveKeyMaterialFromPairingCode("secure-attachments"))
            val primaryKeyHex = requireNotNull(resolvePrimaryKeyHex(pairingMaterial))
            val attachmentFile = tempFile("share-secure", ".mp3", "secret-audio-payload")
            val attachmentUri = fileUri(attachmentFile)
            val savedAttachments = mutableListOf<SavedAttachment>()
            val savedMemos = mutableListOf<SavedMemo>()
            val server =
                LomoShareServer().apply {
                    isE2eEnabled = { true }
                    getPairingKeyHex = { primaryKeyHex }
                    onIncomingPrepare = { acceptIncoming() }
                    onSaveAttachment = { name, type, payloadFile ->
                        savedAttachments += SavedAttachment(name, type, payloadFile.readBytes().decodeToString())
                        "/secure/$name"
                    }
                    onSaveMemo = { content, timestamp, attachmentMappings ->
                        savedMemos += SavedMemo(content, timestamp, attachmentMappings)
                    }
                }
            val port = server.start()
            val client = LomoShareClient(context = context) { pairingMaterial }

            try {
                val prepared =
                    client
                        .prepare(
                            device = device(port),
                            content = "memo with secure attachment",
                            timestamp = 22L,
                            senderName = "Encrypted Sender",
                            attachments =
                                listOf(
                                    ShareAttachmentInfo(
                                        name = "voice.mp3",
                                        type = "audio",
                                        size = attachmentFile.length(),
                                    ),
                                ),
                            e2eEnabled = true,
                        ).getOrThrow()
                val transferred =
                    client.transfer(
                        device = device(port),
                        content = "memo with secure attachment",
                        timestamp = 22L,
                        sessionToken = requireNotNull(prepared.sessionToken),
                        attachmentUris = mapOf("voice.mp3" to attachmentUri),
                        e2eEnabled = true,
                        e2eKeyHex = prepared.keyHex,
                    )

                assertTrue(transferred)
                assertEquals(
                    listOf(
                        SavedAttachment(
                            name = "voice.mp3",
                            type = "audio",
                            contents = "secret-audio-payload",
                        ),
                    ),
                    savedAttachments,
                )
                assertEquals(
                    listOf(
                        SavedMemo(
                            content = "memo with secure attachment",
                            timestamp = 22L,
                            attachmentMappings = mapOf("voice.mp3" to "/secure/voice.mp3"),
                        ),
                    ),
                    savedMemos,
                )
            } finally {
                client.close()
                server.stop()
            }
        }

    @Test
    fun `memo save failure rolls back previously saved attachments`() =
        runBlocking {
            val attachmentFile = tempFile("share-rollback", ".png", "rollback-image-payload")
            val attachmentUri = fileUri(attachmentFile)
            val savedAttachments = mutableListOf<SavedAttachment>()
            val rolledBackAttachments = mutableListOf<Pair<String, String>>()
            val server =
                LomoShareServer().apply {
                    isE2eEnabled = { false }
                    getPairingKeyHex = { null }
                    onIncomingPrepare = { acceptIncoming() }
                    onSaveAttachment = { name, type, payloadFile ->
                        savedAttachments += SavedAttachment(name, type, payloadFile.readBytes().decodeToString())
                        "/store/$name"
                    }
                    onDeleteAttachment = { savedPath, type ->
                        rolledBackAttachments += savedPath to type
                    }
                    onSaveMemo = { _, _, _ ->
                        throw IllegalStateException("Memo commit failed")
                    }
                }
            val port = server.start()
            val client = LomoShareClient(context = context) { null }

            try {
                val prepared =
                    client
                        .prepare(
                            device = device(port),
                            content = "memo requiring rollback",
                            timestamp = 33L,
                            senderName = "Rollback Sender",
                            attachments =
                                listOf(
                                    ShareAttachmentInfo(
                                        name = "photo.png",
                                        type = "image",
                                        size = attachmentFile.length(),
                                    ),
                                ),
                            e2eEnabled = false,
                        ).getOrThrow()
                val transferred =
                    client.transfer(
                        device = device(port),
                        content = "memo requiring rollback",
                        timestamp = 33L,
                        sessionToken = requireNotNull(prepared.sessionToken),
                        attachmentUris = mapOf("photo.png" to attachmentUri),
                        e2eEnabled = false,
                    )

                assertFalse(transferred)
                assertEquals(
                    listOf(
                        SavedAttachment(
                            name = "photo.png",
                            type = "image",
                            contents = "rollback-image-payload",
                        ),
                    ),
                    savedAttachments,
                )
                assertEquals(listOf("/store/photo.png" to "image"), rolledBackAttachments)
            } finally {
                client.close()
                server.stop()
            }
        }

    private fun device(port: Int): DiscoveredDevice =
        DiscoveredDevice(
            name = "Receiver",
            host = "127.0.0.1",
            port = port,
        )

    private fun tempFile(
        prefix: String,
        suffix: String,
        contents: String,
    ): File =
        Files.createTempFile(prefix, suffix).toFile().apply {
            writeText(contents)
        }

    private fun fileUri(file: File): Uri =
        mockk<Uri>(relaxed = true).apply {
            every { scheme } returns "file"
            every { path } returns file.absolutePath
        }

    private data class SavedAttachment(
        val name: String,
        val type: String,
        val contents: String,
    )

    private data class SavedMemo(
        val content: String,
        val timestamp: Long,
        val attachmentMappings: Map<String, String>,
    )
}
