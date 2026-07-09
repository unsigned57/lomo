package com.lomo.data.di

import android.content.Context

import com.lomo.data.repository.AppPreferencesSnapshotRepositoryImpl
import com.lomo.data.repository.DailyReviewSessionRepositoryImpl
import com.lomo.data.repository.DataStoreMigrationSettingsStore
import com.lomo.data.repository.DataStoreWorkspaceSyncGenerationProvider
import com.lomo.data.repository.FileMigrationArchiveStagingWorkspaceFactory
import com.lomo.data.repository.MediaRepositoryImpl
import com.lomo.data.repository.MigrationArchiveRepositoryImpl
import com.lomo.data.repository.MigrationArchiveStagingWorkspaceFactory
import com.lomo.data.repository.MigrationSettingsStore
import com.lomo.data.repository.MigrationArchiveImportBudgets
import com.lomo.data.repository.SettingsRepositoryImpl
import com.lomo.data.repository.ShareImageRepositoryImpl
import com.lomo.data.repository.SyncInboxRepositoryImpl
import com.lomo.data.repository.SyncStateResetRepositoryImpl
import com.lomo.data.repository.WorkspaceTransitionRepositoryImpl
import com.lomo.data.repository.DirectorySettingsRepositoryImpl
import com.lomo.data.repository.PreferencesRepositoryImpl
import com.lomo.data.repository.DateTimePreferencesRepositoryImpl
import com.lomo.data.repository.StoragePreferencesRepositoryImpl
import com.lomo.data.repository.InteractionPreferencesRepositoryImpl
import com.lomo.data.repository.InteractionBehaviorPreferencesRepositoryImpl
import com.lomo.data.repository.MemoActionPreferencesRepositoryImpl
import com.lomo.data.repository.InputToolbarPreferencesRepositoryImpl
import com.lomo.data.repository.SidebarTagOrderPreferencesRepositoryImpl
import com.lomo.data.repository.SecurityPreferencesRepositoryImpl
import com.lomo.data.repository.ShareCardPreferencesRepositoryImpl
import com.lomo.data.repository.DraftPreferencesRepositoryImpl
import com.lomo.data.repository.SyncInboxPreferencesRepositoryImpl
import com.lomo.data.repository.MemoSnapshotPreferencesRepositoryImpl
import com.lomo.data.repository.TypographyPreferencesRepositoryImpl
import com.lomo.data.repository.ColorSchemePreferencesRepositoryImpl
import com.lomo.data.repository.FontPreferencesRepositoryImpl
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.withDriverTransaction
import com.lomo.data.security.DataStoreSecuritySessionPolicy
import com.lomo.data.security.DefaultCredentialRepository
import com.lomo.data.git.GitCredentialStore
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
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.binds

val coreDataRepositoryModule = module {
    singleOf(::ShareImageRepositoryImpl) bind ShareImageRepository::class

    single { LomoDataStore(androidContext()) }

    // Pref delegates
    single { DirectorySettingsRepositoryImpl(get(), get()) }
    single { DateTimePreferencesRepositoryImpl(get()) }
    single { StoragePreferencesRepositoryImpl(get()) }
    single { InteractionPreferencesRepositoryImpl(get()) }
    single { InteractionBehaviorPreferencesRepositoryImpl(get()) }
    single { MemoActionPreferencesRepositoryImpl(get()) }
    single { InputToolbarPreferencesRepositoryImpl(get()) }
    single { SidebarTagOrderPreferencesRepositoryImpl(get()) }
    single { SecurityPreferencesRepositoryImpl(get()) }
    single { ShareCardPreferencesRepositoryImpl(get()) }
    single { DraftPreferencesRepositoryImpl(get()) }
    single { SyncInboxPreferencesRepositoryImpl(get()) }
    single { MemoSnapshotPreferencesRepositoryImpl(get()) }
    single { TypographyPreferencesRepositoryImpl(get()) }
    single { ColorSchemePreferencesRepositoryImpl(get()) }
    single { FontPreferencesRepositoryImpl(get()) }

    single {
        PreferencesRepositoryImpl(
            get(), get(), get(), get(), get(),
            get(), get(), get(), get(), get(),
            get(), get(), get(), get()
        )
    }

    single { SettingsRepositoryImpl(get(), get()) } binds arrayOf(
        AppConfigRepository::class,
        DirectorySettingsRepository::class,
        PreferencesRepository::class,
        InteractionPreferencesRepository::class,
        SecurityPreferencesRepository::class,
        SidebarTagOrderPreferencesRepository::class
    )

    singleOf(::AppPreferencesSnapshotRepositoryImpl) bind AppPreferencesSnapshotRepository::class
    single { com.lomo.data.repository.CustomFontStoreImpl(androidContext()) } bind CustomFontStore::class

    single {
        WorkspaceTransitionRepositoryImpl(
            memoWriteDao = get(),
            memoOutboxDao = get(),
            memoTagDao = get(),
            memoImageDao = get(),
            memoTrashDao = get(),
            localFileStateDao = get(),
            syncStateResetRepository = get(),
            runInTransaction = { block ->
                get<com.lomo.data.local.MemoDatabase>().withDriverTransaction {
                    block()
                }
            }
        )
    } bind WorkspaceTransitionRepository::class

    singleOf(::SyncStateResetRepositoryImpl) bind SyncStateResetRepository::class
    singleOf(::DataStoreWorkspaceSyncGenerationProvider) bind WorkspaceSyncGenerationProvider::class
    singleOf(::MediaRepositoryImpl) bind MediaRepository::class

    // Credentials / Security
    single { GitCredentialStore(get<Context>()) }
    singleOf(::DefaultCredentialRepository) bind CredentialRepository::class
    single { DataStoreSecuritySessionPolicy(get()) } binds arrayOf(
        SecuritySessionPolicy::class,
        SecuritySessionController::class
    )

    // Migration
    single { MigrationArchiveImportBudgets() }
    singleOf(::DataStoreMigrationSettingsStore) bind MigrationSettingsStore::class
    singleOf(::FileMigrationArchiveStagingWorkspaceFactory) bind MigrationArchiveStagingWorkspaceFactory::class
    singleOf(::MigrationArchiveRepositoryImpl) bind MigrationArchiveRepository::class

    // Inbox
    singleOf(::SyncInboxRepositoryImpl) bind SyncInboxRepository::class
    singleOf(::DailyReviewSessionRepositoryImpl) bind DailyReviewSessionRepository::class

    // Snapshot Preferences
    single { MemoSnapshotPreferencesRepositoryImpl(get()) } bind MemoSnapshotPreferencesRepository::class
}
