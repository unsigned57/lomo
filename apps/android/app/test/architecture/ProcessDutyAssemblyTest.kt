/*
 * Behavior Contract:
 * - Unit under test: processStartupPlan / WorkspaceProcessDuty assembly.
 * - Owning layer: app DI boundary.
 * - Priority tier: P0
 * - Capability: process duty selects the minimal platform host — the engine-owning default process
 *   installs the full graph; a projection-only process installs no Koin modules so widget wakes can
 *   never resolve the data layer, the engine session, or WorkManager factories.
 *
 * Scenarios:
 * - Given the owned duty, when the startup plan is assembled, then it carries the reflected data
 *   modules plus the app module set.
 * - Given the projection-only duty, when the startup plan is assembled, then it carries no modules
 *   and the describe trace names PROJECTION_ONLY.
 * - Given the projection-only module set, when a Koin application is started from it, then
 *   LomoDataStore, ManagedEngineSession and EngineReadinessRepository have no definitions.
 * - Given the owned module set, when the plan is described, then the startup trace names OWNED and
 *   the module count.
 *
 * Observable outcomes: module list contents, describe() trace text, Koin resolution results.
 * TDD proof:
 * - Fails before the fix because the contract surface under test did not exist.
 * Excludes: Android process creation, Glance rendering, native engine acquisition.
 */
package com.lomo.app.architecture

import com.lomo.app.di.processStartupPlan
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.WorkspaceProcessDuty
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module

class ProcessDutyAssemblyTest : AppFunSpec() {
    private val dataModuleCount = 3

    private fun fakeDataModules(): List<Module> =
        List(dataModuleCount) { org.koin.dsl.module {} }

    init {
        test("owned process installs data modules plus the app graph") {
            val plan =
                processStartupPlan(
                    duty = WorkspaceProcessDuty.OWNED,
                    processName = "com.lomo.app",
                    dataModules = fakeDataModules(),
                )

            plan.modules.size shouldBe dataModuleCount + APP_PROCESS_MODULE_COUNT
            plan.describe().contains("duty=OWNED") shouldBe true
            plan.describe().contains("koinModules=${dataModuleCount + APP_PROCESS_MODULE_COUNT}") shouldBe true
        }

        test("projection-only process installs no Koin modules") {
            val plan =
                processStartupPlan(
                    duty = WorkspaceProcessDuty.PROJECTION_ONLY,
                    processName = "com.lomo.app:widget",
                    dataModules = fakeDataModules(),
                )

            withClue("widget duty must not even see the data module list") {
                plan.modules shouldBe emptyList()
            }
            plan.describe().contains("duty=PROJECTION_ONLY") shouldBe true
            plan.describe().contains("process=com.lomo.app:widget") shouldBe true
        }

        test("projection-only graph cannot resolve data store, engine session, or readiness") {
            val plan =
                processStartupPlan(
                    duty = WorkspaceProcessDuty.PROJECTION_ONLY,
                    processName = "com.lomo.app:widget",
                    dataModules = fakeDataModules(),
                )

            val app = startKoin { modules(plan.modules) }
            try {
                val unresolvable =
                    listOf(
                        "com.lomo.data.local.datastore.LomoDataStore",
                        "com.lomo.data.engine.ManagedEngineSession",
                        "com.lomo.domain.repository.EngineReadinessRepository",
                    )
                unresolvable.forEach { typeName ->
                    val kClass = Class.forName(typeName).kotlin
                    withClue("projection-only graph must not resolve $typeName") {
                        app.koin.getOrNull<Any>(clazz = kClass) shouldBe null
                    }
                }
            } finally {
                stopKoin()
            }
        }
    }

    private companion object {
        /** appModule + appScopeModule + 8 domain modules + viewModelModule. */
        const val APP_PROCESS_MODULE_COUNT = 11
    }
}
