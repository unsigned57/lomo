package com.lomo.app.di

import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.UnifiedSyncProvider
import com.lomo.domain.repository.WebDavSyncRepository
import com.lomo.domain.usecase.GitRemoteUrlUseCase
import com.lomo.domain.usecase.GitSyncSettingsUseCase
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.RestoreMemoRevisionUseCase
import com.lomo.domain.usecase.S3SyncSettingsUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.SyncConflictResolutionUseCase
import com.lomo.domain.usecase.SyncProviderRegistry
import com.lomo.domain.usecase.SyncReviewResolutionUseCase
import com.lomo.domain.usecase.WebDavSyncSettingsUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

@Module
@InstallIn(SingletonComponent::class)
object DomainSyncBindingsModule {
    @Provides
    @Singleton
    fun provideSyncProviderRegistry(
        providers: Set<@JvmSuppressWildcards UnifiedSyncProvider>,
    ): SyncProviderRegistry = SyncProviderRegistry(providers)

    @Provides
    @Singleton
    fun provideGitSyncSettingsUseCase(
        gitSyncRepository: GitSyncRepository,
        syncPolicyRepository: SyncPolicyRepository,
        syncAndRebuildUseCase: SyncAndRebuildUseCase,
        gitRemoteUrlUseCase: GitRemoteUrlUseCase,
    ): GitSyncSettingsUseCase =
        GitSyncSettingsUseCase(
            gitSyncRepository = gitSyncRepository,
            syncPolicyRepository = syncPolicyRepository,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
            gitRemoteUrlUseCase = gitRemoteUrlUseCase,
        )

    @Provides
    @Singleton
    fun provideWebDavSyncSettingsUseCase(
        webDavSyncRepository: WebDavSyncRepository,
        syncPolicyRepository: SyncPolicyRepository,
        syncAndRebuildUseCase: SyncAndRebuildUseCase,
    ): WebDavSyncSettingsUseCase =
        WebDavSyncSettingsUseCase(
            webDavSyncRepository = webDavSyncRepository,
            syncPolicyRepository = syncPolicyRepository,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
        )

    @Provides
    @Singleton
    fun provideS3SyncSettingsUseCase(
        s3SyncRepository: S3SyncRepository,
        syncPolicyRepository: SyncPolicyRepository,
        syncAndRebuildUseCase: SyncAndRebuildUseCase,
    ): S3SyncSettingsUseCase =
        S3SyncSettingsUseCase(
            s3SyncRepository = s3SyncRepository,
            syncPolicyRepository = syncPolicyRepository,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
        )

    @Provides
    @Singleton
    fun provideLoadMemoRevisionHistoryUseCase(
        memoVersionRepository: MemoVersionRepository,
    ): LoadMemoRevisionHistoryUseCase =
        LoadMemoRevisionHistoryUseCase(memoVersionRepository)

    @Provides
    @Singleton
    fun provideRestoreMemoRevisionUseCase(
        memoMutationRepository: MemoMutationRepository,
    ): RestoreMemoRevisionUseCase =
        RestoreMemoRevisionUseCase(memoMutationRepository)

    @Provides
    @Singleton
    fun provideSyncConflictResolutionUseCase(
        syncProviderRegistry: SyncProviderRegistry,
        memoMutationRepository: MemoMutationRepository,
    ): SyncConflictResolutionUseCase =
        SyncConflictResolutionUseCase(
            syncProviderRegistry = syncProviderRegistry,
            memoRepository = memoMutationRepository,
        )

    @Provides
    @Singleton
    fun provideSyncReviewResolutionUseCase(
        syncProviderRegistry: SyncProviderRegistry,
    ): SyncReviewResolutionUseCase =
        SyncReviewResolutionUseCase(
            syncProviderRegistry = syncProviderRegistry,
        )
}
