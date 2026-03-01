package com.lomo.app.feature.share

import androidx.lifecycle.SavedStateHandle
import com.lomo.app.navigation.ShareRoutePayloadStore
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.IncomingShareState
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var shareService: LanShareService
    private lateinit var extractShareAttachmentsUseCase: ExtractShareAttachmentsUseCase
    private lateinit var shareErrorPolicy: ShareErrorPolicy

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        shareService = mockk(relaxed = true)
        extractShareAttachmentsUseCase = mockk(relaxed = true)
        shareErrorPolicy = ShareErrorPolicy()
        every { shareService.discoveredDevices } returns MutableStateFlow(emptyList())
        every { shareService.incomingShare } returns MutableStateFlow(IncomingShareState.None)
        every { shareService.transferState } returns MutableStateFlow(ShareTransferState.Idle)
        every { shareService.lanSharePairingCode } returns MutableStateFlow("")
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
                    shareServiceManager = shareService,
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
                    shareServiceManager = shareService,
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
                    shareServiceManager = shareService,
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
    fun `missing share payload exposes operationError`() =
        runTest {
            val viewModel =
                ShareViewModel(
                    shareServiceManager = shareService,
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
    fun `updateLanSharePairingCode surfaces validation errors`() =
        runTest {
            val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
            coEvery { shareService.setLanSharePairingCode(any()) } throws IllegalArgumentException("invalid code")
            val viewModel =
                ShareViewModel(
                    shareServiceManager = shareService,
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
                    shareServiceManager = shareService,
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
}
