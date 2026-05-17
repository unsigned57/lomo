package com.lomo.app.feature.share

import androidx.lifecycle.SavedStateHandle
import com.lomo.app.navigation.ShareRoutePayloadStore
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.IncomingShareState
import com.lomo.domain.model.LanShareStartupFailure
import com.lomo.domain.model.ShareAttachmentExtractionResult
import com.lomo.domain.model.ShareTransferState
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.usecase.ExtractShareAttachmentsUseCase
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

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
class ShareViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(testDispatcher))
    }

    private lateinit var shareService: LanShareService
    private lateinit var startupFailures: MutableSharedFlow<LanShareStartupFailure>
    private lateinit var extractShareAttachmentsUseCase: ExtractShareAttachmentsUseCase
    private lateinit var shareErrorPolicy: ShareErrorPolicy

    init {
        beforeTest {
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
    }

    init {
        afterTest {
            ShareRoutePayloadStore.clearForTest()
}
    }

    init {
        test("sendMemo exposes operationError when service returns failure") {
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

                (viewModel.memoContent) shouldBe ("memo-content")
                (viewModel.operationError.value) shouldBe ("send failed")
            }
        }
    }

    init {
        test("sendMemo falls back for technical error message") {
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

                (viewModel.operationError.value) shouldBe ("Failed to send memo")
            }
        }
    }

    init {
        test("sendMemo delegates attachment extraction to usecase and forwards result") {
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
        }
    }

    init {
        test("sendMemo emits pairing required event and skips send when pairing is required") {
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

                (viewModel.pairingRequiredEvent.value) shouldBe (1)
                verify(exactly = 0) { extractShareAttachmentsUseCase.invoke(any()) }
                coVerify(exactly = 0) { shareService.sendMemo(any(), any(), any(), any()) }
            }
        }
    }

    init {
        test("missing share payload exposes operationError") {
            runTest {
                val viewModel =
                    ShareViewModel(
                        lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                        extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                        shareErrorPolicy = shareErrorPolicy,
                        savedStateHandle = SavedStateHandle(),
                    )

                (viewModel.operationError.value) shouldBe ("Share content is unavailable. Please reopen the share page.")
            }
        }
    }

    init {
        test("legacy memo content is used when payload store misses key") {
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

                (viewModel.memoContent) shouldBe ("legacy-content")
                (viewModel.operationError.value) shouldBe null
            }
        }
    }

    init {
        test("init leaves lan share permission state unrequested and does not start discovery") {
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
                (viewModel.lanSharePermissionState.value) shouldBe (LanSharePermissionState.Unrequested)
                verify(exactly = 0) { shareService.startServices() }
                verify(exactly = 0) { shareService.startDiscovery() }
                (viewModel.operationError.value) shouldBe null
            }
        }
    }

    init {
        test("onLanShareNetworkPermissionsGranted reports operation error when discovery start fails") {
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
                (viewModel.lanSharePermissionState.value) shouldBe (LanSharePermissionState.Granted)
                (viewModel.operationError.value) shouldBe ("discovery failed")
            }
        }
    }

    init {
        test("onLanShareNetworkPermissionsGranted starts services and discovery once") {
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

                (viewModel.lanSharePermissionState.value) shouldBe (LanSharePermissionState.Granted)
                verify(exactly = 1) { shareService.startServices() }
                verify(exactly = 1) { shareService.startDiscovery() }
            }
        }
    }

    init {
        test("onLanShareNetworkPermissionsDenied marks denied state and skips startup") {
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

                (viewModel.lanSharePermissionState.value) shouldBe (LanSharePermissionState.Denied)
                verify(exactly = 0) { shareService.startServices() }
                verify(exactly = 0) { shareService.startDiscovery() }
            }
        }
    }

    init {
        test("lan share startup failure events surface operation error") {
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

                (viewModel.operationError.value) shouldBe ("Failed to start device discovery")
            }
        }
    }

    init {
        test("sendMemo with blank content keeps unavailable error and skips send pipeline") {
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

                (viewModel.operationError.value) shouldBe ("Share content is unavailable. Please reopen the share page.")
                coVerify(exactly = 0) { shareService.requiresPairingBeforeSend() }
                verify(exactly = 0) { extractShareAttachmentsUseCase.invoke(any()) }
                coVerify(exactly = 0) { shareService.sendMemo(any(), any(), any(), any()) }
            }
        }
    }

    init {
        test("sendMemo when lan share is disabled surfaces settings error and skips send pipeline") {
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

                (viewModel.operationError.value) shouldBe ("LAN share is disabled in settings.")
                coVerify(exactly = 0) { shareService.requiresPairingBeforeSend() }
                verify(exactly = 0) { extractShareAttachmentsUseCase.invoke(any()) }
                coVerify(exactly = 0) { shareService.sendMemo(any(), any(), any(), any()) }
            }
        }
    }

    init {
        test("updateLanSharePairingCode surfaces validation errors") {
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

                (viewModel.pairingCodeError.value) shouldBe ("Pairing code must be 6-64 characters")
            }
        }
    }

    init {
        test("updateLanSharePairingCode keeps pairingCodeError clear on cancellation") {
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

                (viewModel.pairingCodeError.value) shouldBe null
            }
        }
    }

    init {
        test("updateLanSharePairingCode success clears prior pairingCodeError") {
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
                (viewModel.pairingCodeError.value) shouldBe ("Pairing code must be 6-64 characters")

                viewModel.updateLanSharePairingCode("123456")
                testDispatcher.scheduler.advanceUntilIdle()
                (viewModel.pairingCodeError.value) shouldBe null
            }
        }
    }

    init {
        test("updateLanShareE2eEnabled surfaces operation error on failure") {
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

                (viewModel.operationError.value) shouldBe ("toggle failed")
            }
        }
    }

    init {
        test("clearLanSharePairingCode failure surfaces operation error") {
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

                (viewModel.operationError.value) shouldBe ("clear failed")
            }
        }
    }

    init {
        test("updateLanShareDeviceName surfaces operation error on failure") {
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

                (viewModel.operationError.value) shouldBe ("name invalid")
            }
        }
    }

    init {
        test("clear error actions reset operation and pairing error state") {
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
                (viewModel.operationError.value) shouldBe ("toggle failed")
                (viewModel.pairingCodeError.value) shouldBe ("Pairing code must be 6-64 characters")

                viewModel.clearOperationError()
                viewModel.clearPairingCodeError()

                (viewModel.operationError.value) shouldBe null
                (viewModel.pairingCodeError.value) shouldBe null
            }
        }
    }

    init {
        test("isTechnicalShareError returns policy result for user and technical messages") {
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

                ((viewModel.isTechnicalShareError("Network unavailable"))) shouldBe false
                ((viewModel.isTechnicalShareError("java.net.SocketTimeoutException: timeout\n\tat okhttp3.RealCall"))) shouldBe true
            }
        }
    }

    init {
        test("resetTransferState failure surfaces operation error") {
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

                (viewModel.operationError.value) shouldBe ("reset failed")
            }
        }
    }

}
