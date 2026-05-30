package com.lomo.data.di

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lomo.data.local.ALL_DATABASE_MIGRATIONS
import com.lomo.data.local.ALL_DATABASE_MIGRATION_EDGES
import com.lomo.data.local.DatabaseTransitionStrategy
import com.lomo.data.local.MEMO_DATABASE_VERSION
import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.withDriverTransaction
import com.lomo.data.local.withDriverTransactionAndSuspendedMemoFtsTriggers
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoBrowseDao
import com.lomo.data.local.dao.MemoFtsDao
import com.lomo.data.local.dao.MemoImageDao
import com.lomo.data.local.dao.MemoIdentityDao
import com.lomo.data.local.dao.MemoOutboxDao
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.data.local.dao.MemoStatisticsDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoVersionDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.dao.RoomMemoFtsDao
import com.lomo.data.local.dao.PendingSyncConflictDao
import com.lomo.data.local.dao.PendingSyncReviewDao
import com.lomo.data.local.dao.RawS3SyncMetadataDao
import com.lomo.data.local.dao.RawWebDavSyncMetadataDao
import com.lomo.data.local.dao.S3LocalChangeJournalDao
import com.lomo.data.local.dao.S3RemoteIndexDao
import com.lomo.data.local.dao.S3RemoteShardStateDao
import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncProtocolStateDao
import com.lomo.data.local.dao.SyncStateResetDao
import com.lomo.data.local.dao.WebDavLocalChangeJournalDao
import com.lomo.data.local.dao.WebDavLocalFingerprintDao
import com.lomo.data.local.dao.WebDavSyncMetadataDao
import com.lomo.data.s3.AwsSdkS3ClientFactory
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.repository.AppUpdateApkDownloader
import com.lomo.data.repository.AppUpdateApkVerifier
import com.lomo.data.repository.AppUpdateInstallerResultObserver
import com.lomo.data.repository.AppRuntimeInfoRepositoryImpl
import com.lomo.data.repository.AppUpdateDownloadRepositoryImpl
import com.lomo.data.repository.AppUpdateTransportOwner
import com.lomo.data.repository.AppUpdateRepositoryImpl
import com.lomo.data.repository.AppVersionRepositoryImpl
import com.lomo.data.repository.DailyReviewSessionRepositoryImpl
import com.lomo.data.repository.DefaultWorkspaceMediaAccess
import com.lomo.data.repository.GitSyncRepositoryImpl
import com.lomo.data.repository.MediaRepositoryImpl
import com.lomo.data.repository.DataStoreMigrationSettingsStore
import com.lomo.data.repository.MemoRefreshDbApplier
import com.lomo.data.repository.MemoRefreshEngine
import com.lomo.data.repository.MemoRefreshParserWorker
import com.lomo.data.repository.MemoRefreshPlanner
import com.lomo.data.repository.MemoMutationGate
import com.lomo.data.repository.MemoMutationRepositoryImpl
import com.lomo.data.repository.MemoQueryRepositoryImpl
import com.lomo.data.repository.MemoVersionBlobRoot
import com.lomo.data.repository.MemoVersionJournal
import com.lomo.data.repository.MemoVersionRepositoryImpl
import com.lomo.data.repository.MemoSearchRepositoryImpl
import com.lomo.data.repository.MemoStatisticsRepositoryImpl
import com.lomo.data.repository.MemoTrashRepositoryImpl
import com.lomo.data.repository.MigrationArchiveRepositoryImpl
import com.lomo.data.repository.MigrationSettingsStore
import com.lomo.data.repository.MemoWorkspaceProjector
import com.lomo.data.repository.MemoWorkspaceReader
import com.lomo.data.repository.MemoWorkspaceStore
import com.lomo.data.repository.PackageManagerAppUpdateApkVerifier
import com.lomo.data.repository.PendingSyncConflictStore
import com.lomo.data.repository.PendingSyncReviewStore
import com.lomo.data.repository.RoomPendingSyncConflictStore
import com.lomo.data.repository.RoomPendingSyncReviewStore
import com.lomo.data.repository.RoomMemoVersionStore
import com.lomo.data.repository.RoomBackedS3SyncMetadataStore
import com.lomo.data.repository.RoomBackedWebDavSyncMetadataStore
import com.lomo.data.repository.SettingsRepositoryImpl
import com.lomo.data.repository.ShareImageRepositoryImpl
import com.lomo.data.repository.S3SyncRepositoryImpl
import com.lomo.data.repository.SyncStateResetRepositoryImpl
import com.lomo.data.repository.SyncPolicyRepositoryImpl
import com.lomo.data.repository.SyncInboxRepositoryImpl
import com.lomo.data.repository.WebDavSyncRepositoryImpl
import com.lomo.data.repository.RefreshingWorkspaceStateResolver
import com.lomo.data.repository.WorkspaceMediaAccess
import com.lomo.data.repository.WorkspaceTransitionRepositoryImpl
import com.lomo.data.sync.SyncConflictBackupManager
import com.lomo.data.source.FileDataSourceImpl
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateDownloadRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.AppUpdateTransportLifecycleRepository
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.DailyReviewSessionRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.InteractionPreferencesRepository
import com.lomo.domain.repository.MainListQueryRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoListQueryRepository
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.repository.MemoSearchRepository
import com.lomo.domain.repository.MemoStatisticsRepository
import com.lomo.domain.repository.MemoTrashRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.MigrationArchiveRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SecurityPreferencesRepository
import com.lomo.domain.repository.ShareImageRepository
import com.lomo.domain.repository.SidebarTagOrderPreferencesRepository
import com.lomo.domain.repository.SyncConflictBackupRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.SyncStateResetRepository
import com.lomo.domain.repository.WebDavSyncRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import com.lomo.domain.repository.WorkspaceTransitionRepository
import com.lomo.domain.usecase.MemoIdentityPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideMemoDatabase(
        @ApplicationContext context: Context,
    ): MemoDatabase {
        DatabaseTransitionStrategy.prepareBeforeOpen(
            context = context,
            targetVersion = MEMO_DATABASE_VERSION,
            migrationEdges = ALL_DATABASE_MIGRATION_EDGES,
        )

        return buildMemoDatabase(context)
    }

    private fun buildMemoDatabase(context: Context): MemoDatabase =
        Room
            .databaseBuilder(context, MemoDatabase::class.java, DatabaseTransitionStrategy.DATABASE_NAME)
            .setDriver(BundledSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*ALL_DATABASE_MIGRATIONS)
            .addCallback(DatabaseTransitionStrategy.cleanupLegacyArtifactsCallback())
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()

    @Provides
    @Singleton
    fun provideMemoDao(database: MemoDatabase): MemoDao = database.memoDao()

    @Provides
    @Singleton
    fun provideMemoBrowseDao(database: MemoDatabase): MemoBrowseDao = database.memoBrowseDao()

    @Provides
    @Singleton
    fun provideMemoPinDao(database: MemoDatabase): MemoPinDao = database.memoPinDao()

    @Provides
    @Singleton
    fun provideMemoSearchDao(database: MemoDatabase): MemoSearchDao = database.memoSearchDao()

    @Provides
    @Singleton
    fun provideMemoWriteDao(database: MemoDatabase): MemoWriteDao = database.memoWriteDao()

    @Provides
    @Singleton
    fun provideMemoTagDao(database: MemoDatabase): MemoTagDao = database.memoTagDao()

    @Provides
    @Singleton
    fun provideMemoFtsDao(database: MemoDatabase): MemoFtsDao = RoomMemoFtsDao(database)

    @Provides
    @Singleton
    fun provideMemoIdentityDao(database: MemoDatabase): MemoIdentityDao = database.memoIdentityDao()

    @Provides
    @Singleton
    fun provideMemoTrashDao(database: MemoDatabase): MemoTrashDao = database.memoTrashDao()

}

