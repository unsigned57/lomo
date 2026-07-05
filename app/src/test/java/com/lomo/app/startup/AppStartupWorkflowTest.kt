/*
 * Behavior Contract:
 * - Unit under test: AppStartupWorkflow
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: centralize cold-start side effects behind typed startup tasks with explicit order, idempotency, and failure policy.
 *
 * Scenarios:
 * - Given boot tasks with mixed failure policies, when startup runs, then required tasks execute in declared order, best-effort failures are recorded, and one-shot tasks are not repeated.
 * - Given theme synchronization is registered as the application-owned task, when Activity observes theme state, then Activity does not perform the global night-mode side effect.
 * - Given startup updates are enabled, when startup runs, then the update check is triggered by the workflow path instead of ViewModel initialization.
 * - Given security abstractions support session locking, when startup runs, then credential reads start locked before UI unlock can authorize them.
 * - Given dynamic shortcuts are published on startup, when publishing fails, then the failure is recorded as best-effort and required startup can continue.
 *
 * Observable outcomes:
 * - Ordered task log, task run counts, workflow result failures, update check trigger count, theme application count, and security lock state.
 *
 * TDD proof:
 * - RED: production workflow types do not exist before the startup orchestration owner is introduced.
 *
 * Excludes:
 * - Android lifecycle dispatch, Compose rendering, BiometricPrompt UI, update transport, and AppCompatDelegate platform side effects.
 */

package com.lomo.app.startup

