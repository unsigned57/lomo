package com.lomo.data.share


import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareTransferErrorCode
import com.lomo.domain.model.ShareTransferState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Test Contract:
 * - Unit under test: ShareTransferOrchestrator
 * - Behavior focus: send state transitions, prepare/transfer failure classification, and pairing gate enforcement.
 * - Observable outcomes: Result success/failure, exposed ShareTransferState, and prepare/transfer collaborator calls.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: HTTP transport internals, attachment URI discovery, and UI rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareTransferOrchestratorTest : DataFunSpec() {
    init {
        test("sendMemo stops immediately when pairing is required before send") { `sendMemo stops immediately when pairing is required before send`() }

        test("sendMemo moves to success when prepare and transfer both succeed") { `sendMemo moves to success when prepare and transfer both succeed`() }

        test("sendMemo maps prepare failure to connection error state") { `sendMemo maps prepare failure to connection error state`() }

        test("sendMemo maps rejected prepare session to transfer rejected error") { `sendMemo maps rejected prepare session to transfer rejected error`() }

        test("sendMemo maps false transfer result to transfer failed error") { `sendMemo maps false transfer result to transfer failed error`() }
    }


    private val context = mockk<Context>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)
    private val pairingConfig = mockk<SharePairingConfig>(relaxed = true)
    private val device = DiscoveredDevice(name = "Pixel", host = "127.0.0.1", port = 8080)

    private fun `sendMemo stops immediately when pairing is required before send`() =
        runTest {
            val client = fakeClient()
            val orchestrator = createOrchestrator(client)
            coEvery { pairingConfig.isE2eEnabled() } returns true
            coEvery { pairingConfig.requiresPairingBeforeSend() } returns true

            val result = orchestrator.sendMemo(device, "memo", 10L, emptyMap())

            (result.isFailure).shouldBeTrue()
            orchestrator.transferState.value shouldBe ShareTransferState.Error(
                    error =
                        com.lomo.domain.model.ShareTransferError(
                            code = ShareTransferErrorCode.PAIRING_REQUIRED,
                            detail = null,
                            deviceName = null,
                            missingAttachmentCount = null,
                        ),
                )
            coVerify(exactly = 0) { client.prepare(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { client.transfer(any(), any(), any(), any(), any(), any(), any()) }
        }

    private fun `sendMemo moves to success when prepare and transfer both succeed`() =
        runTest {
            val client = fakeClient()
            val orchestrator = createOrchestrator(client)
            coEvery { pairingConfig.isE2eEnabled() } returns false
            coEvery { pairingConfig.requiresPairingBeforeSend() } returns false
            coEvery { pairingConfig.resolveDeviceName() } returns "Sender"
            coEvery {
                client.prepare(
                    device = device,
                    content = "memo",
                    timestamp = 11L,
                    senderName = "Sender",
                    attachments = emptyList(),
                    e2eEnabled = false,
                )
            } returns Result.success(LomoShareClient.PreparedSession(sessionToken = "approved", keyHex = null))
            coEvery {
                client.transfer(
                    device = device,
                    content = "memo",
                    timestamp = 11L,
                    sessionToken = "approved",
                    attachmentUris = emptyMap(),
                    e2eEnabled = false,
                    e2eKeyHex = null,
                )
            } returns true

            val result = orchestrator.sendMemo(device, "memo", 11L, emptyMap())

            (result.isSuccess).shouldBeTrue()
            orchestrator.transferState.value shouldBe ShareTransferState.Success(device.name)
            coVerify(exactly = 1) { client.prepare(device, "memo", 11L, "Sender", emptyList(), false) }
            coVerify(exactly = 1) {
                client.transfer(device, "memo", 11L, "approved", emptyMap(), false, null)
            }
        }

    private fun `sendMemo maps prepare failure to connection error state`() =
        runTest {
            val client = fakeClient()
            val orchestrator = createOrchestrator(client)
            coEvery { pairingConfig.isE2eEnabled() } returns false
            coEvery { pairingConfig.requiresPairingBeforeSend() } returns false
            coEvery { pairingConfig.resolveDeviceName() } returns "Sender"
            coEvery {
                client.prepare(any(), any(), any(), any(), any(), any())
            } returns Result.failure(IllegalStateException("network down"))

            val result = orchestrator.sendMemo(device, "memo", 12L, emptyMap())

            (result.isFailure).shouldBeTrue()
            val state = orchestrator.transferState.value as ShareTransferState.Error
            state.error.code shouldBe ShareTransferErrorCode.CONNECTION_FAILED
            state.error.detail shouldBe "network down"
        }

    private fun `sendMemo maps rejected prepare session to transfer rejected error`() =
        runTest {
            val client = fakeClient()
            val orchestrator = createOrchestrator(client)
            coEvery { pairingConfig.isE2eEnabled() } returns false
            coEvery { pairingConfig.requiresPairingBeforeSend() } returns false
            coEvery { pairingConfig.resolveDeviceName() } returns "Sender"
            coEvery {
                client.prepare(any(), any(), any(), any(), any(), any())
            } returns Result.success(LomoShareClient.PreparedSession(sessionToken = null, keyHex = null))

            val result = orchestrator.sendMemo(device, "memo", 13L, emptyMap())

            (result.isFailure).shouldBeTrue()
            val state = orchestrator.transferState.value as ShareTransferState.Error
            state.error.code shouldBe ShareTransferErrorCode.TRANSFER_REJECTED
            state.error.deviceName shouldBe device.name
        }

    private fun `sendMemo maps false transfer result to transfer failed error`() =
        runTest {
            val client = fakeClient()
            val orchestrator = createOrchestrator(client)
            coEvery { pairingConfig.isE2eEnabled() } returns true
            coEvery { pairingConfig.requiresPairingBeforeSend() } returns false
            coEvery { pairingConfig.resolveDeviceName() } returns "Sender"
            coEvery {
                client.prepare(any(), any(), any(), any(), any(), any())
            } returns Result.success(LomoShareClient.PreparedSession(sessionToken = "approved", keyHex = "key"))
            coEvery {
                client.transfer(any(), any(), any(), any(), any(), any(), any())
            } returns false

            val result = orchestrator.sendMemo(device, "memo", 14L, emptyMap())

            (result.isFailure).shouldBeTrue()
            val state = orchestrator.transferState.value as ShareTransferState.Error
            state.error.code shouldBe ShareTransferErrorCode.TRANSFER_FAILED
        }

    private fun createOrchestrator(client: LomoShareClient): ShareTransferOrchestrator {
        every { client.close() } just runs
        val orchestrator = ShareTransferOrchestrator(context, dataStore, pairingConfig)
        val field = ShareTransferOrchestrator::class.java.getDeclaredField("client")
        field.isAccessible = true
        (field.get(orchestrator) as LomoShareClient).close()
        field.set(orchestrator, client)
        return orchestrator
    }

    private fun fakeClient(): LomoShareClient = mockk(relaxed = true)
}