@Module
@InstallIn(SingletonComponent::class)
object MemoStatisticsDatabaseModule {
    @Provides
    @Singleton
    fun provideMemoStatisticsDao(database: MemoDatabase): MemoStatisticsDao = database.memoStatisticsDao()
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseSupportModule {
    @Provides
    @Singleton
    fun provideLocalFileStateDao(database: MemoDatabase): LocalFileStateDao = database.localFileStateDao()

    @Provides
    @Singleton
    fun provideMemoImageDao(database: MemoDatabase): MemoImageDao = database.memoImageDao()

    @Provides
    @Singleton
    fun provideMemoVersionDao(database: MemoDatabase): MemoVersionDao = database.memoVersionDao()
}

@Module
@InstallIn(SingletonComponent::class)
object RemoteSyncStateDatabaseModule {
    @Provides
    @Singleton
    fun provideRawWebDavSyncMetadataDao(database: MemoDatabase): RawWebDavSyncMetadataDao =
        database.rawWebDavSyncMetadataDao()

    @Provides
    @Singleton
    fun provideWebDavSyncMetadataDao(store: RoomBackedWebDavSyncMetadataStore): WebDavSyncMetadataDao = store

    @Provides
    @Singleton
    fun provideRawS3SyncMetadataDao(database: MemoDatabase): RawS3SyncMetadataDao = database.rawS3SyncMetadataDao()

