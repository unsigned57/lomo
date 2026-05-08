package com.lomo.data.share

import android.content.Context
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.SharePayload
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: LomoShareServer, LomoShareClient, SharePrepareRequestProcessor, ShareTransferRequestProcessor, and share request executors.
 * - Behavior focus: approval-driven prepare flow, open vs E2E transfer success, compatibility-key retry, and invalid-session rejection.
 * - Observable outcomes: prepared session token/key, captured incoming payload, saved memo content, and transfer success/failure.
 * - Red phase: Fails before the fix because open-mode prepare still succeeds without sender or
 *   receiver pairing configuration, so the new missing-pairing contract stays green incorrectly.
 * - Excludes: UI prompts, attachment file persistence internals, and discovery transport.
 */
class ShareLanFlowIntegrationTest {
    private val context: Context = mockk(relaxed = true)

    @Test
    fun `open mode share flow saves memo after approval`() =
        runBlocking {
            val pairingMaterial = requireNotNull(ShareAuthUtils.deriveKeyMaterialFromPairingCode("open-mode-123"))
            val receiverKey = requireNotNull(resolvePrimaryKeyHex(pairingMaterial))
            val incomingPayloads = mutableListOf<SharePayload>()
            val savedMemos = mutableListOf<SavedMemo>()
            val server =
                configuredServer(
                    e2eEnabled = false,
                    pairingKeyHex = receiverKey,
                    onIncomingPrepare = { payload, activeServer ->
                        incomingPayloads += payload
                        activeServer.acceptIncoming()
                    },
                    onSaveMemo = { content, timestamp, attachmentMappings ->
                        savedMemos += SavedMemo(content, timestamp, attachmentMappings)
                    },
                )
            val port = server.start()
            val client = LomoShareClient(context = context) { pairingMaterial }

            try {
                val device = device(port)
                val content = "hello from open mode"
                val timestamp = 1_717_171L

                val prepared =
                    client
                        .prepare(
                            device = device,
                            content = content,
                            timestamp = timestamp,
                            senderName = "Sender",
                            attachments = emptyList(),
                            e2eEnabled = false,
                        ).getOrThrow()
                val transferred =
                    client.transfer(
                        device = device,
                        content = content,
                        timestamp = timestamp,
                        sessionToken = requireNotNull(prepared.sessionToken),
                        attachmentUris = emptyMap(),
                        e2eEnabled = false,
                    )

                assertNotNull(prepared.sessionToken)
                assertNull(prepared.keyHex)
                assertTrue(transferred)
                assertEquals(
                    listOf(
                        SharePayload(
                            content = content,
                            timestamp = timestamp,
                            senderName = "Sender",
                            attachments = emptyList(),
                        ),
                    ),
                    incomingPayloads,
                )
                assertEquals(listOf(SavedMemo(content, timestamp, emptyMap())), savedMemos)
            } finally {
                client.close()
                server.stop()
            }
        }

    @Test
    fun `open mode prepare fails when sender pairing code is missing`() =
        runBlocking {
            val pairingMaterial = requireNotNull(ShareAuthUtils.deriveKeyMaterialFromPairingCode("open-mode-123"))
            val receiverKey = requireNotNull(resolvePrimaryKeyHex(pairingMaterial))
            val server =
                configuredServer(
                    e2eEnabled = false,
                    pairingKeyHex = receiverKey,
                    onIncomingPrepare = { _, _ -> error("Prepare should fail before approval without sender pairing") },
                    onSaveMemo = { _, _, _ -> error("Memo should not be saved without sender pairing") },
                )
            val port = server.start()
            val client = LomoShareClient(context = context) { null }

            try {
                val result =
                    client.prepare(
                        device = device(port),
                        content = "unsigned open mode",
                        timestamp = 9L,
                        senderName = "Sender",
                        attachments = emptyList(),
                        e2eEnabled = false,
                    )

                assertTrue(result.isFailure)
            } finally {
                client.close()
                server.stop()
            }
        }

