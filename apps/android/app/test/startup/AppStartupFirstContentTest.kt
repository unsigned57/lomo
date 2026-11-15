/*
 * Test Change Justification:
 * Reason category: mechanical migration + deferred startup task addition (WIDGET_PREVIEWS).
 * Old behavior/assertion being replaced: explicit Unit expressions in success callbacks; zipped
 * five StartupTaskId entries.
 * Why old assertion is no longer correct: Kotlin 2.4 diagnoses these expressions as unused, and
 * StartupTaskId now includes WIDGET_PREVIEWS for post-first-frame API 35 preview publishing.
 * Coverage preserved by: the same success callbacks, side effects, state, first-frame defer
 * guarantees and expected values across all StartupTaskId entries.
 * Why this is not fitting the test to the implementation: validates that WIDGET_PREVIEWS also
 * waits for onFirstFrameDrawn before constructing or executing.
 */
package com.lomo.app.startup

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: first content precedes optional startup work; owner: app; P1.
 * Scenarios:
 * Given a cold process with no presented content, when startup starts, then security and theme
 * run while maintenance, shortcuts and update checks wait without executing or constructing.
 * Observable outcomes: startup task effects before/after presentation, cancellation/failure.
 * TDD proof: RED/GREEN in target/lomo/reports/manual/startup-scroll-round2/red.log.
 * Excludes: Android frame delivery, background worker execution and durable workspace ownership.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppStartupFirstContentTest : AppFunSpec() {
    init {
        test(
            "given no presented content when startup begins then optional work does not compete with the first frame",
        ) {
            runTest {
                val effects = mutableListOf<String>()
                val coordinator = coordinator(backgroundScope, effects)
                coordinator.start()
                runCurrent()
                effects shouldContainExactlyInAnyOrder listOf("SECURITY_SESSION_RESTORE", "THEME_APPLICATION")
                coordinator.onFirstFrameDrawn()
                runCurrent()
                effects shouldContainExactlyInAnyOrder (StartupTaskId.entries.map { it.name } + "constructed")
                coordinator.onFirstFrameDrawn()
                coordinator.start()
                runCurrent()
                effects shouldContainExactlyInAnyOrder (StartupTaskId.entries.map { it.name } + "constructed")
            }
        }

        test("given presentation before startup when startup begins then deferred work still runs once") {
            runTest {
                val effects = mutableListOf<String>()
                val coordinator = coordinator(backgroundScope, effects)
                coordinator.onFirstFrameDrawn()
                coordinator.start()
                runCurrent()
                effects shouldContainExactlyInAnyOrder (StartupTaskId.entries.map { it.name } + "constructed")
            }
        }

        test("given startup waiting for presentation when its scope is cancelled then optional work never starts") {
            runTest {
                val effects = mutableListOf<String>()
                val scope = CoroutineScope(
                    backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]),
                )
                val coordinator = coordinator(scope, effects)
                coordinator.start()
                runCurrent()
                scope.cancel()
                coordinator.onFirstFrameDrawn()
                runCurrent()
                effects shouldContainExactlyInAnyOrder listOf("SECURITY_SESSION_RESTORE", "THEME_APPLICATION")
            }
        }
    }

    private fun coordinator(scope: CoroutineScope, effects: MutableList<String>): AppStartupCoordinator {
        val security = mockk<SecuritySessionRestoreTask>()
        val theme = mockk<ThemeApplicationStartupTask>()
        val maintenance = mockk<WorkspaceMaintenanceStartupTask>()
        val shortcuts = mockk<DynamicShortcutStartupTask>()
        val widgetPreviews = mockk<WidgetPreviewStartupTask>()
        val update = mockk<StartupUpdateCheckTask>()
        listOf<StartupTask>(
            security,
            theme,
            maintenance,
            shortcuts,
            widgetPreviews,
            update,
        ).zip(StartupTaskId.entries).forEach { (task, id) ->
            every { task.definition } returns StartupTaskDefinition(
                id,
                id.ordinal,
                StartupTaskIdempotency.RUN_ONCE,
                StartupTaskFailurePolicy.REQUIRED,
            )
            coEvery { task.run(any()) } answers {
                effects.add(id.name)
            }
        }
        return AppStartupCoordinator(scope, security, theme) {
            effects += "constructed"
            listOf(maintenance, shortcuts, widgetPreviews, update)
        }
    }
}