    @Provides
    @Singleton
    fun provideS3SyncMetadataDao(store: RoomBackedS3SyncMetadataStore): S3SyncMetadataDao = store

    @Provides
    @Singleton
    fun provideS3RemoteIndexDao(database: MemoDatabase): S3RemoteIndexDao = database.s3RemoteIndexDao()

    @Provides
    @Singleton
    fun provideS3RemoteShardStateDao(database: MemoDatabase): S3RemoteShardStateDao =
        database.s3RemoteShardStateDao()

    @Provides
    @Singleton
    fun provideS3SyncProtocolStateDao(database: MemoDatabase): S3SyncProtocolStateDao =
        database.s3SyncProtocolStateDao()

    @Provides
    @Singleton
    fun provideS3LocalChangeJournalDao(database: MemoDatabase): S3LocalChangeJournalDao =
        database.s3LocalChangeJournalDao()

    @Provides
    @Singleton
    fun provideSyncStateResetDao(database: MemoDatabase): SyncStateResetDao = database.syncStateResetDao()
}

@Module
@InstallIn(SingletonComponent::class)
object PendingSyncDatabaseModule {
    @Provides
    @Singleton
    fun providePendingSyncConflictDao(database: MemoDatabase): PendingSyncConflictDao =
        database.pendingSyncConflictDao()

    @Provides
    @Singleton
    fun providePendingSyncReviewDao(database: MemoDatabase): PendingSyncReviewDao =
        database.pendingSyncReviewDao()

    @Provides
    @Singleton
    fun providePendingSyncConflictStore(
        store: RoomPendingSyncConflictStore,
    ): PendingSyncConflictStore = store

    @Provides
    @Singleton
    fun providePendingSyncReviewStore(
        store: RoomPendingSyncReviewStore,
    ): PendingSyncReviewStore = store
}

@Module
@InstallIn(SingletonComponent::class)
object WebDavDatabaseSupportModule {
    @Provides
    @Singleton
    fun provideWebDavLocalFingerprintDao(database: MemoDatabase): WebDavLocalFingerprintDao =
        database.webDavLocalFingerprintDao()

    @Provides
    @Singleton
    fun provideWebDavLocalChangeJournalDao(database: MemoDatabase): WebDavLocalChangeJournalDao =
        database.webDavLocalChangeJournalDao()
}

@Module
@InstallIn(SingletonComponent::class)
object MemoOutboxDatabaseModule {
    @Provides
    @Singleton
    fun provideMemoOutboxDao(database: MemoDatabase): MemoOutboxDao = database.memoOutboxDao()
}

@Module
@InstallIn(SingletonComponent::class)
object StorageDataSourceModule {
    @Provides
    @Singleton
    @MemoVersionBlobRoot
    fun provideMemoVersionBlobRoot(
        @ApplicationContext context: Context,
    ): File = File(context.filesDir, "memo-versions/blobs")