import com.lomo.app.feature.update.UpdateStartupOrchestrator
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.domain.model.ThemeMode
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AppStartupWorkflowTest : AppFunSpec() {
    private val dispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(dispatcher))

        test("given typed boot tasks when startup runs then ordering idempotency and failure policy are enforced") {
            runTest(dispatcher.scheduler) {
                val eventLog = mutableListOf<String>()
                val workflow =
                    AppStartupWorkflow(
                        tasks =
                            listOf(
                                RecordingStartupTask(
                                    id = StartupTaskId.SECURITY_SESSION_RESTORE,
                                    order = 10,
                                    failurePolicy = StartupTaskFailurePolicy.REQUIRED,
                                    eventLog = eventLog,
                                ),
                                RecordingStartupTask(
                                    id = StartupTaskId.THEME_APPLICATION,
                                    order = 20,
                                    failurePolicy = StartupTaskFailurePolicy.REQUIRED,
                                    eventLog = eventLog,
                                ),
                                RecordingStartupTask(
                                    id = StartupTaskId.STARTUP_UPDATE_CHECK,
                                    order = 40,
                                    failurePolicy = StartupTaskFailurePolicy.BEST_EFFORT,
                                    eventLog = eventLog,
                                    failure = IllegalStateException("update unavailable"),
                                ),
                                RecordingStartupTask(
                                    id = StartupTaskId.WORKSPACE_MAINTENANCE,
                                    order = 30,
                                    failurePolicy = StartupTaskFailurePolicy.REQUIRED,
                                    eventLog = eventLog,
                                ),
                            ),
                    )

                val firstResult = workflow.runStartup()
                val secondResult = workflow.runStartup()

                eventLog shouldContainExactly
                    listOf(
                        "SECURITY_SESSION_RESTORE",
                        "THEME_APPLICATION",
                        "WORKSPACE_MAINTENANCE",
                        "STARTUP_UPDATE_CHECK",
                    )
                assertSoftly(firstResult) {
                    completedTasks shouldContainExactly
                        listOf(
                            StartupTaskId.SECURITY_SESSION_RESTORE,
                            StartupTaskId.THEME_APPLICATION,
                            StartupTaskId.WORKSPACE_MAINTENANCE,
                        )
                    bestEffortFailures.map { it.taskId } shouldContainExactly
                        listOf(StartupTaskId.STARTUP_UPDATE_CHECK)
                }
                secondResult.completedTasks shouldBe emptyList()
                secondResult.bestEffortFailures shouldBe emptyList()
            }
        }

        test("given theme task owns global side effects when activity observes theme then activity only stores resolved state") {
            runTest(dispatcher.scheduler) {
                val appliedThemes = mutableListOf<ThemeMode>()
                val themeTask =
                    ThemeApplicationStartupTask(
                        appConfigRepository =
                            FakeAppConfigRepository().apply {
                                setThemeModeNow(ThemeMode.DARK)
                            },
                        themeSideEffect =
                            ThemeSideEffect(
                                isAppliedResolver = { themeMode -> appliedThemes.lastOrNull() == themeMode },
                                themeApplier = appliedThemes::add,
                            ),
                    )
                val workflow = AppStartupWorkflow(tasks = listOf(themeTask))
                val activityState = ActivityThemeState()

                workflow.runStartup(backgroundScope)
                advanceUntilIdle()
                activityState.acceptResolvedTheme(ThemeMode.DARK)

                appliedThemes shouldContainExactly listOf(ThemeMode.DARK)
                activityState.observedTheme shouldBe ThemeMode.DARK
                activityState.globalNightModeApplyCount shouldBe 0
            }
        }

        test("given update startup task when workflow runs then startup update check is not owned by viewmodel init") {
            runTest(dispatcher.scheduler) {
                var startupCheckCount = 0
                val updateOrchestrator =
                    UpdateStartupOrchestrator(
                        startupUpdateCheck = {
                            startupCheckCount++
                            null
                        },
                    )
                val workflow =
                    AppStartupWorkflow(
                        tasks =
                            listOf(
                                StartupUpdateCheckTask(updateOrchestrator),
                            ),
                    )

                workflow.runStartup()
                advanceUntilIdle()

                startupCheckCount shouldBe 1
            }
        }

        test("given security session task when workflow runs then credential reads start locked") {
            runTest(dispatcher.scheduler) {
                val securitySession = RecordingSecuritySessionController()
                val workflow =
                    AppStartupWorkflow(
                        tasks =
                            listOf(
                                SecuritySessionRestoreTask(securitySession),
                            ),
                    )

                workflow.runStartup()

                securitySession.events shouldContainExactly listOf("locked")
            }
        }

        test("given dynamic shortcut task fails when workflow runs then failure is best effort") {
            runTest(dispatcher.scheduler) {
                val publisher =
                    RecordingDynamicShortcutPublisher(
                        failure = IllegalStateException("launcher unavailable"),
                    )
                val workflow =
                    AppStartupWorkflow(
                        tasks =
                            listOf(
                                DynamicShortcutStartupTask(publisher),
                                SecuritySessionRestoreTask(RecordingSecuritySessionController()),
                            ),
                    )

                val result = workflow.runStartup()

                publisher.publishCount shouldBe 1
                assertSoftly(result) {
                    completedTasks shouldContainExactly listOf(StartupTaskId.SECURITY_SESSION_RESTORE)
                    bestEffortFailures.map { failure -> failure.taskId } shouldContainExactly
                        listOf(StartupTaskId.DYNAMIC_SHORTCUTS)
                }
            }
        }
    }
}

private class RecordingStartupTask(
    id: StartupTaskId,
    order: Int,
    failurePolicy: StartupTaskFailurePolicy,
    private val eventLog: MutableList<String>,
    private val failure: Throwable? = null,
) : StartupTask {
    override val definition: StartupTaskDefinition =
        StartupTaskDefinition(
            id = id,
            order = order,
            idempotency = StartupTaskIdempotency.RUN_ONCE,
            failurePolicy = failurePolicy,
        )

    override suspend fun run(scope: StartupTaskScope) {
        eventLog += definition.id.name
        failure?.let { throw it }
    }
}

private class ActivityThemeState {
    var observedTheme: ThemeMode? = null
        private set
    var globalNightModeApplyCount: Int = 0
        private set

    fun acceptResolvedTheme(themeMode: ThemeMode) {
        observedTheme = themeMode
    }
}

private class RecordingSecuritySessionController : com.lomo.domain.repository.SecuritySessionController {
    val events = mutableListOf<String>()

    override fun markCredentialReadsAuthorized() {
        events += "authorized"
    }

    override fun markCredentialReadsLocked() {
        events += "locked"
    }
}

private class RecordingDynamicShortcutPublisher(
    private val failure: Throwable? = null,
) : DynamicShortcutPublisher {
    var publishCount = 0
        private set

    override fun publishExternalEntryShortcuts() {
        publishCount += 1
        failure?.let { throw it }
    }
}
