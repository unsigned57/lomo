package com.lomo.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.lomo.data.local.ALL_DATABASE_MIGRATIONS
import com.lomo.data.local.DatabaseTransitionStrategy
import com.lomo.data.local.MEMO_DATABASE_VERSION
import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoFtsDao
import com.lomo.data.local.dao.MemoIdentityDao
import com.lomo.data.local.dao.MemoOutboxDao
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoVersionDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.dao.PendingSyncConflictDao
import com.lomo.data.local.dao.S3LocalChangeJournalDao
import com.lomo.data.local.dao.S3RemoteIndexDao
import com.lomo.data.local.dao.S3RemoteShardStateDao
import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncProtocolStateDao
import com.lomo.data.local.dao.WebDavSyncMetadataDao
import com.lomo.data.s3.AwsSdkS3ClientFactory
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.repository.AppRuntimeInfoRepositoryImpl
import com.lomo.data.repository.AppUpdateDownloadRepositoryImpl
import com.lomo.data.repository.AppUpdateRepositoryImpl
import com.lomo.data.repository.AppVersionRepositoryImpl
import com.lomo.data.repository.DefaultWorkspaceMediaAccess
import com.lomo.data.repository.GitSyncRepositoryImpl
import com.lomo.data.repository.MediaRepositoryImpl
import com.lomo.data.repository.MemoRefreshDbApplier
import com.lomo.data.repository.MemoRefreshEngine
import com.lomo.data.repository.MemoRefreshParserWorker
import com.lomo.data.repository.MemoRefreshPlanner
import com.lomo.data.repository.MemoVersionBlobRoot
import com.lomo.data.repository.MemoVersionJournal
import com.lomo.data.repository.MemoRepositoryImpl
import com.lomo.data.repository.PendingSyncConflictStore
import com.lomo.data.repository.RoomPendingSyncConflictStore
import com.lomo.data.repository.RoomMemoVersionStore
import com.lomo.data.repository.SettingsRepositoryImpl
import com.lomo.data.repository.ShareImageRepositoryImpl
import com.lomo.data.repository.S3SyncRepositoryImpl
import com.lomo.data.repository.SyncPolicyRepositoryImpl
import com.lomo.data.repository.SyncInboxRepositoryImpl
import com.lomo.data.repository.WebDavSyncRepositoryImpl
import com.lomo.data.repository.RefreshingWorkspaceStateResolver
import com.lomo.data.repository.WorkspaceMediaAccess
import com.lomo.data.repository.WorkspaceStateResolver
import com.lomo.data.repository.WorkspaceTransitionRepositoryImpl
import com.lomo.data.sync.SyncConflictBackupManager
import com.lomo.data.source.FileDataSourceImpl
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateDownloadRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.InteractionPreferencesRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SecurityPreferencesRepository
import com.lomo.domain.repository.ShareImageRepository
import com.lomo.domain.repository.SyncConflictBackupRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.WebDavSyncRepository
import com.lomo.domain.repository.WorkspaceTransitionRepository
import com.lomo.domain.usecase.MemoIdentityPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
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
        )

        val db = buildMemoDatabase(context)
        return runNonFatalCatching {
            // Force database open now to trigger migration inside the provider.
            // If migration fails, the catch block recreates the database from scratch.
            db.openHelper.writableDatabase
            db
        }.getOrElse { error ->
            Timber.tag("DataModule").e(error, "Database open/migration failed, recreating from scratch")
            runCatching { db.close() }
            context.deleteDatabase(DatabaseTransitionStrategy.DATABASE_NAME)
            buildMemoDatabase(context)
        }
    }

    private fun buildMemoDatabase(context: Context): MemoDatabase =
        Room
            .databaseBuilder(context, MemoDatabase::class.java, DatabaseTransitionStrategy.DATABASE_NAME)
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
    fun provideMemoFtsDao(database: MemoDatabase): MemoFtsDao = database.memoFtsDao()

    @Provides
    @Singleton
    fun provideMemoIdentityDao(database: MemoDatabase): MemoIdentityDao = database.memoIdentityDao()

    @Provides
    @Singleton
    fun provideMemoTrashDao(database: MemoDatabase): MemoTrashDao = database.memoTrashDao()

    @Provides
    @Singleton
    fun provideMemoOutboxDao(database: MemoDatabase): MemoOutboxDao = database.memoOutboxDao()

}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseSupportModule {
    @Provides
    @Singleton
    fun provideLocalFileStateDao(database: MemoDatabase): LocalFileStateDao = database.localFileStateDao()

    @Provides
    @Singleton
    fun provideWebDavSyncMetadataDao(database: MemoDatabase): WebDavSyncMetadataDao = database.webDavSyncMetadataDao()

    @Provides
    @Singleton
    fun provideS3SyncMetadataDao(database: MemoDatabase): S3SyncMetadataDao = database.s3SyncMetadataDao()

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
    fun providePendingSyncConflictDao(database: MemoDatabase): PendingSyncConflictDao =
        database.pendingSyncConflictDao()

    @Provides
    @Singleton
    fun providePendingSyncConflictStore(
        store: RoomPendingSyncConflictStore,
    ): PendingSyncConflictStore = store

    @Provides
    @Singleton
    fun provideMemoVersionDao(database: MemoDatabase): MemoVersionDao = database.memoVersionDao()
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
        journal: MemoVersionJournal,
    ): MemoVersionRepository = journal
}

