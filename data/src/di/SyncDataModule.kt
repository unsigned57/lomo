package com.lomo.data.di

import com.lomo.data.git.FileGitMediaSyncStateStore
import com.lomo.data.git.GitCredentialStrategy
import com.lomo.data.git.GitFileHistoryReader
import com.lomo.data.git.GitMediaSyncBridge
import com.lomo.data.git.GitMediaSyncFingerprintIndex
import com.lomo.data.git.GitMediaSyncPlanner
import com.lomo.data.git.GitMediaSyncStateStore
import com.lomo.data.git.GitMediaSyncWorkspaceStateStore
import com.lomo.data.git.GitRepositoryPrimitives
import com.lomo.data.git.GitSyncEngine
import com.lomo.data.git.GitSyncQueryTestCoordinator
import com.lomo.data.git.RawGitMediaSyncStateStore
import com.lomo.data.git.SafGitMirrorBridge
import com.lomo.data.network.SyncHttpClientProvider
import com.lomo.data.repository.WebDavSyncPlanner
import com.lomo.data.repository.S3SyncPlanner
import com.lomo.data.repository.AndroidS3SafTreeAccess
import com.lomo.data.repository.S3SafTreeAccess
import com.lomo.data.repository.GitSyncRepositoryImpl
import com.lomo.data.repository.GitSyncConfigurationMutationRepositoryImpl
import com.lomo.data.repository.GitSyncConfigurationRepositoryImpl
import com.lomo.data.repository.GitSyncConflictRepositoryImpl
import com.lomo.data.repository.GitSyncInitAndSyncExecutor
import com.lomo.data.repository.GitSyncMaintenanceExecutor
import com.lomo.data.repository.GitSyncMemoMirror
import com.lomo.data.repository.GitSyncOperationRepositoryImpl
import com.lomo.data.repository.GitSyncRepositoryContext
import com.lomo.data.repository.GitSyncRepositorySupport
import com.lomo.data.repository.GitSyncStateRepositoryImpl
import com.lomo.data.repository.GitSyncStatusExecutor
import com.lomo.data.repository.WebDavSyncRepositoryImpl
import com.lomo.data.repository.AppVersionRepositoryImpl
import com.lomo.data.repository.SyncPolicyRepositoryImpl
import com.lomo.data.repository.S3SyncRepositoryImpl
import com.lomo.data.repository.S3ScheduledSyncWorkEnqueuer
import com.lomo.data.repository.DefaultRemoteSyncLifecycleRunner
import com.lomo.data.repository.RemoteSyncLifecycleRunner
import com.lomo.data.repository.TimberSyncLifecycleExecutionOwner
import com.lomo.data.repository.SyncLifecycleExecutionOwner
import com.lomo.data.repository.DefaultS3RefreshSyncPolicyPlanner
import com.lomo.data.repository.S3RefreshSyncPolicyPlanner
import com.lomo.data.repository.S3ConflictResolver
import com.lomo.data.repository.S3ReviewResolver
import com.lomo.data.repository.S3RemoteObjectKeyPolicy
import com.lomo.data.repository.S3SyncActionApplier
import com.lomo.data.repository.S3SyncConfigurationMutationRepositoryImpl
import com.lomo.data.repository.S3SyncConfigurationRepositoryImpl
import com.lomo.data.repository.S3SyncConflictRepositoryImpl
import com.lomo.data.repository.S3SyncEncodingSupport
import com.lomo.data.repository.S3SyncExecutor
import com.lomo.data.repository.S3SyncFileBridge
import com.lomo.data.repository.S3SyncOperationRepositoryImpl
import com.lomo.data.repository.S3SyncRepositoryContext
import com.lomo.data.repository.S3SyncRepositorySupport
import com.lomo.data.repository.S3SyncReviewRepositoryImpl
import com.lomo.data.repository.S3SyncStateHolder
import com.lomo.data.repository.S3SyncStateRepositoryImpl
import com.lomo.data.repository.S3SyncStatusTester
import com.lomo.data.repository.S3SyncWorkExecutor
import com.lomo.data.repository.RoomBackedS3SyncProtocolStateStore
import com.lomo.data.repository.S3SyncProtocolStateStore
import com.lomo.data.repository.RoomBackedS3LocalChangeJournalStore
import com.lomo.data.repository.S3LocalChangeJournalStore
import com.lomo.data.repository.DefaultS3LocalChangeRecorder
import com.lomo.data.repository.S3LocalChangeRecorder
import com.lomo.data.repository.RoomBackedS3RemoteIndexStore
import com.lomo.data.repository.S3RemoteIndexStore
import com.lomo.data.repository.RoomBackedS3RemoteShardStateStore
import com.lomo.data.repository.S3RemoteShardStateStore
import com.lomo.data.repository.RoomBackedS3SyncTransactionRunner
import com.lomo.data.repository.S3SyncTransactionRunner
import com.lomo.data.repository.S3SyncTransferWorkspace
import com.lomo.data.repository.RoomBackedWebDavLocalChangeJournalStore
import com.lomo.data.repository.WebDavLocalChangeJournalStore
import com.lomo.data.repository.DefaultWebDavLocalChangeRecorder
import com.lomo.data.repository.WebDavLocalChangeRecorder
import com.lomo.data.repository.RoomBackedWebDavLocalFingerprintCache
import com.lomo.data.repository.WebDavLocalFingerprintCache
import com.lomo.data.repository.WebDavConflictResolver
import com.lomo.data.repository.AndroidSyncPerformanceTuner
import com.lomo.data.repository.SyncPerformanceTuner
import com.lomo.data.repository.WebDavRemoteListingCache
import com.lomo.data.repository.WebDavReviewResolver
import com.lomo.data.repository.WebDavSyncActionApplier
import com.lomo.data.repository.WebDavSyncConfigurationMutationRepositoryImpl
import com.lomo.data.repository.WebDavSyncConfigurationRepositoryImpl
import com.lomo.data.repository.WebDavSyncConflictRepositoryImpl
import com.lomo.data.repository.WebDavSyncExecutor
import com.lomo.data.repository.WebDavSyncFileBridge
import com.lomo.data.repository.WebDavSyncOperationRepositoryImpl
import com.lomo.data.repository.WebDavSyncRepositoryContext
import com.lomo.data.repository.WebDavSyncRepositorySupport
import com.lomo.data.repository.WebDavSyncReviewRepositoryImpl
import com.lomo.data.repository.WebDavSyncStateHolder
import com.lomo.data.repository.WebDavSyncStateRepositoryImpl
import com.lomo.data.repository.WebDavSyncStatusTester
import com.lomo.data.s3.AwsSdkS3ClientFactory
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.sync.SyncConflictBackupManager
import com.lomo.data.sync.GitSyncWorkPolicyPlanner
import com.lomo.data.sync.WebDavSyncWorkPolicyPlanner
import com.lomo.data.sync.S3SyncWorkPolicyPlanner
import com.lomo.data.webdav.Dav4jvmWebDavClientFactory
import com.lomo.data.webdav.DefaultWebDavEndpointResolver
import com.lomo.data.webdav.WebDavClientFactory
import com.lomo.data.webdav.WebDavEndpointResolver
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.data.worker.S3SyncScheduler
import com.lomo.data.worker.GitSyncScheduler
import com.lomo.data.worker.WebDavSyncScheduler
import com.lomo.data.worker.GitWorkManagerScheduledSyncWorkEnqueuer
import com.lomo.data.worker.GitScheduledSyncWorkEnqueuer
import com.lomo.data.worker.WebDavWorkManagerScheduledSyncWorkEnqueuer
import com.lomo.data.worker.WebDavScheduledSyncWorkEnqueuer
import com.lomo.data.worker.GitSyncWorker
import com.lomo.data.worker.S3SyncWorker
import com.lomo.data.worker.SyncWorker
import com.lomo.data.worker.WebDavSyncWorker
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SyncConflictBackupRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.UnifiedSyncProvider
import com.lomo.domain.repository.WebDavSyncRepository
import com.lomo.domain.usecase.GitUnifiedSyncProvider
import com.lomo.domain.usecase.InboxUnifiedSyncProvider
import com.lomo.domain.usecase.S3UnifiedSyncProvider
import com.lomo.domain.usecase.WebDavUnifiedSyncProvider
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.dsl.bind