    @Provides
    @Singleton
    fun provideWorkspaceConfigSource(
        dataSource: FileDataSourceImpl,
    ): com.lomo.data.source.WorkspaceConfigSource = dataSource

    @Provides
    @Singleton
    fun provideMarkdownStorageDataSource(
        dataSource: FileDataSourceImpl,
    ): com.lomo.data.source.MarkdownStorageDataSource = dataSource

    @Provides
    @Singleton
    fun provideMediaStorageDataSource(
        dataSource: FileDataSourceImpl,
    ): com.lomo.data.source.MediaStorageDataSource = dataSource
}

@Module
@InstallIn(SingletonComponent::class)
object WorkspaceMediaAccessModule {
    @Provides
    @Singleton
    fun provideWorkspaceMediaAccess(
        access: DefaultWorkspaceMediaAccess,
    ): WorkspaceMediaAccess = access
}

@Module
@InstallIn(SingletonComponent::class)
object MemoVersionModule {
    @Provides
    @Singleton
    fun provideMemoVersionStore(
        store: RoomMemoVersionStore,
    ): com.lomo.data.repository.MemoVersionStore = store

    @Provides
    @Singleton
    fun provideMemoVersionRepository(
        impl: MemoVersionRepositoryImpl,
    ): MemoVersionRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object MemoRefreshModule {
    @Provides
    @Singleton
    fun provideWorkspaceTransitionRepositoryImpl(
        database: MemoDatabase,
        memoWriteDao: MemoWriteDao,
        memoOutboxDao: MemoOutboxDao,
        memoTagDao: MemoTagDao,
        memoImageDao: MemoImageDao,
        memoTrashDao: MemoTrashDao,
        localFileStateDao: LocalFileStateDao,
        syncStateResetRepository: SyncStateResetRepository,
    ): WorkspaceTransitionRepositoryImpl =
        WorkspaceTransitionRepositoryImpl(
            memoWriteDao = memoWriteDao,
            memoOutboxDao = memoOutboxDao,
            memoTagDao = memoTagDao,
            memoImageDao = memoImageDao,
            memoTrashDao = memoTrashDao,
            localFileStateDao = localFileStateDao,
            syncStateResetRepository = syncStateResetRepository,
            runInTransaction = { block ->
                database.withDriverTransaction {
                    block()
                }
            },
        )

    @Provides
    @Singleton
    fun provideMemoRefreshPlanner(): MemoRefreshPlanner = MemoRefreshPlanner

    @Provides
    @Singleton
    fun provideMemoIdentityPolicy(): MemoIdentityPolicy = MemoIdentityPolicy()

    @Provides
    @Singleton
    fun provideMemoRefreshParserWorker(
        workspaceProjector: MemoWorkspaceProjector,
        dao: MemoDao,
    ): MemoRefreshParserWorker =
        MemoRefreshParserWorker(
            workspaceProjector = workspaceProjector,
            dao = dao,
        )

    @Provides
    @Singleton
    fun provideMemoRefreshDbApplier(
        memoDao: MemoDao,
        memoWriteDao: MemoWriteDao,
        memoTagDao: MemoTagDao,
        memoImageDao: MemoImageDao,
        memoTrashDao: MemoTrashDao,
        localFileStateDao: LocalFileStateDao,
        memoVersionJournal: MemoVersionJournal,
        database: MemoDatabase,
    ): MemoRefreshDbApplier =
        MemoRefreshDbApplier(
            memoDao = memoDao,
            memoWriteDao = memoWriteDao,
            memoTagDao = memoTagDao,
            memoImageDao = memoImageDao,
            memoTrashDao = memoTrashDao,
            localFileStateDao = localFileStateDao,
            memoVersionJournal = memoVersionJournal,
            runInTransaction = { block ->
                database.withDriverTransactionAndSuspendedMemoFtsTriggers {
                    block()
                }
            },
        )

    @Provides
    @Singleton
    fun provideMemoRefreshEngine(
        workspaceReader: MemoWorkspaceReader,
        localFileStateDao: LocalFileStateDao,
        planner: MemoRefreshPlanner,
        parserWorker: MemoRefreshParserWorker,
        dbApplier: MemoRefreshDbApplier,
        mutationGate: MemoMutationGate,
        ): MemoRefreshEngine =
        MemoRefreshEngine(
            workspaceReader = workspaceReader,
            localFileStateDao = localFileStateDao,
            refreshPlanner = planner,
            refreshParserWorker = parserWorker,
            refreshDbApplier = dbApplier,
            mutationGate = mutationGate,
        )

    @Provides
    @Singleton
    fun provideWorkspaceStateResolver(
        cleanupRepository: WorkspaceTransitionRepository,
        mediaRepository: MediaRepository,
        refreshEngine: MemoRefreshEngine,
    ): WorkspaceStateResolver =
        RefreshingWorkspaceStateResolver(
            cleanupRepository = cleanupRepository,
            mediaRepository = mediaRepository,
            refreshEngine = refreshEngine,
        )
}

@Module
@InstallIn(SingletonComponent::class)
object MemoQueryRepositoryModule {
    @Provides
    @Singleton
    fun provideMemoQueryRepository(impl: MemoQueryRepositoryImpl): MemoQueryRepository = impl

