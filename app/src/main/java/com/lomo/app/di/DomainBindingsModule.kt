package com.lomo.app.di

import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.ShareImageRepository
import com.lomo.domain.repository.SyncConflictBackupRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.WebDavSyncRepository
import com.lomo.domain.repository.WorkspaceTransitionRepository
import com.lomo.domain.usecase.ApplyMainMemoFilterUseCase
import com.lomo.domain.usecase.BackupSyncConflictFilesUseCase
import com.lomo.domain.usecase.CheckStartupAppUpdateUseCase
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.DiscardMemoDraftAttachmentsUseCase
import com.lomo.domain.usecase.ExtractShareAttachmentsUseCase
import com.lomo.domain.usecase.GitRemoteUrlUseCase
import com.lomo.domain.usecase.GitSyncErrorUseCase
import com.lomo.domain.usecase.GitSyncSettingsUseCase
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.ObserveDraftTextUseCase
import com.lomo.domain.usecase.PersistShareImageUseCase
import com.lomo.domain.usecase.PrepareShareCardContentUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.ResolveMainMemoQueryUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.RestoreMemoRevisionUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.SetDraftTextUseCase
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.SyncConflictResolutionUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import com.lomo.domain.usecase.WebDavSyncSettingsUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainCoreBindingsModule {
    @Provides
    @Singleton
    fun provideApplyMainMemoFilterUseCase(): ApplyMainMemoFilterUseCase = ApplyMainMemoFilterUseCase()

    @Provides
    @Singleton
    fun provideValidateMemoContentUseCase(): ValidateMemoContentUseCase = ValidateMemoContentUseCase()

    @Provides
    @Singleton
    fun provideResolveMemoUpdateActionUseCase(): ResolveMemoUpdateActionUseCase = ResolveMemoUpdateActionUseCase()

    @Provides
    @Singleton
    fun provideGitRemoteUrlUseCase(): GitRemoteUrlUseCase = GitRemoteUrlUseCase()

    @Provides
    @Singleton
    fun provideGitSyncErrorUseCase(): GitSyncErrorUseCase = GitSyncErrorUseCase()

    @Provides
    @Singleton
    fun provideResolveMainMemoQueryUseCase(): ResolveMainMemoQueryUseCase = ResolveMainMemoQueryUseCase()
}

@Module
@InstallIn(SingletonComponent::class)
object DomainMemoBindingsModule {
    @Provides
    @Singleton
    fun provideDeleteMemoUseCase(
        memoRepository: MemoRepository,
    ): DeleteMemoUseCase = DeleteMemoUseCase(memoRepository)

    @Provides
    @Singleton
    fun provideSyncAndRebuildUseCase(
        memoRepository: MemoRepository,
        gitSyncRepository: GitSyncRepository,
        webDavSyncRepository: WebDavSyncRepository,
        syncPolicyRepository: SyncPolicyRepository,
    ): SyncAndRebuildUseCase =
        SyncAndRebuildUseCase(
            memoRepository = memoRepository,
            gitSyncRepository = gitSyncRepository,
            webDavSyncRepository = webDavSyncRepository,
            syncPolicyRepository = syncPolicyRepository,
        )

    @Provides
    @Singleton
    fun provideRefreshMemosUseCase(syncAndRebuildUseCase: SyncAndRebuildUseCase): RefreshMemosUseCase =
        RefreshMemosUseCase(syncAndRebuildUseCase)

    @Provides
    @Singleton
    fun provideToggleMemoCheckboxUseCase(
        memoRepository: MemoRepository,
        validateMemoContentUseCase: ValidateMemoContentUseCase,
    ): ToggleMemoCheckboxUseCase =
        ToggleMemoCheckboxUseCase(
            repository = memoRepository,
            validator = validateMemoContentUseCase,
        )

    @Provides
    @Singleton
    fun provideUpdateMemoContentUseCase(
        memoRepository: MemoRepository,
        validateMemoContentUseCase: ValidateMemoContentUseCase,
        resolveMemoUpdateActionUseCase: ResolveMemoUpdateActionUseCase,
        deleteMemoUseCase: DeleteMemoUseCase,
    ): UpdateMemoContentUseCase =
        UpdateMemoContentUseCase(
            repository = memoRepository,
            validator = validateMemoContentUseCase,
            resolveMemoUpdateActionUseCase = resolveMemoUpdateActionUseCase,
            deleteMemoUseCase = deleteMemoUseCase,
        )

    @Provides
    @Singleton
    fun provideCreateMemoUseCase(
        memoRepository: MemoRepository,
        initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
        validateMemoContentUseCase: ValidateMemoContentUseCase,
        ): CreateMemoUseCase =
        CreateMemoUseCase(
            memoRepository = memoRepository,
            initializeWorkspaceUseCase = initializeWorkspaceUseCase,
            validator = validateMemoContentUseCase,
        )

