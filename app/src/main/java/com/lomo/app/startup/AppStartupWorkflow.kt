package com.lomo.app.startup

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.lomo.app.di.AppScope
import com.lomo.app.feature.main.MainStartupCoordinator
import com.lomo.app.feature.update.UpdateStartupOrchestrator
import com.lomo.app.theme.ThemeResyncPolicy
import com.lomo.app.theme.applyAppNightMode
import com.lomo.app.theme.resolvePlatformNightMode
import com.lomo.app.theme.toAppCompatNightMode
import com.lomo.app.util.runSuspendCatching
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.SecuritySessionController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.coroutineContext

enum class StartupTaskId {
    SECURITY_SESSION_RESTORE,
    THEME_APPLICATION,
    WORKSPACE_MAINTENANCE,
    STARTUP_UPDATE_CHECK,
}

enum class StartupTaskIdempotency {
    RUN_ONCE,
}

enum class StartupTaskFailurePolicy {
    REQUIRED,
    BEST_EFFORT,
}

data class StartupTaskDefinition(
    val id: StartupTaskId,
    val order: Int,
    val idempotency: StartupTaskIdempotency,
    val failurePolicy: StartupTaskFailurePolicy,
)

data class StartupTaskFailure(
    val taskId: StartupTaskId,
    val cause: Throwable,
)

data class StartupWorkflowResult(
    val completedTasks: List<StartupTaskId>,
    val bestEffortFailures: List<StartupTaskFailure>,
)

class StartupTaskScope(
    val coroutineScope: CoroutineScope,
)

interface StartupTask {
    val definition: StartupTaskDefinition

    suspend fun run(scope: StartupTaskScope)
}

class AppStartupWorkflow(
    tasks: List<StartupTask>,
) {
    private val orderedTasks = tasks.sortedBy { it.definition.order }
    private val completedOneShotTasks = mutableSetOf<StartupTaskId>()
    private val attemptedBestEffortOneShotTasks = mutableSetOf<StartupTaskId>()
    private val startupMutex = Mutex()

    suspend fun runStartup(): StartupWorkflowResult = runStartup(CoroutineScope(coroutineContext))

    suspend fun runStartup(scope: CoroutineScope): StartupWorkflowResult =
        startupMutex.withLock {
            runStartupLocked(scope)
        }

    private suspend fun runStartupLocked(scope: CoroutineScope): StartupWorkflowResult {
        val completedTasks = mutableListOf<StartupTaskId>()
        val bestEffortFailures = mutableListOf<StartupTaskFailure>()
        val taskScope = StartupTaskScope(scope)

        for (task in orderedTasks) {
            val definition = task.definition
            if (
                definition.idempotency == StartupTaskIdempotency.RUN_ONCE &&
                (definition.id in completedOneShotTasks || definition.id in attemptedBestEffortOneShotTasks)
            ) {
                continue
            }

            val result = runSuspendCatching { task.run(taskScope) }
            result
                .onSuccess {
                    completedOneShotTasks += definition.id
                    completedTasks += definition.id
                }.onFailure { failure ->
                    when (definition.failurePolicy) {
                        StartupTaskFailurePolicy.REQUIRED -> throw failure
                        StartupTaskFailurePolicy.BEST_EFFORT -> {
                            attemptedBestEffortOneShotTasks += definition.id
                            bestEffortFailures +=
                                StartupTaskFailure(
                                    taskId = definition.id,
                                    cause = failure,
                                )
                        }
                    }
                }
        }

        return StartupWorkflowResult(
            completedTasks = completedTasks,
            bestEffortFailures = bestEffortFailures,
        )
    }
}

class SecuritySessionRestoreTask
    @Inject
    constructor(
        private val securitySessionController: SecuritySessionController,
    ) : StartupTask {
        override val definition: StartupTaskDefinition =
            StartupTaskDefinition(
                id = StartupTaskId.SECURITY_SESSION_RESTORE,
                order = STARTUP_ORDER_SECURITY_SESSION_RESTORE,
                idempotency = StartupTaskIdempotency.RUN_ONCE,
                failurePolicy = StartupTaskFailurePolicy.REQUIRED,
            )

        override suspend fun run(scope: StartupTaskScope) {
            securitySessionController.markCredentialReadsLocked()
        }
    }

class StartupUpdateCheckTask
    @Inject
    constructor(
        private val updateStartupOrchestrator: UpdateStartupOrchestrator,
    ) : StartupTask {
        override val definition: StartupTaskDefinition =
            StartupTaskDefinition(
                id = StartupTaskId.STARTUP_UPDATE_CHECK,
                order = STARTUP_ORDER_UPDATE_CHECK,
                idempotency = StartupTaskIdempotency.RUN_ONCE,
                failurePolicy = StartupTaskFailurePolicy.BEST_EFFORT,
            )

        override suspend fun run(scope: StartupTaskScope) {
            updateStartupOrchestrator.triggerStartupCheck(scope.coroutineScope)
        }
    }