    @Provides
    @Singleton
    fun provideMemoListQueryRepository(impl: MemoQueryRepositoryImpl): MemoListQueryRepository = impl

    @Provides
    @Singleton
    fun provideMainListQueryRepository(impl: MemoQueryRepositoryImpl): MainListQueryRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object MemoMutationRepositoryModule {
    @Provides
    @Singleton
    fun provideMemoMutationRepository(impl: MemoMutationRepositoryImpl): MemoMutationRepository = impl

    @Provides
    @Singleton
    fun provideMemoTrashRepository(impl: MemoTrashRepositoryImpl): MemoTrashRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object MemoAnalysisRepositoryModule {
    @Provides
    @Singleton
    fun provideMemoSearchRepository(impl: MemoSearchRepositoryImpl): MemoSearchRepository = impl

    @Provides
    @Singleton
    fun provideMemoStatisticsRepository(impl: MemoStatisticsRepositoryImpl): MemoStatisticsRepository = impl
}

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
    fun provideCustomFontStore(
        impl: com.lomo.data.repository.CustomFontStoreImpl,
    ): com.lomo.domain.repository.CustomFontStore = impl

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
        impl: com.lomo.data.repository.DataStoreWorkspaceSyncGenerationProvider,
    ): WorkspaceSyncGenerationProvider = impl

    @Provides
    @Singleton
    fun provideMediaRepository(impl: MediaRepositoryImpl): MediaRepository = impl

    @Provides
    @Singleton
    fun provideGitSyncRepository(impl: GitSyncRepositoryImpl): GitSyncRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object AppUpdateRepositoryModule {
    @Provides
    @Singleton
    fun provideAppUpdateRepository(impl: AppUpdateRepositoryImpl): AppUpdateRepository = impl

    @Provides
    @Singleton
    fun provideAppUpdateDownloadRepository(
        impl: AppUpdateDownloadRepositoryImpl,
    ): AppUpdateDownloadRepository = impl

    @Provides
    @Singleton
    fun provideAppRuntimeInfoRepository(impl: AppRuntimeInfoRepositoryImpl): AppRuntimeInfoRepository = impl
}

@Module
@InstallIn(SingletonComponent::class)
object AppUpdateTransportModule {
    @Provides
    @Singleton
    internal fun provideAppUpdateTransportOwner(): AppUpdateTransportOwner = AppUpdateTransportOwner.createDefault()

