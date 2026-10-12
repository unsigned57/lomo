/*
 * Behavior Contract:
 * - Unit under test: production data Koin modules
 *   (coreDataRepositoryModule + syncDataModule + applicationScopeModule).
 * - Owning layer: data DI boundary.
 * - Priority tier: P0
 * - Capability: the security-session <-> deferred-work subgraph stays acyclic at
 *   construction time. LomoApplication.onCreate resolves SecuritySessionPolicy
 *   through WidgetProjectionBinder, so any eager constructor cycle recurses into
 *   StackOverflowError before the first frame — a process-fatal regression that
 *   Koin verify() cannot see: verify only checks interface-primary-type
 *   definitions for missing bindings, never resolution-time cycles.
 *
 * Scenarios:
 * - Given the production modules with Android leaf bindings stubbed, when
 *   SecuritySessionPolicy is resolved, then construction completes and the same
 *   instance serves SecuritySessionController.
 * - Given the same graph, when AuthorizedWorkResume, SecretMaterialSource, and
 *   RustSyncScheduler are resolved, then construction completes.
 *
 * Observable outcomes: all four resolutions return the bound implementations;
 * no StackOverflowError.
 *
 * TDD proof:
 * - RED command: ./kotlin test --include-module=data
 *   --include-classes='com.lomo.data.di.SecurityWorkConstructorCycleGuardTest'
 * - RED symptom: resolving SecuritySessionPolicy recursed
 *   SecuritySessionPolicy -> AuthorizedWorkResume -> RustSyncScheduler ->
 *   SecretMaterialSource -> SecuritySessionPolicy until StackOverflowError,
 *   because DeferredLockAuthorizedWorkResume eagerly demanded RustSyncScheduler.
 *
 * Excludes:
 * - Android framework behavior — Context, DataStore, WorkManager, credential
 *   storage, and deferred-lock file IO are stubbed leaves never exercised;
 *   app-module bindings are outside this subgraph.
 */
package com.lomo.data.di

import android.content.Context
import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.engine.sync.CredentialSecretMaterialSource
import com.lomo.data.engine.sync.SecretMaterialSource
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.security.AppLockPreferenceSource
import com.lomo.data.security.DataStoreSecuritySessionPolicy
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.worker.DeferredLockAuthorizedWorkResume
import com.lomo.data.worker.DeferredLockWorkStore
import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.model.AppLockPreference
import com.lomo.domain.repository.AuthorizedWorkResume
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.repository.SecuritySessionController
import com.lomo.domain.repository.SecuritySessionPolicy
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Deterministic lock-off preference feed — construction of the policy launches a
 * collector on [AppLockPreferenceSource.observe]; an empty flow keeps it inert.
 */
private object DisabledAppLockPreferenceSource : AppLockPreferenceSource {
    override suspend fun read(): AppLockPreference = AppLockPreference.Disabled

    override fun observe(): Flow<AppLockPreference> = emptyFlow()
}

class SecurityWorkConstructorCycleGuardTest : DataFunSpec() {
    init {
        test("given production data modules when cycle nodes resolve then construction completes") {
            val androidLeafFakes =
                module {
                    single<Context> { mockk() }
                    single<DispatcherProvider> { DefaultDispatcherProvider() }
                    single<LomoDataStore> { mockk() }
                    single<WorkspaceFilesystemRoot> { WorkspaceFilesystemRoot { null } }
                    single<CredentialRepository> { mockk() }
                    single<AppLockPreferenceSource> { DisabledAppLockPreferenceSource }
                    single<DeferredLockWorkStore> { mockk() }
                }
            val app =
                koinApplication {
                    modules(
                        coreDataRepositoryModule,
                        syncDataModule,
                        applicationScopeModule,
                    )
                }
            app.allowOverride(true)
            app.modules(androidLeafFakes)
            try {
                val policy = app.koin.get<SecuritySessionPolicy>()
                policy.shouldBeInstanceOf<DataStoreSecuritySessionPolicy>()
                app.koin.get<SecuritySessionController>() shouldBe policy

                app.koin
                    .get<AuthorizedWorkResume>()
                    .shouldBeInstanceOf<DeferredLockAuthorizedWorkResume>()
                app.koin
                    .get<SecretMaterialSource>()
                    .shouldBeInstanceOf<CredentialSecretMaterialSource>()
                app.koin
                    .get<RustSyncScheduler>()
                    .shouldBeInstanceOf<RustSyncScheduler>()
            } finally {
                app.close()
            }
        }
    }
}