class WorkspaceMaintenanceStartupTask
    @Inject
    constructor(
        private val startupCoordinator: MainStartupCoordinator,
    ) : StartupTask {
        override val definition: StartupTaskDefinition =
            StartupTaskDefinition(
                id = StartupTaskId.WORKSPACE_MAINTENANCE,
                order = STARTUP_ORDER_WORKSPACE_MAINTENANCE,
                idempotency = StartupTaskIdempotency.RUN_ONCE,
                failurePolicy = StartupTaskFailurePolicy.REQUIRED,
            )

        override suspend fun run(scope: StartupTaskScope) {
            val rootDir = startupCoordinator.initializeRootDirectory()
            startupCoordinator.runDeferredStartupTasks(rootDir)
        }
    }

class ThemeApplicationStartupTask
    @Inject
    constructor(
        private val appConfigRepository: AppConfigRepository,
        private val themeSideEffect: ThemeSideEffect,
    ) : StartupTask {
        private var observeThemeJob: Job? = null
        @Volatile
        private var currentThemeMode: ThemeMode = ThemeMode.SYSTEM

        override val definition: StartupTaskDefinition =
            StartupTaskDefinition(
                id = StartupTaskId.THEME_APPLICATION,
                order = STARTUP_ORDER_THEME_APPLICATION,
                idempotency = StartupTaskIdempotency.RUN_ONCE,
                failurePolicy = StartupTaskFailurePolicy.REQUIRED,
            )

        override suspend fun run(scope: StartupTaskScope) {
            applyTheme(appConfigRepository.getThemeMode().first())
            if (observeThemeJob?.isActive == true) {
                return
            }
            observeThemeJob =
                scope.coroutineScope.launch {
                    appConfigRepository.getThemeMode().collectLatest { themeMode ->
                        applyTheme(themeMode)
                    }
                }
        }

        suspend fun resyncTheme(themeMode: ThemeMode) {
            applyTheme(themeMode)
        }

        suspend fun resyncOnConfigurationChange(
            previousUiMode: Int,
            currentUiMode: Int,
        ) {
            val themeMode = currentThemeMode
            if (
                ThemeResyncPolicy.shouldResyncOnConfigurationChange(
                    themeMode = themeMode,
                    previousUiMode = previousUiMode,
                    currentUiMode = currentUiMode,
                )
            ) {
                applyTheme(themeMode)
            }
        }

        private suspend fun applyTheme(themeMode: ThemeMode) {
            currentThemeMode = themeMode
            if (themeSideEffect.isApplied(themeMode)) {
                return
            }
            withContext(Dispatchers.Main.immediate) {
                themeSideEffect.apply(themeMode)
            }
        }
    }

@Singleton
class ThemeSideEffect
    internal constructor(
        private val isAppliedResolver: (ThemeMode) -> Boolean,
        private val themeApplier: (ThemeMode) -> Unit,
    ) {
        @Inject
        constructor(
            @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
        ) : this(
            isAppliedResolver = { themeMode ->
                val targetCompatMode = themeMode.toAppCompatNightMode()
                val targetPlatformMode = resolvePlatformNightMode(themeMode)
                val alreadyAppliedCompatMode =
                    AppCompatDelegate.getDefaultNightMode() == targetCompatMode
                val alreadyAppliedPlatformMode =
                    targetPlatformMode == null ||
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.getSystemService(UiModeManager::class.java)?.nightMode == targetPlatformMode
                        } else {
                            true
                        }
                alreadyAppliedCompatMode && alreadyAppliedPlatformMode
            },
            themeApplier = { themeMode ->
                applyAppNightMode(
                    context = context,
                    themeMode = themeMode,
                )
            },
        )

        fun isApplied(themeMode: ThemeMode): Boolean {
            return isAppliedResolver(themeMode)
        }

        fun apply(themeMode: ThemeMode) {
            themeApplier(themeMode)
        }
    }

@Singleton
class AppStartupCoordinator
    @Inject
    constructor(
        @AppScope private val appScope: CoroutineScope,
        securitySessionRestoreTask: SecuritySessionRestoreTask,
        private val themeApplicationStartupTask: ThemeApplicationStartupTask,
        workspaceMaintenanceStartupTask: WorkspaceMaintenanceStartupTask,
        startupUpdateCheckTask: StartupUpdateCheckTask,
    ) {
        private val workflow =
            AppStartupWorkflow(
                tasks =
                    listOf(
                        securitySessionRestoreTask,
                        themeApplicationStartupTask,
                        workspaceMaintenanceStartupTask,
                        startupUpdateCheckTask,
                    ),
            )

        fun start() {
            appScope.launch {
                runSuspendCatching {
                    workflow.runStartup(appScope)
                }.onFailure { failure ->
                    Timber.e(failure, "Required startup task failed")
                }
            }
        }

        fun resyncThemeOnConfigurationChange(
            previousUiMode: Int,
            currentUiMode: Int,
        ) {
            appScope.launch {
                runSuspendCatching {
                    themeApplicationStartupTask.resyncOnConfigurationChange(
                        previousUiMode = previousUiMode,
                        currentUiMode = currentUiMode,
                    )
                }.onFailure { failure ->
                    Timber.w(failure, "Failed to resync app theme after configuration change")
                }
            }
        }
    }

private const val STARTUP_ORDER_SECURITY_SESSION_RESTORE = 10
private const val STARTUP_ORDER_THEME_APPLICATION = 20
private const val STARTUP_ORDER_WORKSPACE_MAINTENANCE = 30
private const val STARTUP_ORDER_UPDATE_CHECK = 40