    @Provides
    @Singleton
    internal fun provideAppUpdateApkDownloader(
        transportOwner: AppUpdateTransportOwner,
    ): AppUpdateApkDownloader = transportOwner.createDownloader()

    @Provides
    @Singleton
    internal fun provideAppUpdateTransportLifecycleRepository(
        transportOwner: AppUpdateTransportOwner,
    ): AppUpdateTransportLifecycleRepository = transportOwner

    @Provides
    @Singleton
    internal fun providePackageManagerAppUpdateApkVerifier(
        @ApplicationContext context: Context,
    ): PackageManagerAppUpdateApkVerifier = PackageManagerAppUpdateApkVerifier(context)

    @Provides
    @Singleton
    internal fun provideAppUpdateApkVerifier(
        verifier: PackageManagerAppUpdateApkVerifier,
    ): AppUpdateApkVerifier = verifier

    @Provides
    @Singleton
    internal fun provideAppUpdateInstallerResultObserver(
        verifier: PackageManagerAppUpdateApkVerifier,
    ): AppUpdateInstallerResultObserver = verifier
}

@Module
@InstallIn(SingletonComponent::class)
object MigrationRepositoryModule {
    @Provides
    @Singleton
    fun provideMigrationSettingsStore(impl: DataStoreMigrationSettingsStore): MigrationSettingsStore = impl

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

@Module
@InstallIn(SingletonComponent::class)
object SyncRepositoryModule {
    @Provides
    @Singleton
    fun provideWebDavSyncPlanner(): com.lomo.data.repository.WebDavSyncPlanner =
        com.lomo.data.repository.WebDavSyncPlanner()

    @Provides
    @Singleton
    fun provideGitMediaSyncPlanner(): com.lomo.data.git.GitMediaSyncPlanner =
        com.lomo.data.git.GitMediaSyncPlanner()

    @Provides
    @Singleton
    fun provideWebDavClientFactory(
        factory: com.lomo.data.webdav.Dav4jvmWebDavClientFactory,
    ): com.lomo.data.webdav.WebDavClientFactory = factory

    @Provides
    @Singleton
    fun provideWebDavEndpointResolver(
        resolver: com.lomo.data.webdav.DefaultWebDavEndpointResolver,
    ): com.lomo.data.webdav.WebDavEndpointResolver = resolver

    @Provides
    @Singleton
    fun provideRawGitMediaSyncStateStore(
        impl: com.lomo.data.git.FileGitMediaSyncStateStore,
    ): com.lomo.data.git.RawGitMediaSyncStateStore = impl

    @Provides
    @Singleton
    fun provideGitMediaSyncStateStore(
        impl: com.lomo.data.git.GitMediaSyncWorkspaceStateStore,
    ): com.lomo.data.git.GitMediaSyncStateStore = impl

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
    fun provideS3SyncPlanner(): com.lomo.data.repository.S3SyncPlanner =
        com.lomo.data.repository.S3SyncPlanner()

    @Provides
    @Singleton
    fun provideS3SafTreeAccess(
        impl: com.lomo.data.repository.AndroidS3SafTreeAccess,
    ): com.lomo.data.repository.S3SafTreeAccess = impl

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
object MediaShareModule {
    @Provides
    @Singleton
    fun provideVoiceRecorder(
        audioRecorder: com.lomo.data.media.AudioRecorder,
    ): com.lomo.domain.repository.VoiceRecordingRepository = audioRecorder

    @Provides
    @Singleton
    fun provideAudioPlaybackUriResolver(
        impl: com.lomo.data.media.AudioPlaybackUriResolverImpl,
    ): com.lomo.domain.repository.AudioPlaybackResolverRepository = impl

    @Provides
    @Singleton
    fun provideLanShareService(
        impl: com.lomo.data.share.ShareServiceManager,
    ): com.lomo.domain.repository.LanShareService = impl
}
