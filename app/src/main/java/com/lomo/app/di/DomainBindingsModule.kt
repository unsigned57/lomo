package com.lomo.app.di

import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateDownloadRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.MigrationArchiveRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.ReminderCoordinator
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.ShareImageRepository
import com.lomo.domain.repository.SyncConflictBackupRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.WebDavSyncRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.BackupSyncConflictFilesUseCase
import com.lomo.domain.usecase.CancelAppUpdateDownloadUseCase
import com.lomo.domain.usecase.CheckStartupAppUpdateUseCase
import com.lomo.domain.usecase.CheckAppUpdateUseCase
import com.lomo.domain.usecase.DiscardMemoDraftAttachmentsUseCase
import com.lomo.domain.usecase.DownloadAndInstallAppUpdateUseCase
import com.lomo.domain.usecase.ExtractShareAttachmentsUseCase
import com.lomo.domain.usecase.ExportAllNotesArchiveUseCase
import com.lomo.domain.usecase.ExportEncryptedSettingsUseCase
import com.lomo.domain.usecase.GitRemoteUrlUseCase
import com.lomo.domain.usecase.GitSyncErrorUseCase
import com.lomo.domain.usecase.GitSyncSettingsUseCase
import com.lomo.domain.usecase.GetCurrentAppVersionUseCase
import com.lomo.domain.usecase.GetLatestAppReleaseUseCase
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.ImportAllNotesArchiveUseCase
import com.lomo.domain.usecase.ImportEncryptedSettingsUseCase
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.MarkReminderDoneUseCase
import com.lomo.domain.usecase.PersistShareImageUseCase
import com.lomo.domain.usecase.PrepareShareCardContentUseCase
import com.lomo.domain.usecase.ResolveMainMemoQueryUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.RestoreMemoRevisionUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.S3SyncSettingsUseCase
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.SyncConflictResolutionUseCase
import com.lomo.domain.usecase.SyncProviderRegistry
import com.lomo.domain.usecase.SyncReviewResolutionUseCase
import com.lomo.domain.usecase.GitUnifiedSyncProvider
import com.lomo.domain.usecase.WebDavUnifiedSyncProvider
import com.lomo.domain.usecase.S3UnifiedSyncProvider
import com.lomo.domain.usecase.InboxUnifiedSyncProvider
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

    @Provides
    @Singleton
    fun provideMarkReminderDoneUseCase(
        reminderCoordinator: ReminderCoordinator,
    ): MarkReminderDoneUseCase = MarkReminderDoneUseCase(reminderCoordinator)

    @Provides
    @Singleton
    fun provideGetCurrentAppVersionUseCase(
        appRuntimeInfoRepository: AppRuntimeInfoRepository,
    ): GetCurrentAppVersionUseCase = GetCurrentAppVersionUseCase(appRuntimeInfoRepository)

    @Provides
    @Singleton
    fun provideSyncProviderRegistry(
        gitSyncRepository: GitSyncRepository,
        webDavSyncRepository: WebDavSyncRepository,
        s3SyncRepository: S3SyncRepository,
        syncInboxRepository: SyncInboxRepository,
        preferencesRepository: PreferencesRepository,
    ): SyncProviderRegistry =
        SyncProviderRegistry(
            providers =
                listOf(
                    GitUnifiedSyncProvider(gitSyncRepository),
                    WebDavUnifiedSyncProvider(webDavSyncRepository),
                    S3UnifiedSyncProvider(s3SyncRepository),
                    InboxUnifiedSyncProvider(
                        syncInboxRepository = syncInboxRepository,
                        preferencesRepository = preferencesRepository,
                    ),
                ),
        )
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
        workspaceStateResolver: WorkspaceStateResolver,
    ): SwitchRootStorageUseCase =
        SwitchRootStorageUseCase(
            directorySettingsRepository = directorySettingsRepository,
            workspaceStateResolver = workspaceStateResolver,
        )

    @Provides
    @Singleton
    fun provideExportAllNotesArchiveUseCase(
        migrationArchiveRepository: MigrationArchiveRepository,
    ): ExportAllNotesArchiveUseCase = ExportAllNotesArchiveUseCase(migrationArchiveRepository)

    @Provides
    @Singleton
    fun provideImportAllNotesArchiveUseCase(
        migrationArchiveRepository: MigrationArchiveRepository,
        workspaceStateResolver: WorkspaceStateResolver,
    ): ImportAllNotesArchiveUseCase =
        ImportAllNotesArchiveUseCase(
            repository = migrationArchiveRepository,
            workspaceStateResolver = workspaceStateResolver,
        )

    @Provides
    @Singleton
    fun provideExportEncryptedSettingsUseCase(
        migrationArchiveRepository: MigrationArchiveRepository,
    ): ExportEncryptedSettingsUseCase = ExportEncryptedSettingsUseCase(migrationArchiveRepository)

    @Provides
    @Singleton
    fun provideImportEncryptedSettingsUseCase(
        migrationArchiveRepository: MigrationArchiveRepository,
    ): ImportEncryptedSettingsUseCase = ImportEncryptedSettingsUseCase(migrationArchiveRepository)

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
        syncProviderRegistry: SyncProviderRegistry,
        appVersionRepository: AppVersionRepository,
        syncInboxRepository: SyncInboxRepository,
    ): StartupMaintenanceUseCase =
        StartupMaintenanceUseCase(
            mediaRepository = mediaRepository,
            initializeWorkspaceUseCase = initializeWorkspaceUseCase,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
            syncProviderRegistry = syncProviderRegistry,
            appVersionRepository = appVersionRepository,
            syncInboxRepository = syncInboxRepository,
        )

}

@Module
@InstallIn(SingletonComponent::class)
object DomainShareBindingsModule {
    @Provides
    @Singleton
    fun provideDownloadAndInstallAppUpdateUseCase(
        appUpdateDownloadRepository: AppUpdateDownloadRepository,
    ): DownloadAndInstallAppUpdateUseCase =
        DownloadAndInstallAppUpdateUseCase(appUpdateDownloadRepository)

    @Provides
    @Singleton
    fun provideCancelAppUpdateDownloadUseCase(
        appUpdateDownloadRepository: AppUpdateDownloadRepository,
    ): CancelAppUpdateDownloadUseCase =
        CancelAppUpdateDownloadUseCase(appUpdateDownloadRepository)

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

    @Provides
    @Singleton
    fun provideCheckAppUpdateUseCase(
        appUpdateRepository: AppUpdateRepository,
        appRuntimeInfoRepository: AppRuntimeInfoRepository,
    ): CheckAppUpdateUseCase =
        CheckAppUpdateUseCase(
            appUpdateRepository = appUpdateRepository,
            appRuntimeInfoRepository = appRuntimeInfoRepository,
        )

    @Provides
    @Singleton
    fun provideGetLatestAppReleaseUseCase(
        appUpdateRepository: AppUpdateRepository,
    ): GetLatestAppReleaseUseCase =
        GetLatestAppReleaseUseCase(
            appUpdateRepository = appUpdateRepository,
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
