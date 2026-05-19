package com.lomo.app.feature.share

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


import androidx.lifecycle.SavedStateHandle
import com.lomo.app.navigation.ShareRoutePayloadStore
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeLanShareService
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanShareStartupFailure
import com.lomo.domain.usecase.ExtractShareAttachmentsUseCase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()
    private val shareService = FakeLanShareService()
    private val extractShareAttachmentsUseCase = ExtractShareAttachmentsUseCase()
    private val shareErrorPolicy = ShareErrorPolicy()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        afterTest {
            ShareRoutePayloadStore.clearForTest()
        }

        test("sendMemo exposes operationError when service returns failure") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                shareService.sendMemoResult = Result.failure(IllegalStateException("send failed"))

                val viewModel = createViewModel(payloadKey)

                viewModel.sendMemo(
                    DiscoveredDevice(
                        name = "Peer",
                        host = "192.168.1.2",
                        port = 1080,
                    ),
                )
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.memoContent shouldBe "memo-content"
                viewModel.operationError.value shouldBe "send failed"
            }
        }

        test("sendMemo falls back for technical error message") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                shareService.sendMemoResult = Result.failure(
                    IllegalStateException("java.net.SocketTimeoutException: timeout\n\tat okhttp3.RealCall")
                )

                val viewModel = createViewModel(payloadKey)

                viewModel.sendMemo(
                    DiscoveredDevice(
                        name = "Peer",
                        host = "192.168.1.2",
                        port = 1080,
                    ),
                )
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.operationError.value shouldBe "Failed to send memo"
            }
        }

        test("sendMemo delegates attachment extraction to usecase and forwards result") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content with photo ![photo](images/photo.png)")
                val device = DiscoveredDevice(
                    name = "Peer",
                    host = "192.168.1.2",
                    port = 1080,
                )

                val viewModel = createViewModel(payloadKey)

                viewModel.sendMemo(device)
                testDispatcher.scheduler.advanceUntilIdle()

                shareService.sentMemos shouldBe listOf(
                    FakeLanShareService.SentMemo(
                        device = device,
                        content = "memo-content with photo ![photo](images/photo.png)",
                        timestamp = 123L,
                        attachmentUris = mapOf("images/photo.png" to "images/photo.png"),
                    )
                )
            }
        }

        test("sendMemo emits pairing required event and skips send when pairing is required") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                val device = DiscoveredDevice(
                    name = "Peer",
                    host = "192.168.1.2",
                    port = 1080,
                )
                shareService.requiresPairingValue = true
                val viewModel = createViewModel(payloadKey)

                viewModel.sendMemo(device)
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.pairingRequiredEvent.value shouldBe 1
                shareService.sentMemos.isEmpty() shouldBe true
            }
        }

        test("missing share payload exposes operationError") {
            runTest {
                val viewModel = ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle = SavedStateHandle(),
                )

                viewModel.operationError.value shouldBe "Share content is unavailable. Please reopen the share page."
            }
        }

        test("legacy memo content is used when payload store misses key") {
            runTest {
                val viewModel = ShareViewModel(
                    lanShareUiCoordinator = LanShareUiCoordinator(shareService),
                    extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
                    shareErrorPolicy = shareErrorPolicy,
                    savedStateHandle = SavedStateHandle(
                        mapOf(
                            "payloadKey" to "missing-key",
                            "memoContent" to "legacy-content",
                            "memoTimestamp" to 123L,
                        ),
                    ),
                )

                viewModel.memoContent shouldBe "legacy-content"
                viewModel.operationError.value shouldBe null
            }
        }

        test("init leaves lan share permission state unrequested and does not start discovery") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")

                val viewModel = createViewModel(payloadKey)

                testDispatcher.scheduler.advanceUntilIdle()
                viewModel.lanSharePermissionState.value shouldBe LanSharePermissionState.Unrequested
                shareService.startServicesCalledCount shouldBe 0
                shareService.startDiscoveryCalledCount shouldBe 0
                viewModel.operationError.value shouldBe null
            }
        }

        test("onLanShareNetworkPermissionsGranted reports operation error when discovery start fails") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                shareService.startDiscoveryError = IllegalStateException("discovery failed")

                val viewModel = createViewModel(payloadKey)

                viewModel.onLanShareNetworkPermissionsGranted()
                testDispatcher.scheduler.advanceUntilIdle()
                viewModel.lanSharePermissionState.value shouldBe LanSharePermissionState.Granted
                viewModel.operationError.value shouldBe "discovery failed"
            }
        }

        test("onLanShareNetworkPermissionsGranted starts services and discovery once") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")

                val viewModel = createViewModel(payloadKey)

                viewModel.onLanShareNetworkPermissionsGranted()
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.lanSharePermissionState.value shouldBe LanSharePermissionState.Granted
                shareService.startServicesCalledCount shouldBe 1
                shareService.startDiscoveryCalledCount shouldBe 1
            }
        }

        test("onLanShareNetworkPermissionsDenied marks denied state and skips startup") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")

                val viewModel = createViewModel(payloadKey)

                viewModel.onLanShareNetworkPermissionsDenied()

                viewModel.lanSharePermissionState.value shouldBe LanSharePermissionState.Denied
                shareService.startServicesCalledCount shouldBe 0
                shareService.startDiscoveryCalledCount shouldBe 0
            }
        }

        test("lan share startup failure events surface operation error") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")

                val viewModel = createViewModel(payloadKey)

                testDispatcher.scheduler.advanceUntilIdle()
                shareService.lanShareStartupFailures.tryEmit(LanShareStartupFailure.DiscoveryStartFailed)
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.operationError.value shouldBe "Failed to start device discovery"
            }
        }

        test("sendMemo with blank content keeps unavailable error and skips send pipeline") {
            runTest {
                val viewModel = ShareViewModel(
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

                viewModel.operationError.value shouldBe "Share content is unavailable. Please reopen the share page."
                shareService.sentMemos.isEmpty() shouldBe true
            }
        }

        test("sendMemo when lan share is disabled surfaces settings error and skips send pipeline") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                val device = DiscoveredDevice(
                    name = "Peer",
                    host = "192.168.1.2",
                    port = 1080,
                )
                shareService.lanShareEnabledValue = false
                val viewModel = createViewModel(payloadKey)

                viewModel.sendMemo(device)
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.operationError.value shouldBe "LAN share is disabled in settings."
                shareService.sentMemos.isEmpty() shouldBe true
            }
        }

        test("updateLanSharePairingCode surfaces validation errors") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                shareService.setLanSharePairingCodeError = IllegalArgumentException("invalid code")
                val viewModel = createViewModel(payloadKey)

                viewModel.updateLanSharePairingCode("bad")
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.pairingCodeError.value shouldBe "Pairing code must be 6-64 characters"
            }
        }

        test("updateLanSharePairingCode keeps pairingCodeError clear on cancellation") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                shareService.setLanSharePairingCodeError = CancellationException("cancelled")
                val viewModel = createViewModel(payloadKey)

                viewModel.updateLanSharePairingCode("123456")
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.pairingCodeError.value shouldBe null
            }
        }

        test("updateLanSharePairingCode success clears prior pairingCodeError") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                shareService.setLanSharePairingCodeError = IllegalArgumentException("invalid code")
                val viewModel = createViewModel(payloadKey)

                viewModel.updateLanSharePairingCode("bad")
                testDispatcher.scheduler.advanceUntilIdle()
                viewModel.pairingCodeError.value shouldBe "Pairing code must be 6-64 characters"

                shareService.setLanSharePairingCodeError = null
                viewModel.updateLanSharePairingCode("123456")
                testDispatcher.scheduler.advanceUntilIdle()
                viewModel.pairingCodeError.value shouldBe null
            }
        }

        test("updateLanShareE2eEnabled surfaces operation error on failure") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                shareService.setLanShareE2eEnabledError = IllegalStateException("toggle failed")
                val viewModel = createViewModel(payloadKey)

                viewModel.updateLanShareE2eEnabled(false)
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.operationError.value shouldBe "toggle failed"
            }
        }

        test("clearLanSharePairingCode failure surfaces operation error") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                shareService.clearLanSharePairingCodeError = IllegalStateException("clear failed")
                val viewModel = createViewModel(payloadKey)

                viewModel.clearLanSharePairingCode()
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.operationError.value shouldBe "clear failed"
            }
        }

        test("updateLanShareDeviceName surfaces operation error on failure") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                shareService.setLanShareDeviceNameError = IllegalArgumentException("name invalid")
                val viewModel = createViewModel(payloadKey)

                viewModel.updateLanShareDeviceName("bad")
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.operationError.value shouldBe "name invalid"
            }
        }

        test("clear error actions reset operation and pairing error state") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                shareService.setLanShareE2eEnabledError = IllegalStateException("toggle failed")
                shareService.setLanSharePairingCodeError = IllegalArgumentException("invalid code")
                val viewModel = createViewModel(payloadKey)

                viewModel.updateLanShareE2eEnabled(false)
                testDispatcher.scheduler.advanceUntilIdle()
                viewModel.updateLanSharePairingCode("bad")
                testDispatcher.scheduler.advanceUntilIdle()
                viewModel.operationError.value shouldBe "toggle failed"
                viewModel.pairingCodeError.value shouldBe "Pairing code must be 6-64 characters"

                viewModel.clearOperationError()
                viewModel.clearPairingCodeError()

                viewModel.operationError.value shouldBe null
                viewModel.pairingCodeError.value shouldBe null
            }
        }

        test("isTechnicalShareError returns policy result for user and technical messages") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                val viewModel = createViewModel(payloadKey)

                viewModel.isTechnicalShareError("Network unavailable") shouldBe false
                viewModel.isTechnicalShareError("java.net.SocketTimeoutException: timeout\n\tat okhttp3.RealCall") shouldBe true
            }
        }

        test("resetTransferState failure surfaces operation error") {
            runTest {
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")
                shareService.resetTransferStateError = IllegalStateException("reset failed")
                val viewModel = createViewModel(payloadKey)

                viewModel.resetTransferState()

                viewModel.operationError.value shouldBe "reset failed"
            }
        }
    }

    private fun createViewModel(payloadKey: String): ShareViewModel =
        ShareViewModel(
            lanShareUiCoordinator = LanShareUiCoordinator(shareService),
            extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
            shareErrorPolicy = shareErrorPolicy,
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "payloadKey" to payloadKey,
                    "memoTimestamp" to 123L,
                ),
            ),
        )
}
