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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: ShareTransferOrchestrator
 * - Behavior focus: send state transitions, prepare/transfer failure classification, and pairing gate enforcement.
 * - Observable outcomes: Result success/failure, exposed ShareTransferState, and prepare/transfer collaborator calls.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: HTTP transport internals, attachment URI discovery, and UI rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareTransferOrchestratorTest {
    private val context = mockk<Context>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)
    private val pairingConfig = mockk<SharePairingConfig>(relaxed = true)
    private val device = DiscoveredDevice(name = "Pixel", host = "127.0.0.1", port = 8080)

    @Test
    fun `sendMemo stops immediately when pairing is required before send`() =
        runTest {
            val client = fakeClient()
            val orchestrator = createOrchestrator(client)
            coEvery { pairingConfig.isE2eEnabled() } returns true
            coEvery { pairingConfig.requiresPairingBeforeSend() } returns true

            val result = orchestrator.sendMemo(device, "memo", 10L, emptyMap())

            assertTrue(result.isFailure)
            assertEquals(
                ShareTransferState.Error(
                    error =
                        com.lomo.domain.model.ShareTransferError(
                            code = ShareTransferErrorCode.PAIRING_REQUIRED,
                            detail = null,
                            deviceName = null,
                            missingAttachmentCount = null,
                        ),
                ),
                orchestrator.transferState.value,
            )
            coVerify(exactly = 0) { client.prepare(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { client.transfer(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `sendMemo moves to success when prepare and transfer both succeed`() =
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

            assertTrue(result.isSuccess)
            assertEquals(ShareTransferState.Success(device.name), orchestrator.transferState.value)
            coVerify(exactly = 1) { client.prepare(device, "memo", 11L, "Sender", emptyList(), false) }
            coVerify(exactly = 1) {
                client.transfer(device, "memo", 11L, "approved", emptyMap(), false, null)
            }
        }

    @Test
    fun `sendMemo maps prepare failure to connection error state`() =
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

            assertTrue(result.isFailure)
            val state = orchestrator.transferState.value as ShareTransferState.Error
            assertEquals(ShareTransferErrorCode.CONNECTION_FAILED, state.error.code)
            assertEquals("network down", state.error.detail)
        }

    @Test
    fun `sendMemo maps rejected prepare session to transfer rejected error`() =
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

            assertTrue(result.isFailure)
            val state = orchestrator.transferState.value as ShareTransferState.Error
            assertEquals(ShareTransferErrorCode.TRANSFER_REJECTED, state.error.code)
            assertEquals(device.name, state.error.deviceName)
        }

    @Test
    fun `sendMemo maps false transfer result to transfer failed error`() =
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

            assertTrue(result.isFailure)
            val state = orchestrator.transferState.value as ShareTransferState.Error
            assertEquals(ShareTransferErrorCode.TRANSFER_FAILED, state.error.code)
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
