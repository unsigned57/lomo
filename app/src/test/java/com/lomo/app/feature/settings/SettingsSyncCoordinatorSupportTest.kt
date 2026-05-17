package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.GitSyncErrorCode
import io.kotest.assertions.fail
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: shared settings coordinator helpers in SettingsSyncCoordinatorSupport.kt
 *
 * Scenario matrix:
 * - Happy: standard happy path for SettingsSyncCoordinatorSupportTest.
 * - Boundary: boundary and edge cases for SettingsSyncCoordinatorSupportTest.
 * - Failure: failure and error scenarios for SettingsSyncCoordinatorSupportTest.
 * - Must-not-happen: invariants are never violated for SettingsSyncCoordinatorSupportTest.
 * - Behavior focus: shared helpers should centralize settings error handling and connection-test state transitions
 *   for Git/WebDAV/S3 coordinators without altering their user-visible contract.
 * - Observable outcomes: mapped SettingsOperationError values, connection-test state transitions, and cancellation
 *   propagation.
 * - Red phase: Fails before the fix because the shared coordinator helper layer does not exist yet.
 * - Excludes: use-case internals, coroutine scope wiring, and Compose rendering.
 */
class SettingsSyncCoordinatorSupportTest : AppFunSpec() {
    init {
        test("runSettingsOperation returns specific mapped error before fallback message") {
            runTest {
                val result =
                    runSettingsOperation(
                        fallbackMessage = "fallback",
                        specificError = { SettingsOperationError.GitSync(GitSyncErrorCode.UNKNOWN, it.message) },
                        action = { throw IllegalStateException("boom") },
                    )

                (result) shouldBe (SettingsOperationError.GitSync(
                        code = GitSyncErrorCode.UNKNOWN,
                        detail = "boom",
                    ))
            }
        }
    }

    init {
        test("runSettingsOperation rethrows cancellation") {
            runTest {
                try {
                    runSettingsOperation(
                        fallbackMessage = "fallback",
                        specificError = { null },
                        action = { throw CancellationException("stop") },
                    )
                    fail("Expected CancellationException")
                } catch (error: CancellationException) {
                    (error.message) shouldBe ("stop")
                }
            }
        }
    }

    init {
        test("runConnectionTest updates testing and success states") {
            runTest {
                val state = MutableStateFlow<SettingsGitConnectionTestState>(SettingsGitConnectionTestState.Idle)

                runConnectionTest(
                    state = state,
                    testingState = SettingsGitConnectionTestState.Testing,
                    execute = { "connected" },
                    mapSuccess = { SettingsGitConnectionTestState.Success(it) },
                    mapFailure = { SettingsGitConnectionTestState.Error(GitSyncErrorCode.UNKNOWN, it.message) },
                )

                (state.value) shouldBe (SettingsGitConnectionTestState.Success("connected"))
            }
        }
    }

    init {
        test("runConnectionTest captures failure state without returning operation error") {
            runTest {
                val state = MutableStateFlow<SettingsGitConnectionTestState>(SettingsGitConnectionTestState.Idle)

                val result =
                    runConnectionTest(
                        state = state,
                        testingState = SettingsGitConnectionTestState.Testing,
                        execute = { throw IllegalStateException("network down") },
                        mapSuccess = { SettingsGitConnectionTestState.Success(it) },
                        mapFailure = { SettingsGitConnectionTestState.Error(GitSyncErrorCode.UNKNOWN, it.message) },
                    )

                (result) shouldBe (null)
                (state.value) shouldBe (SettingsGitConnectionTestState.Error(
                        code = GitSyncErrorCode.UNKNOWN,
                        detail = "network down",
                    ))
            }
        }
    }

}