@Module
@InstallIn(SingletonComponent::class)
object MemoRefreshModule {
    @Provides
    @Singleton
    fun provideWorkspaceTransitionRepositoryImpl(
        memoWriteDao: MemoWriteDao,
        memoOutboxDao: MemoOutboxDao,
        memoTagDao: MemoTagDao,
        memoFtsDao: MemoFtsDao,
        memoTrashDao: MemoTrashDao,
        localFileStateDao: LocalFileStateDao,
    ): WorkspaceTransitionRepositoryImpl =
        WorkspaceTransitionRepositoryImpl(
            memoWriteDao = memoWriteDao,
            memoOutboxDao = memoOutboxDao,
            memoTagDao = memoTagDao,
            memoFtsDao = memoFtsDao,
            memoTrashDao = memoTrashDao,
            localFileStateDao = localFileStateDao,
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
        markdownStorageDataSource: com.lomo.data.source.MarkdownStorageDataSource,
        dao: MemoDao,
        parser: com.lomo.data.parser.MarkdownParser,
    ): MemoRefreshParserWorker =
        MemoRefreshParserWorker(
            markdownStorageDataSource = markdownStorageDataSource,
            dao = dao,
            parser = parser,
        )

    @Provides
    @Singleton
    fun provideMemoRefreshDbApplier(
        memoDao: MemoDao,
        memoWriteDao: MemoWriteDao,
        memoTagDao: MemoTagDao,
        memoFtsDao: MemoFtsDao,
        memoTrashDao: MemoTrashDao,
        localFileStateDao: LocalFileStateDao,
        memoVersionJournal: MemoVersionJournal,
        database: MemoDatabase,
    ): MemoRefreshDbApplier =
        MemoRefreshDbApplier(
            memoDao = memoDao,
            memoWriteDao = memoWriteDao,
            memoTagDao = memoTagDao,
            memoFtsDao = memoFtsDao,
            memoTrashDao = memoTrashDao,
            localFileStateDao = localFileStateDao,
            memoVersionJournal = memoVersionJournal,
            runInTransaction = { block ->
                database.withTransaction {
                    block()
                }
            },
        )

    @Provides
    @Singleton
    fun provideMemoRefreshEngine(
        markdownStorageDataSource: com.lomo.data.source.MarkdownStorageDataSource,
        localFileStateDao: LocalFileStateDao,
        planner: MemoRefreshPlanner,
        parserWorker: MemoRefreshParserWorker,
        dbApplier: MemoRefreshDbApplier,
        ): MemoRefreshEngine =
        MemoRefreshEngine(
            markdownStorageDataSource = markdownStorageDataSource,
            localFileStateDao = localFileStateDao,
            refreshPlanner = planner,
            refreshParserWorker = parserWorker,
            refreshDbApplier = dbApplier,
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
object CoreRepositoryModule {
    @Provides
    @Singleton
    fun provideMemoRepository(impl: MemoRepositoryImpl): MemoRepository = impl

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
    fun provideWorkspaceTransitionRepository(
        impl: WorkspaceTransitionRepositoryImpl,
    ): WorkspaceTransitionRepository = impl

    @Provides
    @Singleton
    fun provideMediaRepository(impl: MediaRepositoryImpl): MediaRepository = impl

    @Provides
    @Singleton
    fun provideSyncInboxRepository(impl: SyncInboxRepositoryImpl): SyncInboxRepository = impl

    @Provides
    @Singleton
    fun provideGitSyncRepository(impl: GitSyncRepositoryImpl): GitSyncRepository = impl
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
    fun provideGitMediaSyncStateStore(
        impl: com.lomo.data.git.FileGitMediaSyncStateStore,
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