    @Test
    fun `encrypted share flow retries with compatibility key and saves decrypted memo`() =
        runBlocking {
            val pairingMaterial = requireNotNull(ShareAuthUtils.deriveKeyMaterialFromPairingCode("compat-123"))
            val legacyReceiverKey = resolveCandidateKeyHexes(pairingMaterial).last()
            val incomingPayloads = mutableListOf<SharePayload>()
            val savedMemos = mutableListOf<SavedMemo>()
            val server =
                configuredServer(
                    e2eEnabled = true,
                    pairingKeyHex = legacyReceiverKey,
                    onIncomingPrepare = { payload, activeServer ->
                        incomingPayloads += payload
                        activeServer.acceptIncoming()
                    },
                    onSaveMemo = { content, timestamp, attachmentMappings ->
                        savedMemos += SavedMemo(content, timestamp, attachmentMappings)
                    },
                )
            val port = server.start()
            val client = LomoShareClient(context = context) { pairingMaterial }

            try {
                val device = device(port)
                val content = "secret memo"
                val timestamp = 2_424_242L

                val prepared =
                    client
                        .prepare(
                            device = device,
                            content = content,
                            timestamp = timestamp,
                            senderName = "Encrypted Sender",
                            attachments = emptyList(),
                            e2eEnabled = true,
                        ).getOrThrow()
                val transferred =
                    client.transfer(
                        device = device,
                        content = content,
                        timestamp = timestamp,
                        sessionToken = requireNotNull(prepared.sessionToken),
                        attachmentUris = emptyMap(),
                        e2eEnabled = true,
                        e2eKeyHex = prepared.keyHex,
                    )

                assertEquals(legacyReceiverKey, prepared.keyHex)
                assertTrue(transferred)
                assertEquals(
                    listOf(
                        SharePayload(
                            content = content,
                            timestamp = timestamp,
                            senderName = "Encrypted Sender",
                            attachments = emptyList(),
                        ),
                    ),
                    incomingPayloads,
                )
                assertEquals(listOf(SavedMemo(content, timestamp, emptyMap())), savedMemos)
            } finally {
                client.close()
                server.stop()
            }
        }

    @Test
    fun `prepare returns empty session token when receiver rejects request`() =
        runBlocking {
            val pairingMaterial = requireNotNull(ShareAuthUtils.deriveKeyMaterialFromPairingCode("open-mode-123"))
            val receiverKey = requireNotNull(resolvePrimaryKeyHex(pairingMaterial))
            val server =
                configuredServer(
                    e2eEnabled = false,
                    pairingKeyHex = receiverKey,
                    onIncomingPrepare = { _, activeServer ->
                        activeServer.rejectIncoming()
                    },
                    onSaveMemo = { _, _, _ -> error("Memo should not be saved when prepare is rejected") },
                )
            val port = server.start()
            val client = LomoShareClient(context = context) { pairingMaterial }

            try {
                val prepared =
                    client
                        .prepare(
                            device = device(port),
                            content = "declined",
                            timestamp = 5L,
                            senderName = "Sender",
                            attachments = emptyList(),
                            e2eEnabled = false,
                        ).getOrThrow()

                assertNull(prepared.sessionToken)
                assertNull(prepared.keyHex)
            } finally {
                client.close()
                server.stop()
            }
        }

    @Test
    fun `transfer returns false when session token was never approved`() =
        runBlocking {
            var saveInvoked = false
            val server =
                configuredServer(
                    e2eEnabled = false,
                    pairingKeyHex = null,
                    onIncomingPrepare = { _, _ -> error("Prepare should not be invoked for invalid session transfer") },
                    onSaveMemo = { _, _, _ ->
                        saveInvoked = true
                    },
                )
            val port = server.start()
            val client = LomoShareClient(context = context) { null }

            try {
                val transferred =
                    client.transfer(
                        device = device(port),
                        content = "unauthorized transfer",
                        timestamp = 7L,
                        sessionToken = "bogus-session",
                        attachmentUris = emptyMap(),
                        e2eEnabled = false,
                    )

                assertFalse(transferred)
                assertFalse(saveInvoked)
            } finally {
                client.close()
                server.stop()
            }
        }

    private fun configuredServer(
        e2eEnabled: Boolean,
        pairingKeyHex: String?,
        onIncomingPrepare: (SharePayload, LomoShareServer) -> Unit,
        onSaveMemo: suspend (String, Long, Map<String, String>) -> Unit,
    ): LomoShareServer =
        LomoShareServer().apply {
            isE2eEnabled = { e2eEnabled }
            getPairingKeyHex = { pairingKeyHex }
            this.onIncomingPrepare = { payload ->
                onIncomingPrepare(payload, this)
            }
            this.onSaveMemo = onSaveMemo
            onSaveAttachment = { _, _, _ -> error("No attachments expected in this test") }
        }

    private fun device(port: Int): DiscoveredDevice =
        DiscoveredDevice(
            name = "Receiver",
            host = "127.0.0.1",
            port = port,
        )

    private data class SavedMemo(
        val content: String,
        val timestamp: Long,
        val attachmentMappings: Map<String, String>,
    )
}
