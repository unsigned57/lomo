package com.lomo.app.feature.share

import androidx.lifecycle.SavedStateHandle
import com.lomo.app.navigation.ShareRoutePayloadStore
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.IncomingShareState
import com.lomo.domain.model.ShareTransferState
import com.lomo.domain.repository.LanShareService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var shareService: LanShareService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        shareService = mockk(relaxed = true)
        every { shareService.discoveredDevices } returns MutableStateFlow(emptyList())
        every { shareService.incomingShare } returns MutableStateFlow(IncomingShareState.None)
        every { shareService.transferState } returns MutableStateFlow(ShareTransferState.Idle)
        every { shareService.lanSharePairingCode } returns MutableStateFlow("")
        every { shareService.lanShareE2eEnabled } returns flowOf(true)
        every { shareService.lanSharePairingConfigured } returns flowOf(true)
        every { shareService.lanShareDeviceName } returns flowOf("Local")

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
    fun `missing share payload exposes operationError`() =
        runTest {
            val viewModel =
                ShareViewModel(
                    shareServiceManager = shareService,
                    savedStateHandle = SavedStateHandle(),
                )

            assertEquals(
                "Share content is unavailable. Please reopen the share page.",
                viewModel.operationError.value,
            )
        }
}
