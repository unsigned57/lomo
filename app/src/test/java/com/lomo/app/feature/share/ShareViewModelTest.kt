package com.lomo.app.feature.share

import androidx.lifecycle.SavedStateHandle
import com.lomo.app.navigation.ShareRoutePayloadStore
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.IncomingShareState
import com.lomo.domain.model.LanShareStartupFailure
import com.lomo.domain.model.ShareAttachmentExtractionResult
import com.lomo.domain.model.ShareTransferState
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.usecase.ExtractShareAttachmentsUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: ShareViewModel
 * - Behavior focus: permission-gated LAN share startup, send preconditions, attachment extraction
 *   wiring, and user-visible error mapping for LAN share flows.
 * - Observable outcomes: lanSharePermissionState, operationError and pairingRequiredEvent state,
 *   plus send payload forwarding to LanShareService.
 * - Red phase: Fails before the fix because ShareViewModel eagerly starts discovery in init,
 *   exposes no permission state, and cannot consume LAN-share startup failure events.
 * - Excludes: NSD discovery internals, transport implementation, and Compose rendering.
 */
/*
 * Test Change Justification:
 * - Reason category: bug fix contract update.
 * - Old behavior/assertion being replaced: ShareViewModel init immediately started discovery and
 *   surfaced startup failure from the eager init path.
 * - Why old assertion is no longer correct: Android 16 discovery must start only after runtime
 *   permission grant, so init can no longer be the startup trigger.
 * - Coverage preserved by: companion tests now lock init non-startup, permission-granted startup,
 *   denied permission state, and propagated startup failure visibility.
 * - Why this is not fitting the test to the implementation: the new assertions match the
 *   user-visible permission gate required to make discovery recover correctly on Android 16.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var shareService: LanShareService
    private lateinit var startupFailures: MutableSharedFlow<LanShareStartupFailure>
    private lateinit var extractShareAttachmentsUseCase: ExtractShareAttachmentsUseCase
    private lateinit var shareErrorPolicy: ShareErrorPolicy

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        shareService = mockk(relaxed = true)
        startupFailures = MutableSharedFlow(extraBufferCapacity = 1)
        extractShareAttachmentsUseCase = mockk(relaxed = true)
        shareErrorPolicy = ShareErrorPolicy()
        every { shareService.discoveredDevices } returns MutableStateFlow(emptyList())
        every { shareService.incomingShare } returns MutableStateFlow(IncomingShareState.None)
        every { shareService.transferState } returns MutableStateFlow(ShareTransferState.Idle)
        every { shareService.lanShareStartupFailures } returns startupFailures
        every { shareService.lanSharePairingCode } returns MutableStateFlow("")
        every { shareService.lanShareEnabled } returns flowOf(true)
        every { shareService.lanShareE2eEnabled } returns flowOf(true)
        every { shareService.lanSharePairingConfigured } returns flowOf(true)
        every { shareService.lanShareDeviceName } returns flowOf("Local")
        every { extractShareAttachmentsUseCase.invoke(any()) } returns
            ShareAttachmentExtractionResult(
                localAttachmentPaths = emptyList(),
                attachmentUris = emptyMap(),
            )

        coEvery { shareService.requiresPairingBeforeSend() } returns false
        coEvery { shareService.sendMemo(any(), any(), any(), any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        ShareRoutePayloadStore.clearForTest()
        Dispatchers.resetMain()
    }

    @Test
    fun `sendMemo exposes operationError when service returns failure`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            coEvery { shareService.sendMemo(any(), any(), any(), any()) } returns
                Result.failure(IllegalStateException("send failed"))

            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.sendMemo(
                DiscoveredDevice(
                    name = "Peer",
                    host = "192.168.1.2",
                    port = 1080,
                ),
            )
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("memo-content", viewModel.memoContent)
            assertEquals("send failed", viewModel.operationError.value)
        }

    @Test
    fun `sendMemo falls back for technical error message`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            coEvery { shareService.sendMemo(any(), any(), any(), any()) } returns
                Result.failure(IllegalStateException("java.net.SocketTimeoutException: timeout\n\tat okhttp3.RealCall"))

            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.sendMemo(
                DiscoveredDevice(
                    name = "Peer",
                    host = "192.168.1.2",
                    port = 1080,
                ),
            )
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Failed to send memo", viewModel.operationError.value)
        }

    @Test
    fun `sendMemo delegates attachment extraction to usecase and forwards result`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            val device =
                DiscoveredDevice(
                    name = "Peer",
                    host = "192.168.1.2",
                    port = 1080,
                )
            val expectedAttachmentUris = mapOf("images/photo.png" to "content://images/photo.png")
            every { extractShareAttachmentsUseCase.invoke("memo-content") } returns
                ShareAttachmentExtractionResult(
                    localAttachmentPaths = expectedAttachmentUris.keys.toList(),
                    attachmentUris = expectedAttachmentUris,
                )

            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.sendMemo(device)
            testDispatcher.scheduler.advanceUntilIdle()

            verify(exactly = 1) { extractShareAttachmentsUseCase.invoke("memo-content") }
            coVerify(exactly = 1) {
                shareService.sendMemo(
                    device = device,
                    content = "memo-content",
                    timestamp = 123L,
                    attachmentUris = expectedAttachmentUris,
                )
            }
        }

    @Test
    fun `sendMemo emits pairing required event and skips send when pairing is required`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            val device =
                DiscoveredDevice(
                    name = "Peer",
                    host = "192.168.1.2",
                    port = 1080,
                )
            coEvery { shareService.requiresPairingBeforeSend() } returns true
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.sendMemo(device)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, viewModel.pairingRequiredEvent.value)
            verify(exactly = 0) { extractShareAttachmentsUseCase.invoke(any()) }
            coVerify(exactly = 0) { shareService.sendMemo(any(), any(), any(), any()) }
        }

    @Test
    fun `missing share payload exposes operationError`() =
        runTest {
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle = SavedStateHandle(),
                )

            assertEquals(
                "Share content is unavailable. Please reopen the share page.",
                viewModel.operationError.value,
            )
        }

    @Test
    fun `legacy memo content is used when payload store misses key`() =
        runTest {
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to "missing-key",
                                "memoContent" to "legacy-content",
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            assertEquals("legacy-content", viewModel.memoContent)
            assertNull(viewModel.operationError.value)
        }

    @Test
    fun `init leaves lan share permission state unrequested and does not start discovery`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")

            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(LanSharePermissionState.Unrequested, viewModel.lanSharePermissionState.value)
            verify(exactly = 0) { shareService.startServices() }
            verify(exactly = 0) { shareService.startDiscovery() }
            assertNull(viewModel.operationError.value)
        }

    @Test
    fun `onLanShareNetworkPermissionsGranted reports operation error when discovery start fails`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            every { shareService.startDiscovery() } throws IllegalStateException("discovery failed")

            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.onLanShareNetworkPermissionsGranted()
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(LanSharePermissionState.Granted, viewModel.lanSharePermissionState.value)
            assertEquals("discovery failed", viewModel.operationError.value)
        }

    @Test
    fun `onLanShareNetworkPermissionsGranted starts services and discovery once`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")

            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.onLanShareNetworkPermissionsGranted()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(LanSharePermissionState.Granted, viewModel.lanSharePermissionState.value)
            verify(exactly = 1) { shareService.startServices() }
            verify(exactly = 1) { shareService.startDiscovery() }
        }

    @Test
    fun `onLanShareNetworkPermissionsDenied marks denied state and skips startup`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")

            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.onLanShareNetworkPermissionsDenied()

            assertEquals(LanSharePermissionState.Denied, viewModel.lanSharePermissionState.value)
            verify(exactly = 0) { shareService.startServices() }
            verify(exactly = 0) { shareService.startDiscovery() }
        }

    @Test
    fun `lan share startup failure events surface operation error`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")

            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            testDispatcher.scheduler.advanceUntilIdle()
            startupFailures.tryEmit(LanShareStartupFailure.DiscoveryStartFailed)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Failed to start device discovery", viewModel.operationError.value)
        }

    @Test
    fun `sendMemo with blank content keeps unavailable error and skips send pipeline`() =
        runTest {
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle = SavedStateHandle(),
                )

            viewModel.sendMemo(
                DiscoveredDevice(
                    name = "Peer",
                    host = "192.168.1.2",
                    port = 1080,
                ),
            )
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "Share content is unavailable. Please reopen the share page.",
                viewModel.operationError.value,
            )
            coVerify(exactly = 0) { shareService.requiresPairingBeforeSend() }
            verify(exactly = 0) { extractShareAttachmentsUseCase.invoke(any()) }
            coVerify(exactly = 0) { shareService.sendMemo(any(), any(), any(), any()) }
        }

    @Test
    fun `sendMemo when lan share is disabled surfaces settings error and skips send pipeline`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            val device =
                DiscoveredDevice(
                    name = "Peer",
                    host = "192.168.1.2",
                    port = 1080,
                )
            every { shareService.lanShareEnabled } returns flowOf(false)
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.sendMemo(device)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("LAN share is disabled in settings.", viewModel.operationError.value)
            coVerify(exactly = 0) { shareService.requiresPairingBeforeSend() }
            verify(exactly = 0) { extractShareAttachmentsUseCase.invoke(any()) }
            coVerify(exactly = 0) { shareService.sendMemo(any(), any(), any(), any()) }
        }

    @Test
    fun `updateLanSharePairingCode surfaces validation errors`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            coEvery { shareService.setLanSharePairingCode(any()) } throws IllegalArgumentException("invalid code")
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.updateLanSharePairingCode("bad")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Pairing code must be 6-64 characters", viewModel.pairingCodeError.value)
        }

    @Test
    fun `updateLanSharePairingCode keeps pairingCodeError clear on cancellation`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            coEvery { shareService.setLanSharePairingCode(any()) } throws CancellationException("cancelled")
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.updateLanSharePairingCode("123456")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.pairingCodeError.value)
        }

    @Test
    fun `updateLanSharePairingCode success clears prior pairingCodeError`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            coEvery { shareService.setLanSharePairingCode("bad") } throws IllegalArgumentException("invalid code")
            coEvery { shareService.setLanSharePairingCode("123456") } returns Unit
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.updateLanSharePairingCode("bad")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("Pairing code must be 6-64 characters", viewModel.pairingCodeError.value)

            viewModel.updateLanSharePairingCode("123456")
            testDispatcher.scheduler.advanceUntilIdle()
            assertNull(viewModel.pairingCodeError.value)
        }

    @Test
    fun `updateLanShareE2eEnabled surfaces operation error on failure`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            coEvery { shareService.setLanShareE2eEnabled(false) } throws IllegalStateException("toggle failed")
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.updateLanShareE2eEnabled(false)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("toggle failed", viewModel.operationError.value)
        }

    @Test
    fun `clearLanSharePairingCode failure surfaces operation error`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            coEvery { shareService.clearLanSharePairingCode() } throws IllegalStateException("clear failed")
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.clearLanSharePairingCode()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("clear failed", viewModel.operationError.value)
        }

    @Test
    fun `updateLanShareDeviceName surfaces operation error on failure`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            coEvery { shareService.setLanShareDeviceName("bad") } throws IllegalArgumentException("name invalid")
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.updateLanShareDeviceName("bad")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("name invalid", viewModel.operationError.value)
        }

    @Test
    fun `clear error actions reset operation and pairing error state`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            coEvery { shareService.setLanShareE2eEnabled(false) } throws IllegalStateException("toggle failed")
            coEvery { shareService.setLanSharePairingCode("bad") } throws IllegalArgumentException("invalid code")
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.updateLanShareE2eEnabled(false)
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.updateLanSharePairingCode("bad")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("toggle failed", viewModel.operationError.value)
            assertEquals("Pairing code must be 6-64 characters", viewModel.pairingCodeError.value)

            viewModel.clearOperationError()
            viewModel.clearPairingCodeError()

            assertNull(viewModel.operationError.value)
            assertNull(viewModel.pairingCodeError.value)
        }

    @Test
    fun `isTechnicalShareError returns policy result for user and technical messages`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            assertFalse(viewModel.isTechnicalShareError("Network unavailable"))
            assertTrue(viewModel.isTechnicalShareError("java.net.SocketTimeoutException: timeout\n\tat okhttp3.RealCall"))
        }

    @Test
    fun `resetTransferState failure surfaces operation error`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            every { shareService.resetTransferState() } throws IllegalStateException("reset failed")
            val viewModel =
                ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle =
                        SavedStateHandle(
                            mapOf(
                                "payloadKey" to payloadKey,
                                "memoTimestamp" to 123L,
                            ),
                        ),
                )

            viewModel.resetTransferState()

            assertEquals("reset failed", viewModel.operationError.value)
        }
}