    @Provides
    @Singleton
    fun provideDailyReviewQueryUseCase(
        memoRepository: MemoRepository,
    ): DailyReviewQueryUseCase = DailyReviewQueryUseCase(memoRepository)

    @Provides
    @Singleton
    fun provideObserveDraftTextUseCase(
        preferencesRepository: PreferencesRepository,
    ): ObserveDraftTextUseCase = ObserveDraftTextUseCase(preferencesRepository)

    @Provides
    @Singleton
    fun provideSetDraftTextUseCase(
        preferencesRepository: PreferencesRepository,
    ): SetDraftTextUseCase = SetDraftTextUseCase(preferencesRepository)
}

@Module
@InstallIn(SingletonComponent::class)
object DomainWorkspaceBindingsModule {
    @Provides
    @Singleton
    fun provideBackupSyncConflictFilesUseCase(
        repository: SyncConflictBackupRepository,
    ): BackupSyncConflictFilesUseCase = BackupSyncConflictFilesUseCase(repository)

    @Provides
    @Singleton
    fun provideInitializeWorkspaceUseCase(
        directorySettingsRepository: DirectorySettingsRepository,
        mediaRepository: MediaRepository,
    ): InitializeWorkspaceUseCase =
        InitializeWorkspaceUseCase(
            directorySettingsRepository = directorySettingsRepository,
            mediaRepository = mediaRepository,
        )

    @Provides
    @Singleton
    fun provideSwitchRootStorageUseCase(
        directorySettingsRepository: DirectorySettingsRepository,
        rootSwitchCleanupRepository: WorkspaceTransitionRepository,
    ): SwitchRootStorageUseCase =
        SwitchRootStorageUseCase(
            directorySettingsRepository = directorySettingsRepository,
            cleanupRepository = rootSwitchCleanupRepository,
        )

    @Provides
    @Singleton
    fun provideSaveImageUseCase(
        mediaRepository: MediaRepository,
    ): SaveImageUseCase = SaveImageUseCase(mediaRepository)

    @Provides
    @Singleton
    fun provideDiscardMemoDraftAttachmentsUseCase(
        mediaRepository: MediaRepository,
    ): DiscardMemoDraftAttachmentsUseCase =
        DiscardMemoDraftAttachmentsUseCase(mediaRepository)

    @Provides
    @Singleton
    fun provideStartupMaintenanceUseCase(
        mediaRepository: MediaRepository,
        initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
        syncAndRebuildUseCase: SyncAndRebuildUseCase,
        appVersionRepository: AppVersionRepository,
    ): StartupMaintenanceUseCase =
        StartupMaintenanceUseCase(
            mediaRepository = mediaRepository,
            initializeWorkspaceUseCase = initializeWorkspaceUseCase,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
            appVersionRepository = appVersionRepository,
        )
}

@Module
@InstallIn(SingletonComponent::class)
object DomainShareBindingsModule {
    @Provides
    @Singleton
    fun providePrepareShareCardContentUseCase(): PrepareShareCardContentUseCase = PrepareShareCardContentUseCase()

    @Provides
    @Singleton
    fun provideExtractShareAttachmentsUseCase(): ExtractShareAttachmentsUseCase = ExtractShareAttachmentsUseCase()

    @Provides
    @Singleton
    fun providePersistShareImageUseCase(
        repository: ShareImageRepository,
    ): PersistShareImageUseCase = PersistShareImageUseCase(repository)

    @Provides
    @Singleton
    fun provideCheckStartupAppUpdateUseCase(
        preferencesRepository: PreferencesRepository,
        appUpdateRepository: AppUpdateRepository,
        appRuntimeInfoRepository: AppRuntimeInfoRepository,
    ): CheckStartupAppUpdateUseCase =
        CheckStartupAppUpdateUseCase(
            preferencesRepository = preferencesRepository,
            appUpdateRepository = appUpdateRepository,
            appRuntimeInfoRepository = appRuntimeInfoRepository,
        )
}

@Module
@InstallIn(SingletonComponent::class)
object DomainSyncBindingsModule {
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
    fun provideLoadMemoRevisionHistoryUseCase(
        memoVersionRepository: MemoVersionRepository,
    ): LoadMemoRevisionHistoryUseCase =
        LoadMemoRevisionHistoryUseCase(memoVersionRepository)

    @Provides
    @Singleton
    fun provideRestoreMemoRevisionUseCase(
        memoVersionRepository: MemoVersionRepository,
    ): RestoreMemoRevisionUseCase =
        RestoreMemoRevisionUseCase(memoVersionRepository)

    @Provides
    @Singleton
    fun provideSyncConflictResolutionUseCase(
        gitSyncRepository: GitSyncRepository,
        webDavSyncRepository: WebDavSyncRepository,
        memoRepository: MemoRepository,
    ): SyncConflictResolutionUseCase =
        SyncConflictResolutionUseCase(
            gitSyncRepository = gitSyncRepository,
            webDavSyncRepository = webDavSyncRepository,
            memoRepository = memoRepository,
        )
}
