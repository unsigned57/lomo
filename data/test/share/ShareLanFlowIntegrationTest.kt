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



import android.content.Context
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.SharePayload
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

/*
 * Behavior Contract:
 * - Unit under test: LomoShareServer, LomoShareClient, SharePrepareRequestProcessor, ShareTransferRequestProcessor, and share request executors.
 * - Behavior focus: approval-driven prepare flow, open vs E2E transfer success, compatibility-key retry, and invalid-session rejection.
 * - Observable outcomes: prepared session token/key, captured incoming payload, saved memo content, and transfer success/failure.
 * - TDD proof: Fails before the fix because open-mode prepare still succeeds without sender or
 *   receiver pairing configuration, so the new missing-pairing contract stays green incorrectly.
 * - Excludes: UI prompts, attachment file persistence internals, and discovery transport.
 */
class ShareLanFlowIntegrationTest : DataFunSpec() {
    init {
        test("open mode share flow saves memo after approval") { `open mode share flow saves memo after approval`() }

        test("open mode prepare fails when sender pairing code is missing") { `open mode prepare fails when sender pairing code is missing`() }

        test("encrypted share flow retries with compatibility key and saves decrypted memo") { `encrypted share flow retries with compatibility key and saves decrypted memo`() }

        test("prepare returns empty session token when receiver rejects request") { `prepare returns empty session token when receiver rejects request`() }

        test("transfer returns false when session token was never approved") { `transfer returns false when session token was never approved`() }
    }


    private val context: Context = mockk(relaxed = true)

    private fun `open mode share flow saves memo after approval`() =
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

                prepared.sessionToken.shouldNotBeNull()
                prepared.keyHex.shouldBeNull()
                (transferred).shouldBeTrue()
                incomingPayloads shouldBe listOf(
                        SharePayload(
                            content = content,
                            timestamp = timestamp,
                            senderName = "Sender",
                            attachments = emptyList(),
                        ),
                    )
                savedMemos shouldBe listOf(SavedMemo(content, timestamp, emptyMap()))
            } finally {
                client.close()
                server.stop()
            }
        }

    private fun `open mode prepare fails when sender pairing code is missing`() =
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

                (result.isFailure).shouldBeTrue()
            } finally {
                client.close()
                server.stop()
            }
        }

    private fun `encrypted share flow retries with compatibility key and saves decrypted memo`() =
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

                prepared.keyHex shouldBe legacyReceiverKey
                (transferred).shouldBeTrue()
                incomingPayloads shouldBe listOf(
                        SharePayload(
                            content = content,
                            timestamp = timestamp,
                            senderName = "Encrypted Sender",
                            attachments = emptyList(),
                        ),
                    )
                savedMemos shouldBe listOf(SavedMemo(content, timestamp, emptyMap()))
            } finally {
                client.close()
                server.stop()
            }
        }

    private fun `prepare returns empty session token when receiver rejects request`() =
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

                prepared.sessionToken.shouldBeNull()
                prepared.keyHex.shouldBeNull()
            } finally {
                client.close()
                server.stop()
            }
        }

    private fun `transfer returns false when session token was never approved`() =
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

                (transferred).shouldBeFalse()
                (saveInvoked).shouldBeFalse()
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
