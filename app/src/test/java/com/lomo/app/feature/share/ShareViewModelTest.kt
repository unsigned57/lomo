/**
 * Behavior Contract:
 * - Unit under test: ShareViewModel.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: restore share memo content and coordinate LAN share operations.
 *
 * Scenarios:
 * - Given a share route key survives while process-memory payload state is unavailable, when a
 *   configured route payload cache still holds the memo content, then a new ViewModel restores the
 *   content from that key without saving the full memo body in route arguments.
 * - Given share content is missing, when the ViewModel initializes or send is requested, then a
 *   user-facing unavailable-content error is exposed and sending is skipped.
 * - Given LAN share startup, pairing, settings, or transfer operations fail, when the user invokes the
 *   operation, then observable error or pairing state is updated without hiding cancellation.
 * - Given attachment references exist in memo content, when sending succeeds, then extracted attachment
 *   URIs are forwarded with the memo payload.
 *
 * Observable outcomes:
 * - memoContent, operationError, pairingCodeError, lanSharePermissionState, pairingRequiredEvent, and
 *   fake LAN service sent memo records.
 *
 * TDD proof:
 * - The route-key cache restoration scenario fails before implementation because ViewModels only
 *   consume the process-memory ShareRoutePayloadStore and lose content once that memory store is
 *   unavailable.
 *
 * Excludes:
 * - Compose rendering, NavHost graph serialization, network transport internals, and attachment parser
 *   internals.
 */

package com.lomo.app.feature.share

import androidx.lifecycle.SavedStateHandle
import com.lomo.app.navigation.ShareRoutePayloadStore
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeLanShareService
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanShareStartupFailure
import com.lomo.domain.usecase.ExtractShareAttachmentsUseCase
import io.kotest.matchers.shouldBe
import java.nio.file.Files
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

        test("given route key survives process memory loss when cache is configured then ViewModel restores memo content") {
            runTest {
                val cacheDir = Files.createTempDirectory("share-view-model-payload-cache-test").toFile()
                ShareRoutePayloadStore.configurePersistentCacheForTest(cacheDir)
                val payloadKey = ShareRoutePayloadStore.putMemoContent("memo-content")

                // Simulates a restored navigation route after process-local singleton state is gone.
                ShareRoutePayloadStore.clearMemoryForTest()
                val restoredViewModel = createViewModel(payloadKey)

                restoredViewModel.memoContent shouldBe "memo-content"
                restoredViewModel.operationError.value shouldBe null
                restoredViewModel.memoTimestamp shouldBe 123L
                cacheDir.deleteRecursively()
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
        createViewModel(
            SavedStateHandle(
                mapOf(
                    "payloadKey" to payloadKey,
                    "memoTimestamp" to 123L,
                ),
            ),
        )

    private fun createViewModel(savedStateHandle: SavedStateHandle): ShareViewModel =
        ShareViewModel(
            lanShareUiCoordinator = LanShareUiCoordinator(shareService),
            extractShareAttachmentsUseCase = extractShareAttachmentsUseCase,
            shareErrorPolicy = shareErrorPolicy,
            savedStateHandle = savedStateHandle,
        )
}
