package com.lomo.app.di

import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.SetMemoPinnedUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.SyncProviderRegistry
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainMemoMutationBindingsModule {
    @Provides
    @Singleton
    fun provideDeleteMemoUseCase(
        memoMutationRepository: MemoMutationRepository,
    ): DeleteMemoUseCase = DeleteMemoUseCase(memoMutationRepository)

    @Provides
    @Singleton
    fun provideSetMemoPinnedUseCase(
        memoMutationRepository: MemoMutationRepository,
    ): SetMemoPinnedUseCase = SetMemoPinnedUseCase(memoMutationRepository)

    @Provides
    @Singleton
    fun provideSyncAndRebuildUseCase(
        memoMutationRepository: MemoMutationRepository,
        syncProviderRegistry: SyncProviderRegistry,
        syncPolicyRepository: SyncPolicyRepository,
    ): SyncAndRebuildUseCase =
        SyncAndRebuildUseCase(
            memoRepository = memoMutationRepository,
            syncProviderRegistry = syncProviderRegistry,
            syncPolicyRepository = syncPolicyRepository,
        )

    @Provides
    @Singleton
    fun provideRefreshMemosUseCase(syncAndRebuildUseCase: SyncAndRebuildUseCase): RefreshMemosUseCase =
        RefreshMemosUseCase(syncAndRebuildUseCase)

    @Provides
    @Singleton
    fun provideToggleMemoCheckboxUseCase(
        memoMutationRepository: MemoMutationRepository,
        validateMemoContentUseCase: ValidateMemoContentUseCase,
    ): ToggleMemoCheckboxUseCase =
        ToggleMemoCheckboxUseCase(
            repository = memoMutationRepository,
            validator = validateMemoContentUseCase,
        )

    @Provides
    @Singleton
    fun provideUpdateMemoContentUseCase(
        memoMutationRepository: MemoMutationRepository,
        validateMemoContentUseCase: ValidateMemoContentUseCase,
        resolveMemoUpdateActionUseCase: ResolveMemoUpdateActionUseCase,
        deleteMemoUseCase: DeleteMemoUseCase,
    ): UpdateMemoContentUseCase =
        UpdateMemoContentUseCase(
            repository = memoMutationRepository,
            validator = validateMemoContentUseCase,
            resolveMemoUpdateActionUseCase = resolveMemoUpdateActionUseCase,
            deleteMemoUseCase = deleteMemoUseCase,
        )

    @Provides
    @Singleton
    fun provideCreateMemoUseCase(
        memoMutationRepository: MemoMutationRepository,
        initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
        validateMemoContentUseCase: ValidateMemoContentUseCase,
    ): CreateMemoUseCase =
        CreateMemoUseCase(
            memoRepository = memoMutationRepository,
            initializeWorkspaceUseCase = initializeWorkspaceUseCase,
            validator = validateMemoContentUseCase,
        )
}