val syncDataModule = module {
    singleOf(::SyncHttpClientProvider)

    // Planners
    single { WebDavSyncPlanner() }
    single { GitMediaSyncPlanner() }
    single { S3SyncPlanner() }

    // Client factories & resolvers
    singleOf(::Dav4jvmWebDavClientFactory) bind WebDavClientFactory::class
    singleOf(::DefaultWebDavEndpointResolver) bind WebDavEndpointResolver::class
    single { AndroidS3SafTreeAccess(androidContext()) } bind S3SafTreeAccess::class
    singleOf(::AwsSdkS3ClientFactory) bind LomoS3ClientFactory::class

    // State stores
    single { FileGitMediaSyncStateStore(androidContext()) } bind RawGitMediaSyncStateStore::class
    singleOf(::GitMediaSyncWorkspaceStateStore) bind GitMediaSyncStateStore::class

    // Git sync runtime
    singleOf(::GitCredentialStrategy)
    singleOf(::GitRepositoryPrimitives)
    singleOf(::GitSyncEngine)
    singleOf(::GitFileHistoryReader)
    singleOf(::GitSyncQueryTestCoordinator)
    singleOf(::GitMediaSyncFingerprintIndex)
    singleOf(::GitMediaSyncBridge)
    single { SafGitMirrorBridge(androidContext()) }
    single {
        GitSyncRepositoryContext(
            context = androidContext(),
            gitSyncEngine = get(),
            credentialStore = get(),
            dataStore = get(),
            memoSynchronizer = get(),
            safGitMirrorBridge = get(),
            gitMediaSyncBridge = get(),
            gitSyncQueryCoordinator = get(),
            markdownParser = get(),
            markdownStorageDataSource = get(),
        )
    }
    singleOf(::GitSyncRepositorySupport)
    singleOf(::GitSyncMemoMirror)
    singleOf(::GitSyncInitAndSyncExecutor)
    singleOf(::GitSyncStatusExecutor)
    singleOf(::GitSyncMaintenanceExecutor)
    singleOf(::GitSyncConfigurationRepositoryImpl)
    singleOf(::GitSyncConfigurationMutationRepositoryImpl)
    singleOf(::GitSyncOperationRepositoryImpl)
    singleOf(::GitSyncConflictRepositoryImpl)
    singleOf(::GitSyncStateRepositoryImpl)

    // Repositories
    singleOf(::GitSyncRepositoryImpl) bind GitSyncRepository::class
    singleOf(::WebDavSyncRepositoryImpl) bind WebDavSyncRepository::class
    singleOf(::AppVersionRepositoryImpl) bind AppVersionRepository::class
    singleOf(::SyncPolicyRepositoryImpl) bind SyncPolicyRepository::class
    singleOf(::SyncConflictBackupManager) bind SyncConflictBackupRepository::class
    singleOf(::S3SyncRepositoryImpl) bind S3SyncRepository::class

    // Planners/Policy Planners
    single { GitSyncWorkPolicyPlanner() }
    single { WebDavSyncWorkPolicyPlanner() }
    single { S3SyncWorkPolicyPlanner(get(), get(), get()) }

    // Schedulers
    single { GitSyncScheduler(androidContext(), get(), get(), get()) }
    single { WebDavSyncScheduler(androidContext(), get(), get(), get()) }
    single { S3SyncScheduler(androidContext(), get(), get()) } bind S3ScheduledSyncWorkEnqueuer::class
    single { GitWorkManagerScheduledSyncWorkEnqueuer(androidContext()) } bind GitScheduledSyncWorkEnqueuer::class
    single { WebDavWorkManagerScheduledSyncWorkEnqueuer(androidContext()) } bind WebDavScheduledSyncWorkEnqueuer::class

    // Workers
    workerOf(::GitSyncWorker)
    workerOf(::S3SyncWorker)
    workerOf(::SyncWorker)
    workerOf(::WebDavSyncWorker)

    // Remote sync execution
    singleOf(::DefaultRemoteSyncLifecycleRunner) bind RemoteSyncLifecycleRunner::class
    singleOf(::TimberSyncLifecycleExecutionOwner) bind SyncLifecycleExecutionOwner::class

    // S3 sync components
    singleOf(::DefaultS3RefreshSyncPolicyPlanner) bind S3RefreshSyncPolicyPlanner::class
    single {
        S3SyncOperationRepositoryImpl(
            syncExecutor = get(),
            statusTester = get(),
            refreshPolicyPlanner = get(),
            scheduledWorkEnqueuer = get(),
            stateHolder = get(),
            pendingConflictStore = get(),
        )
    } bind S3SyncWorkExecutor::class
    singleOf(::S3SyncStateHolder)
    singleOf(::S3SyncRepositoryContext)
    singleOf(::S3SyncRepositorySupport)
    singleOf(::S3SyncEncodingSupport)
    singleOf(::S3RemoteObjectKeyPolicy)
    single { S3SyncFileBridge(get(), get(), get()) }
    singleOf(::S3SyncActionApplier)
    singleOf(::S3SyncExecutor)
    singleOf(::S3SyncStatusTester)
    singleOf(::S3ConflictResolver)
    singleOf(::S3ReviewResolver)
    singleOf(::S3SyncConfigurationRepositoryImpl)
    singleOf(::S3SyncConfigurationMutationRepositoryImpl)
    singleOf(::S3SyncConflictRepositoryImpl)
    singleOf(::S3SyncReviewRepositoryImpl)
    singleOf(::S3SyncStateRepositoryImpl)
    singleOf(::RoomBackedS3SyncProtocolStateStore) bind S3SyncProtocolStateStore::class
    singleOf(::RoomBackedS3LocalChangeJournalStore) bind S3LocalChangeJournalStore::class
    singleOf(::DefaultS3LocalChangeRecorder) bind S3LocalChangeRecorder::class
    singleOf(::RoomBackedS3RemoteIndexStore) bind S3RemoteIndexStore::class
    singleOf(::RoomBackedS3RemoteShardStateStore) bind S3RemoteShardStateStore::class
    singleOf(::RoomBackedS3SyncTransactionRunner) bind S3SyncTransactionRunner::class
    single { S3CredentialStore(androidContext()) }
    single { S3SyncTransferWorkspace(androidContext()) }

    // WebDav sync components
    singleOf(::RoomBackedWebDavLocalChangeJournalStore) bind WebDavLocalChangeJournalStore::class
    singleOf(::DefaultWebDavLocalChangeRecorder) bind WebDavLocalChangeRecorder::class
    singleOf(::RoomBackedWebDavLocalFingerprintCache) bind WebDavLocalFingerprintCache::class
    singleOf(::WebDavRemoteListingCache)
    singleOf(::WebDavSyncStateHolder)
    singleOf(::WebDavSyncRepositoryContext)
    singleOf(::WebDavSyncRepositorySupport)
    singleOf(::WebDavSyncFileBridge)
    singleOf(::WebDavSyncStatusTester)
    singleOf(::WebDavSyncActionApplier)
    singleOf(::WebDavSyncExecutor)
    singleOf(::WebDavConflictResolver)
    singleOf(::WebDavReviewResolver)
    singleOf(::WebDavSyncConfigurationRepositoryImpl)
    singleOf(::WebDavSyncConfigurationMutationRepositoryImpl)
    singleOf(::WebDavSyncOperationRepositoryImpl)
    singleOf(::WebDavSyncConflictRepositoryImpl)
    singleOf(::WebDavSyncReviewRepositoryImpl)
    singleOf(::WebDavSyncStateRepositoryImpl)
    single { WebDavCredentialStore(androidContext()) }
    single { LocalMediaSyncStore(androidContext(), get()) }

    // Performance tuner
    single { AndroidSyncPerformanceTuner(androidContext()) } bind SyncPerformanceTuner::class

    // Unified sync providers
    single<UnifiedSyncProvider> { GitUnifiedSyncProvider(get()) }
    single<UnifiedSyncProvider> { WebDavUnifiedSyncProvider(get()) }
    single<UnifiedSyncProvider> { S3UnifiedSyncProvider(get()) }
    single<UnifiedSyncProvider> { InboxUnifiedSyncProvider(get(), get()) }
}
