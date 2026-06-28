package com.lomo.data.di

import com.lomo.data.git.FileGitMediaSyncStateStore
import com.lomo.data.git.GitMediaSyncPlanner
import com.lomo.data.git.GitMediaSyncStateStore
import com.lomo.data.git.GitMediaSyncWorkspaceStateStore
import com.lomo.data.git.RawGitMediaSyncStateStore
import com.lomo.data.repository.AndroidS3SafTreeAccess
import com.lomo.data.repository.AppVersionRepositoryImpl
import com.lomo.data.repository.GitSyncRepositoryImpl
import com.lomo.data.repository.S3SafTreeAccess
import com.lomo.data.repository.S3SyncPlanner
import com.lomo.data.repository.S3SyncRepositoryImpl
import com.lomo.data.repository.SyncPolicyRepositoryImpl
import com.lomo.data.repository.WebDavSyncPlanner
import com.lomo.data.repository.WebDavSyncRepositoryImpl
import com.lomo.data.s3.AwsSdkS3ClientFactory
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.sync.SyncConflictBackupManager
import com.lomo.data.webdav.Dav4jvmWebDavClientFactory
import com.lomo.data.webdav.DefaultWebDavEndpointResolver
import com.lomo.data.webdav.WebDavClientFactory
import com.lomo.data.webdav.WebDavEndpointResolver
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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncRepositoryModule {
    @Provides
    @Singleton
    fun provideWebDavSyncPlanner(): WebDavSyncPlanner = WebDavSyncPlanner()

    @Provides
    @Singleton
    fun provideGitMediaSyncPlanner(): GitMediaSyncPlanner = GitMediaSyncPlanner()

    @Provides
    @Singleton
    fun provideWebDavClientFactory(
        factory: Dav4jvmWebDavClientFactory,
    ): WebDavClientFactory = factory

    @Provides
    @Singleton
    fun provideWebDavEndpointResolver(
        resolver: DefaultWebDavEndpointResolver,
    ): WebDavEndpointResolver = resolver

    @Provides
    @Singleton
    fun provideRawGitMediaSyncStateStore(
        impl: FileGitMediaSyncStateStore,
    ): RawGitMediaSyncStateStore = impl

    @Provides
    @Singleton
    fun provideGitMediaSyncStateStore(
        impl: GitMediaSyncWorkspaceStateStore,
    ): GitMediaSyncStateStore = impl

    @Provides
    @Singleton
    fun provideGitSyncRepository(impl: GitSyncRepositoryImpl): GitSyncRepository = impl

    @Provides
    @Singleton
    fun provideWebDavSyncRepository(impl: WebDavSyncRepositoryImpl): WebDavSyncRepository = impl

    @Provides
    @Singleton
    fun provideAppVersionRepository(impl: AppVersionRepositoryImpl): AppVersionRepository = impl

    @Provides
    @Singleton
    fun provideSyncPolicyRepository(impl: SyncPolicyRepositoryImpl): SyncPolicyRepository = impl

    @Provides
    @Singleton
    fun provideSyncConflictBackupRepository(impl: SyncConflictBackupManager): SyncConflictBackupRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object S3SyncModule {
    @Provides
    @Singleton
    fun provideS3SyncPlanner(): S3SyncPlanner = S3SyncPlanner()

    @Provides
    @Singleton
    fun provideS3SafTreeAccess(
        impl: AndroidS3SafTreeAccess,
    ): S3SafTreeAccess = impl

    @Provides
    @Singleton
    fun provideLomoS3ClientFactory(
        factory: AwsSdkS3ClientFactory,
    ): LomoS3ClientFactory = factory

    @Provides
    @Singleton
    fun provideS3SyncRepository(impl: S3SyncRepositoryImpl): S3SyncRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object UnifiedSyncProviderModule {
    @Provides
    @IntoSet
    fun provideGitUnifiedSyncProvider(
        repository: GitSyncRepository,
    ): UnifiedSyncProvider = GitUnifiedSyncProvider(repository)

    @Provides
    @IntoSet
    fun provideWebDavUnifiedSyncProvider(
        repository: WebDavSyncRepository,
    ): UnifiedSyncProvider = WebDavUnifiedSyncProvider(repository)

    @Provides
    @IntoSet
    fun provideS3UnifiedSyncProvider(
        repository: S3SyncRepository,
    ): UnifiedSyncProvider = S3UnifiedSyncProvider(repository)

    @Provides
    @IntoSet
    fun provideInboxUnifiedSyncProvider(
        syncInboxRepository: SyncInboxRepository,
        preferencesRepository: PreferencesRepository,
    ): UnifiedSyncProvider =
        InboxUnifiedSyncProvider(
            syncInboxRepository = syncInboxRepository,
            preferencesRepository = preferencesRepository,
        )
}
