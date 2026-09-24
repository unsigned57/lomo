package com.lomo.app.di

import com.lomo.domain.model.WorkspaceProcessDuty
import org.koin.core.module.Module

/**
 * Per-process dependency assembly decided by [WorkspaceProcessDuty].
 *
 * The engine-owning default process installs the full graph: reflected data modules plus the app,
 * domain and ViewModel modules. A projection-only process (the `:widget` Glance host) installs no
 * Koin graph at all — it renders a file snapshot written by the owning process and mints trusted
 * launch intents through `TrustedLaunchIntents.create`, so it must never resolve `LomoDataStore`,
 * `ManagedEngineSession`, WorkManager factories, or a workspace session.
 */
internal data class ProcessStartupPlan(
    val duty: WorkspaceProcessDuty,
    val processName: String,
    val modules: List<Module>,
) {
    /** Startup trace line recorded at process entry so loads are observable per process. */
    fun describe(): String {
        val dutyName = if (duty.ownsNativeEngine) "OWNED" else "PROJECTION_ONLY"
        return "process=$processName duty=$dutyName koinModules=${modules.size}"
    }
}

/** App-side modules shared by the owning process graph (order-stable for the trace). */
internal fun appProcessModules(): List<Module> =
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

internal fun processStartupPlan(
    duty: WorkspaceProcessDuty,
    processName: String,
    dataModules: List<Module>,
): ProcessStartupPlan =
    ProcessStartupPlan(
        duty = duty,
        processName = processName,
        modules =
            if (duty.ownsNativeEngine) {
                dataModules + appProcessModules()
            } else {
                emptyList()
            },
    )
