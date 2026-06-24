package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.GitSyncErrorCode
import io.kotest.assertions.fail
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: shared settings operation helpers in SettingsSyncCoordinatorSupport.kt
 * - Owning layer: app/settings
 * - Priority tier: P2
 * - Capability: centralize cancellation-safe settings operation error mapping.
 *
 * Scenarios:
 * - Given a provider-specific mapper returns an error, when the operation fails, then the specific
 *   error is returned before fallback text.
 * - Given cancellation occurs, when the operation is wrapped, then cancellation is rethrown.
 *
 * Observable outcomes:
 * - Returned SettingsOperationError value and propagated CancellationException.
 *
 * TDD proof:
 * - RED: earlier focused app tests failed before shared settings operation helpers existed.
 *
 * Excludes:
 * - Provider connection-test lifecycle, which is owned by ProviderSettingsController.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
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
}
