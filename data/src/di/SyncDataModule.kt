package com.lomo.data.di

import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.engine.sync.BoltFfiRemoteSyncRepository
import com.lomo.data.engine.sync.BridgeConflictArtifactSource
import com.lomo.data.engine.sync.ConflictArtifactSource
import com.lomo.data.engine.sync.CredentialSecretMaterialSource
import com.lomo.data.engine.sync.FreeFunctionSyncNativeBridge
import com.lomo.data.engine.sync.KeystoreRustSyncSecretSupplier
import com.lomo.data.engine.sync.RemoteSyncCenterRepositoryAdapter
import com.lomo.data.engine.sync.RemoteSyncRepository
import com.lomo.data.engine.sync.RustSyncSecretSupplier
import com.lomo.data.engine.sync.SecretMaterialSource
import com.lomo.data.engine.sync.SyncNativeBridge
import com.lomo.data.repository.AppVersionRepositoryImpl
import com.lomo.data.repository.GitRemoteSyncFacade
import com.lomo.data.repository.GitSyncConfigurationMutationRepositoryImpl
import com.lomo.data.repository.GitSyncConfigurationRepositoryImpl
import com.lomo.data.repository.GitSyncStateRepositoryImpl
import com.lomo.data.repository.S3RemoteSyncFacade
import com.lomo.data.repository.S3SyncConfigurationMutationRepositoryImpl
import com.lomo.data.repository.S3SyncConfigurationRepositoryImpl
import com.lomo.data.repository.S3SyncStateRepositoryImpl
import com.lomo.data.repository.SyncPolicyRepositoryImpl
import com.lomo.data.repository.WebDavRemoteSyncFacade
import com.lomo.data.repository.WebDavSyncConfigurationMutationRepositoryImpl
import com.lomo.data.repository.WebDavSyncConfigurationRepositoryImpl
import com.lomo.data.repository.WebDavSyncStateRepositoryImpl
import com.lomo.data.sync.OwnerMemoIdentityConflictMerger
import com.lomo.data.sync.RustSyncWorkPolicyPlanner
import com.lomo.data.sync.SyncConflictBackupManager
import com.lomo.data.worker.RemoteSyncRustWorkExecutor
import com.lomo.data.worker.RustSyncScheduler
import com.lomo.data.worker.RustSyncWorkExecutor
import com.lomo.data.worker.RustSyncWorker
import com.lomo.data.worker.SyncWorker
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.WebDavSyncStateRepository
import com.lomo.domain.repository.WebDavSyncConfigurationRepository
import com.lomo.domain.repository.WebDavSyncConfigurationMutationRepository
import com.lomo.domain.repository.S3SyncStateRepository
import com.lomo.domain.repository.S3SyncConfigurationRepository
import com.lomo.domain.repository.S3SyncConfigurationMutationRepository
import com.lomo.domain.repository.GitSyncStateRepository
import com.lomo.domain.repository.GitSyncConfigurationRepository
import com.lomo.domain.repository.GitSyncConfigurationMutationRepository
import com.lomo.domain.repository.RemoteSyncCenterRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SyncConflictBackupRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.UnifiedSyncProvider
import com.lomo.domain.repository.WebDavSyncRepository
import com.lomo.domain.usecase.GitUnifiedSyncProvider
import com.lomo.domain.usecase.InboxUnifiedSyncProvider
import com.lomo.domain.usecase.S3UnifiedSyncProvider
import com.lomo.domain.usecase.WebDavUnifiedSyncProvider
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * P5-13 production cutover: single Rust-backed remote sync stack.
 *
 * Kotlin provider engines, sync-v1 planner, and provider WorkManager workers are deleted.
 * Settings retain config/credential surfaces via thin facades that enqueue [RustSyncWorker].
 * Sync Center is the conflict authority over [RemoteSyncCenterRepository].
 */
