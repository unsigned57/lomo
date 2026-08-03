package com.lomo.data.di

import android.content.Context

import com.lomo.data.repository.AppPreferencesSnapshotRepositoryImpl
import com.lomo.data.repository.DailyReviewSessionRepositoryImpl
import com.lomo.data.repository.DataStoreMigrationSettingsStore
import com.lomo.data.repository.DataStoreWorkspaceSyncGenerationProvider
import com.lomo.data.engine.ManagedEngineSession
import com.lomo.data.engine.archive.ArchivePort
import com.lomo.data.engine.archive.BoltFfiArchivePort
import com.lomo.data.engine.media.BoltFfiMediaPort
import com.lomo.data.engine.media.MediaPort
import com.lomo.data.engine.media.MediaSyncEdgeAdapter
import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.repository.MediaEdgeRepository
import com.lomo.data.repository.MigrationSettingsStore
import com.lomo.data.repository.SettingsRepositoryImpl
import com.lomo.data.repository.WorkspaceArchiveEdgeRepository
import com.lomo.data.repository.ShareImageRepositoryImpl
import com.lomo.data.repository.SyncInboxRepositoryImpl
import com.lomo.data.repository.SyncStateResetRepositoryImpl
import com.lomo.data.repository.ProcessWorkspaceMutationLease
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
import com.lomo.data.local.datastore.LomoLanSharePreferencesStore
import com.lomo.data.security.DataStoreSecuritySessionPolicy
import com.lomo.data.security.DefaultCredentialRepository
import com.lomo.data.git.GitCredentialStore
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.webdav.WebDavCredentialStore
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
import org.koin.dsl.module
import org.koin.core.module.dsl.singleOf
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.binds

val coreDataRepositoryModule = module {
    singleOf(::ShareImageRepositoryImpl) bind ShareImageRepository::class

    single { LomoDataStore(androidContext()) }
    single<LomoLanSharePreferencesStore> { get<LomoDataStore>() }

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

    single { ProcessWorkspaceMutationLease(engineReadinessRepository = get()) } bind
        com.lomo.domain.repository.WorkspaceMutationLease::class


    singleOf(::SyncStateResetRepositoryImpl) bind SyncStateResetRepository::class
    singleOf(::DataStoreWorkspaceSyncGenerationProvider) bind WorkspaceSyncGenerationProvider::class

    // P4-10A/B: path-only media/archive ports over ManagedEngineSession; settings stay Kotlin.
    single<WorkspaceFilesystemRoot> {
        WorkspaceFilesystemRoot {
            val raw = get<ManagedEngineSession>().activeWorkspaceLocation.value?.raw
            if (raw.isNullOrBlank() || raw.startsWith("content:", ignoreCase = true)) {
                null
            } else {
                java.io.File(raw).absolutePath
            }
        }
    }
    single { com.lomo.data.engine.media.PendingMediaStageRegistry() }
    single<MediaPort> { BoltFfiMediaPort(bridge = get<ManagedEngineSession>()) }
    single { MediaSyncEdgeAdapter() }
    single {
        MediaEdgeRepository(
            context = androidContext(),
            workspaceConfigSource = get(),
            mediaStorageDataSource = get(),
            mediaPort = get(),
            workspaceRoot = get(),
            syncEdge = get(),
            writeLease = get(),
            storePort = get(),
            pendingStages = get(),
        )
    } bind MediaRepository::class
    single<ArchivePort> { BoltFfiArchivePort(bridge = get<ManagedEngineSession>()) }

    // Credentials / Security
    single { GitCredentialStore(get<Context>()) }
    single { WebDavCredentialStore(get<Context>()) }
    single { S3CredentialStore(get<Context>()) }
    singleOf(::DefaultCredentialRepository) bind CredentialRepository::class
    single { DataStoreSecuritySessionPolicy(get()) } binds arrayOf(
        SecuritySessionPolicy::class,
        SecuritySessionController::class
    )

    // Migration (workspace archive v2 via Rust; encrypted settings remain Kotlin)
    singleOf(::DataStoreMigrationSettingsStore) bind MigrationSettingsStore::class
    single {
        WorkspaceArchiveEdgeRepository(
            context = androidContext(),
            archivePort = get(),
            workspaceRoot = get(),
            settingsStore = get(),
        )
    } bind MigrationArchiveRepository::class

    // Inbox
    singleOf(::SyncInboxRepositoryImpl) bind SyncInboxRepository::class
    singleOf(::DailyReviewSessionRepositoryImpl) bind DailyReviewSessionRepository::class

    // Snapshot Preferences
    single { MemoSnapshotPreferencesRepositoryImpl(get()) } bind MemoSnapshotPreferencesRepository::class
}
