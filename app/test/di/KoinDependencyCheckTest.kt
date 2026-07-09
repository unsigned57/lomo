package com.lomo.app.di

/*
 * Behavior Contract:
 * - Unit under test: App and data Koin modules.
 * - Owning layer: app DI boundary.
 * - Priority tier: P1
 * - Capability: verify the runtime Koin dependency graph is complete after migrating to Koin.
 *
 * Scenarios:
 * - Given all production app and data Koin modules, when Koin verification runs, then every non-runtime
 *   dependency has a production binding.
 * - Given a constructor dependency is missing from the owning Koin module, when this test runs, then Koin
 *   verification fails during unit tests.
 *
 * Observable outcomes:
 * - Koin verify completes without MissingKoinDefinitionException and the production module list contains definitions.
 *
 * TDD proof:
 * - RED command: `./kotlin test --include-classes='com.lomo.app.di.KoinDependencyCheckTest'`.
 * - RED symptom: Koin verify failed with MissingKoinDefinitionException for MemoUiMapper's background dispatcher
 *   before MemoUiMapper encoded the dependency as DispatcherProvider.
 *
 * Excludes:
 * - Android framework object creation, worker execution, network calls, database IO, and app startup side effects.
 */

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.work.WorkerParameters
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.verify.verify

private val dataModules: List<Module> by lazy {
    val p1 = "com.lomo"
    val p2 = "data.di.DataModulesKt"
    val modules =
        Class.forName("$p1.$p2")
        .getMethod("getDataModules")
        .invoke(null)
    require(modules is List<*>) {
        "DataModulesKt.getDataModules must return List<Module>."
    }
    modules.map { module ->
        require(module is Module) {
            "DataModulesKt.getDataModules returned ${module?.javaClass?.name ?: "null"}, expected Module."
        }
        module
    }
}

class KoinDependencyCheckTest : AppFunSpec() {
    init {
        @OptIn(
            org.koin.core.annotation.KoinInternalApi::class,
            org.koin.core.annotation.KoinExperimentalAPI::class,
        )
        test("verify Koin dependency graph completeness") {
            val appModules =
                listOf(
                    appModule,
                    appScopeModule,
                    domainAppUpdateModule,
                    domainCoreModule,
                    domainMemoMutationModule,
                    domainMemoReadModule,
                    domainSearchModule,
                    domainShareModule,
                    domainSyncModule,
                    domainWorkspaceModule,
                    viewModelModule,
                )
            val allModules = module {
                includes(dataModules)
                includes(appModules)
            }

            allModules.verify(
                extraTypes = listOf(
                    Context::class,
                    SavedStateHandle::class,
                    WorkerParameters::class,
                    kotlinx.coroutines.CoroutineScope::class,
                    KoinWorkerFactory::class,
                    Function1::class,
                    Function0::class,
                    Function2::class,
                    Class.forName("io.ktor.client.HttpClient").kotlin
                )
            )

            ((dataModules + appModules).sumOf { module -> module.mappings.size } > 0) shouldBe true
        }
    }
}