val syncDataModule = module {
    singleOf(::OwnerMemoIdentityConflictMerger) bind com.lomo.domain.repository.MemoIdentityConflictMerger::class
    singleOf(::SyncConflictBackupManager) bind SyncConflictBackupRepository::class
    singleOf(::AppVersionRepositoryImpl) bind AppVersionRepository::class

    // BoltFFI conversion bridge (Rust-owned decisions)
    single<SyncNativeBridge> { FreeFunctionSyncNativeBridge() }
    single<RemoteSyncRepository> { BoltFfiRemoteSyncRepository(bridge = get()) }
    single<ConflictArtifactSource> { BridgeConflictArtifactSource(bridge = get()) }
    single<SecretMaterialSource> {
        CredentialSecretMaterialSource(
            credentialRepository = get(),
            securitySessionPolicy = get(),
        )
    }
    single<RustSyncSecretSupplier> {
        KeystoreRustSyncSecretSupplier(
            materialSource = get(),
            remoteSync = get(),
        )
    }
    single<RustSyncWorkExecutor> { RemoteSyncRustWorkExecutor(remoteSync = get()) }
    single<RemoteSyncCenterRepository> {
        RemoteSyncCenterRepositoryAdapter(
            remoteSync = get(),
            artifactSource = get(),
        )
    }

    // Config facades (DataStore + Keystore only)
    singleOf(::GitSyncConfigurationRepositoryImpl) bind GitSyncConfigurationRepository::class
    singleOf(::GitSyncConfigurationMutationRepositoryImpl) bind
        GitSyncConfigurationMutationRepository::class
    singleOf(::GitSyncStateRepositoryImpl) bind GitSyncStateRepository::class
    singleOf(::WebDavSyncConfigurationRepositoryImpl) bind
        WebDavSyncConfigurationRepository::class
    singleOf(::WebDavSyncConfigurationMutationRepositoryImpl) bind
        WebDavSyncConfigurationMutationRepository::class
    singleOf(::WebDavSyncStateRepositoryImpl) bind WebDavSyncStateRepository::class
    singleOf(::S3SyncConfigurationRepositoryImpl) bind
        S3SyncConfigurationRepository::class
    singleOf(::S3SyncConfigurationMutationRepositoryImpl) bind
        S3SyncConfigurationMutationRepository::class
    singleOf(::S3SyncStateRepositoryImpl) bind S3SyncStateRepository::class

    single { RustSyncWorkPolicyPlanner() }
    single {
        RustSyncScheduler(
            context = androidContext(),
            dataStore = get(),
            workspaceRoot = get(),
            policyPlanner = get(),
            identityMaterial = get(),
        )
    }

    single {
        GitRemoteSyncFacade(
            configuration = get(),
            configurationMutation = get(),
            state = get(),
            rustSyncScheduler = get(),
        )
    } bind GitSyncRepository::class
    single {
        WebDavRemoteSyncFacade(
            configuration = get(),
            configurationMutation = get(),
            state = get(),
            rustSyncScheduler = get(),
        )
    } bind WebDavSyncRepository::class
    single {
        S3RemoteSyncFacade(
            configuration = get(),
            configurationMutation = get(),
            state = get(),
            rustSyncScheduler = get(),
        )
    } bind S3SyncRepository::class

    single {
        SyncPolicyRepositoryImpl(
            context = androidContext(),
            dataStore = get(),
            rustSyncScheduler = get(),
        )
    } bind SyncPolicyRepository::class

    // WorkManager: memo refresh + single Rust remote runner
    workerOf(::SyncWorker)
    workerOf(::RustSyncWorker)

    // Unified providers (settings / refresh enqueue path) + independent Sync Inbox
    single<UnifiedSyncProvider> { GitUnifiedSyncProvider(get()) }
    single<UnifiedSyncProvider> { WebDavUnifiedSyncProvider(get()) }
    single<UnifiedSyncProvider> { S3UnifiedSyncProvider(get()) }
    single<UnifiedSyncProvider> { InboxUnifiedSyncProvider(get(), get()) }
}
