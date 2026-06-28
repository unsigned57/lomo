package com.lomo.data.di

import com.lomo.data.repository.AppPreferencesSnapshotRepositoryImpl
import com.lomo.data.repository.DailyReviewSessionRepositoryImpl
import com.lomo.data.repository.DataStoreMigrationSettingsStore
import com.lomo.data.repository.DataStoreWorkspaceSyncGenerationProvider
import com.lomo.data.repository.FileMigrationArchiveStagingWorkspaceFactory
import com.lomo.data.repository.MediaRepositoryImpl
import com.lomo.data.repository.MigrationArchiveRepositoryImpl
import com.lomo.data.repository.MigrationArchiveStagingWorkspaceFactory
import com.lomo.data.repository.MigrationSettingsStore
import com.lomo.data.repository.SettingsRepositoryImpl
import com.lomo.data.repository.ShareImageRepositoryImpl
import com.lomo.data.repository.SyncInboxRepositoryImpl
import com.lomo.data.repository.SyncStateResetRepositoryImpl
import com.lomo.data.repository.WorkspaceTransitionRepositoryImpl
import com.lomo.data.security.DataStoreSecuritySessionPolicy
import com.lomo.data.security.DefaultCredentialRepository
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.AppPreferencesSnapshotRepository
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.repository.CustomFontStore
import com.lomo.domain.repository.DailyReviewSessionRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.InteractionPreferencesRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MigrationArchiveRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.SecurityPreferencesRepository
import com.lomo.domain.repository.SecuritySessionController
import com.lomo.domain.repository.SecuritySessionPolicy
import com.lomo.domain.repository.ShareImageRepository
import com.lomo.domain.repository.SidebarTagOrderPreferencesRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.SyncStateResetRepository
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import com.lomo.domain.repository.WorkspaceTransitionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreRepositoryModule {
    @Provides
    @Singleton
    fun provideShareImageRepository(impl: ShareImageRepositoryImpl): ShareImageRepository = impl

    @Provides
    @Singleton
    fun provideAppConfigRepository(impl: SettingsRepositoryImpl): AppConfigRepository = impl

    @Provides
    @Singleton
    fun provideDirectorySettingsRepository(impl: SettingsRepositoryImpl): DirectorySettingsRepository = impl

    @Provides
    @Singleton
    fun providePreferencesRepository(impl: SettingsRepositoryImpl): PreferencesRepository = impl

    @Provides
    @Singleton
    fun provideAppPreferencesSnapshotRepository(
        impl: AppPreferencesSnapshotRepositoryImpl,
    ): AppPreferencesSnapshotRepository = impl

    @Provides
    @Singleton
    fun provideCustomFontStore(
        impl: com.lomo.data.repository.CustomFontStoreImpl,
    ): CustomFontStore = impl

    @Provides
    @Singleton
    fun provideWorkspaceTransitionRepository(
        impl: WorkspaceTransitionRepositoryImpl,
    ): WorkspaceTransitionRepository = impl

    @Provides
    @Singleton
    fun provideSyncStateResetRepository(
        impl: SyncStateResetRepositoryImpl,
    ): SyncStateResetRepository = impl

    @Provides
    @Singleton
    fun provideWorkspaceSyncGenerationProvider(
        impl: DataStoreWorkspaceSyncGenerationProvider,
    ): WorkspaceSyncGenerationProvider = impl

    @Provides
    @Singleton
    fun provideMediaRepository(impl: MediaRepositoryImpl): MediaRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object CredentialRepositoryModule {
    @Provides
    @Singleton
    fun provideCredentialRepository(impl: DefaultCredentialRepository): CredentialRepository = impl

    @Provides
    @Singleton
    fun provideSecuritySessionPolicy(impl: DataStoreSecuritySessionPolicy): SecuritySessionPolicy = impl

    @Provides
    @Singleton
    fun provideSecuritySessionController(impl: DataStoreSecuritySessionPolicy): SecuritySessionController = impl
}

@Module
@InstallIn(SingletonComponent::class)
object MigrationRepositoryModule {
    @Provides
    @Singleton
    fun provideMigrationSettingsStore(impl: DataStoreMigrationSettingsStore): MigrationSettingsStore = impl

    @Provides
    @Singleton
    fun provideMigrationArchiveStagingWorkspaceFactory(
        impl: FileMigrationArchiveStagingWorkspaceFactory,
    ): MigrationArchiveStagingWorkspaceFactory = impl

    @Provides
    @Singleton
    fun provideMigrationArchiveRepository(impl: MigrationArchiveRepositoryImpl): MigrationArchiveRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object InboxRepositoryModule {
    @Provides
    @Singleton
    fun provideSyncInboxRepository(impl: SyncInboxRepositoryImpl): SyncInboxRepository = impl

    @Provides
    @Singleton
    fun provideDailyReviewSessionRepository(
        impl: DailyReviewSessionRepositoryImpl,
    ): DailyReviewSessionRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object SnapshotPreferencesRepositoryModule {
    @Provides
    @Singleton
    fun provideMemoSnapshotPreferencesRepository(
        impl: com.lomo.data.repository.MemoSnapshotPreferencesRepositoryImpl,
    ): MemoSnapshotPreferencesRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object PreferenceFacetRepositoryModule {
    @Provides
    @Singleton
    fun provideInteractionPreferencesRepository(
        impl: SettingsRepositoryImpl,
    ): InteractionPreferencesRepository = impl

    @Provides
    @Singleton
    fun provideSecurityPreferencesRepository(
        impl: SettingsRepositoryImpl,
    ): SecurityPreferencesRepository = impl

    @Provides
    @Singleton
    fun provideSidebarTagOrderPreferencesRepository(
        impl: SettingsRepositoryImpl,
    ): SidebarTagOrderPreferencesRepository = impl
}
