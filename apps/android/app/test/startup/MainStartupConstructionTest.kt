package com.lomo.app.startup

import com.lomo.app.di.appModule
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.main.MainStartupCoordinator
import com.lomo.app.media.AudioPlayerManager
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.usecase.GetCurrentAppBuildVersionUseCase
import com.lomo.domain.usecase.ObserveWorkspaceSessionUseCase
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/*
 * Behavior Contract:
 * Capability: maintenance dependencies are acquired only by deferred startup; owner: app; P1.
 * Scenarios:
 * Given the production app module, when the homepage coordinator is resolved, then maintenance
 * is not constructed. When deferred maintenance runs twice, its singleton is acquired once
 * and both invocations finish using the current build version and selected root.
 * Observable outcomes: construction count and completed maintenance inputs.
 * TDD proof: RED/GREEN: target/lomo/reports/manual/memo-startup/startup-construction-*.log.
 * Excludes: native mount throughput, Android drawing and background worker scheduling.
 */
class MainStartupConstructionTest : AppFunSpec() {
    init {
        test("given homepage construction when maintenance is deferred then its dependency graph waits until use") {
            runTest {
                var constructed = 0
                val completed = mutableListOf<Pair<String?, String>>()
                val maintenance = mockk<StartupMaintenanceUseCase>()
                coEvery { maintenance.runDeferredStartupTasks(any(), any()) } answers {
                    completed += firstArg<String?>() to secondArg<String>()
                }
                val version = mockk<GetCurrentAppBuildVersionUseCase>()
                coEvery { version() } returns "test-build"
                val overrides = module {
                    single { version }
                    single { mockk<AppConfigStateProvider>() }
                    single { mockk<AudioPlayerManager>() }
                    single { mockk<ObserveWorkspaceSessionUseCase>() }
                    single {
                        constructed++
                        maintenance
                    }
                }
                val application = koinApplication { modules(appModule, overrides) }
                try {
                    val coordinator = application.koin.get<MainStartupCoordinator>()
                    constructed shouldBe 0
                    coordinator.runDeferredStartupTasks("/workspace")
                    coordinator.runDeferredStartupTasks(null)
                    constructed shouldBe 1
                    completed shouldBe listOf("/workspace" to "test-build", null to "test-build")
                } finally {
                    application.close()
                }
            }
        }
    }
}
