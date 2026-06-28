package com.lomo.app.di

import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MigrationArchiveRepository
import com.lomo.domain.repository.SyncConflictBackupRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.BackupSyncConflictFilesUseCase
import com.lomo.domain.usecase.DiscardMemoDraftAttachmentsUseCase
import com.lomo.domain.usecase.ExportAllNotesArchiveUseCase
import com.lomo.domain.usecase.ExportEncryptedSettingsUseCase
import com.lomo.domain.usecase.ImportAllNotesArchiveUseCase
import com.lomo.domain.usecase.ImportEncryptedSettingsUseCase
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.StartupMaintenanceUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.SyncProviderRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
