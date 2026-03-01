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
import com.lomo.data.repository.AppVersionRepositoryImpl
import com.lomo.data.repository.AppRuntimeInfoRepositoryImpl
import com.lomo.data.repository.AppUpdateRepositoryImpl
import com.lomo.data.repository.GitSyncRepositoryImpl
import com.lomo.data.repository.MediaRepositoryImpl
import com.lomo.data.repository.MemoRefreshDbApplier
import com.lomo.data.repository.MemoRefreshEngine
import com.lomo.data.repository.MemoRefreshParserWorker
import com.lomo.data.repository.MemoRefreshPlanner
import com.lomo.data.repository.MemoRepositoryImpl
import com.lomo.data.repository.WorkspaceTransitionRepositoryImpl
import com.lomo.data.repository.ShareImageRepositoryImpl
import com.lomo.data.repository.SettingsRepositoryImpl
import com.lomo.data.repository.SyncPolicyRepositoryImpl
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.WorkspaceTransitionRepository
import com.lomo.domain.repository.ShareImageRepository
import com.lomo.domain.repository.SyncPolicyRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideMemoDatabase(
        @ApplicationContext context: Context,
    ): MemoDatabase {
        val migrations = ALL_DATABASE_MIGRATIONS.toList()
        DatabaseTransitionStrategy.prepareBeforeOpen(
            context = context,
            targetVersion = MEMO_DATABASE_VERSION,
            migrations = migrations,
        )
        val fallbackFromVersions =
            DatabaseTransitionStrategy.fallbackToDestructiveFromVersions(
                migrations = migrations,
                targetVersion = MEMO_DATABASE_VERSION,
            )

        val builder =
            Room
                .databaseBuilder(context, MemoDatabase::class.java, DatabaseTransitionStrategy.DATABASE_NAME)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*ALL_DATABASE_MIGRATIONS)
                .addCallback(DatabaseTransitionStrategy.cleanupLegacyArtifactsCallback())
                .fallbackToDestructiveMigrationOnDowngrade(true)
        if (fallbackFromVersions.isNotEmpty()) {
            builder.fallbackToDestructiveMigrationFrom(true, *fallbackFromVersions)
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideMemoDao(database: MemoDatabase): MemoDao = database.memoDao()

    @Provides
    @Singleton
    fun provideLocalFileStateDao(database: MemoDatabase): LocalFileStateDao = database.localFileStateDao()

    @Provides
    @Singleton
    fun provideFileDataSource(
        @ApplicationContext context: Context,
        dataStore: com.lomo.data.local.datastore.LomoDataStore,
    ): com.lomo.data.source.FileDataSource =
        com.lomo.data.source
            .FileDataSourceImpl(context, dataStore)

    @Provides
    @Singleton
    fun provideWorkspaceConfigSource(
        dataSource: com.lomo.data.source.FileDataSource,
    ): com.lomo.data.source.WorkspaceConfigSource = dataSource

    @Provides
    @Singleton
    fun provideMarkdownStorageDataSource(
        dataSource: com.lomo.data.source.FileDataSource,
    ): com.lomo.data.source.MarkdownStorageDataSource = dataSource

    @Provides
    @Singleton
    fun provideMediaStorageDataSource(
        dataSource: com.lomo.data.source.FileDataSource,
    ): com.lomo.data.source.MediaStorageDataSource = dataSource

    @Provides
    @Singleton
    fun provideMemoRepositoryImpl(
        dao: MemoDao,
        synchronizer: com.lomo.data.repository.MemoSynchronizer,
        resolveMemoUpdateActionUseCase: com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase,
    ): MemoRepositoryImpl =
        MemoRepositoryImpl(
            dao,
            synchronizer,
            resolveMemoUpdateActionUseCase,
        )

    @Provides
    @Singleton
    fun provideSettingsRepositoryImpl(
        dataSource: com.lomo.data.source.WorkspaceConfigSource,
        dataStore: com.lomo.data.local.datastore.LomoDataStore,
    ): SettingsRepositoryImpl =
        SettingsRepositoryImpl(
            dataSource,
            dataStore,
        )

    @Provides
    @Singleton
    fun provideWorkspaceTransitionRepositoryImpl(
        memoDao: MemoDao,
    ): WorkspaceTransitionRepositoryImpl =
        WorkspaceTransitionRepositoryImpl(
            memoDao = memoDao,
        )

    @Provides
    @Singleton
    fun provideMediaRepositoryImpl(dataSource: com.lomo.data.source.FileDataSource): MediaRepositoryImpl =
        MediaRepositoryImpl(
            dataSource,
        )

    @Provides
    @Singleton
    fun provideMemoRefreshPlanner(): MemoRefreshPlanner = MemoRefreshPlanner()

    @Provides
    @Singleton
    fun provideMemoRefreshParserWorker(
        fileDataSource: com.lomo.data.source.FileDataSource,
        dao: MemoDao,
        parser: com.lomo.data.parser.MarkdownParser,
    ): MemoRefreshParserWorker =
        MemoRefreshParserWorker(
            fileDataSource = fileDataSource,
            dao = dao,
            parser = parser,
        )

    @Provides
    @Singleton
    fun provideMemoRefreshDbApplier(
        dao: MemoDao,
        localFileStateDao: LocalFileStateDao,
        database: MemoDatabase,
    ): MemoRefreshDbApplier =
        MemoRefreshDbApplier(
            dao = dao,
            localFileStateDao = localFileStateDao,
            runInTransaction = { block ->
                database.withTransaction {
                    block()
                }
            },
        )

    @Provides
    @Singleton
    fun provideMemoRefreshEngine(
        fileDataSource: com.lomo.data.source.FileDataSource,
        dao: MemoDao,
        localFileStateDao: LocalFileStateDao,
        parser: com.lomo.data.parser.MarkdownParser,
        planner: MemoRefreshPlanner,
        parserWorker: MemoRefreshParserWorker,
        dbApplier: MemoRefreshDbApplier,
    ): MemoRefreshEngine =
        MemoRefreshEngine(
            fileDataSource = fileDataSource,
            dao = dao,
            localFileStateDao = localFileStateDao,
            parser = parser,
            refreshPlanner = planner,
            refreshParserWorker = parserWorker,
            refreshDbApplier = dbApplier,
        )

    @Provides
    @Singleton
    fun provideMemoRepository(impl: MemoRepositoryImpl): MemoRepository = impl

    @Provides
    @Singleton
    fun provideAppUpdateRepository(impl: AppUpdateRepositoryImpl): AppUpdateRepository = impl

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
    fun provideWorkspaceTransitionRepository(impl: WorkspaceTransitionRepositoryImpl): WorkspaceTransitionRepository = impl

    @Provides
    @Singleton
    fun provideMediaRepository(impl: MediaRepositoryImpl): MediaRepository = impl

    @Provides
    @Singleton
    fun provideGitSyncRepositoryImpl(
        gitSyncEngine: com.lomo.data.git.GitSyncEngine,
        credentialStore: com.lomo.data.git.GitCredentialStore,
        dataStore: com.lomo.data.local.datastore.LomoDataStore,
        memoSynchronizer: com.lomo.data.repository.MemoSynchronizer,
        safGitMirrorBridge: com.lomo.data.git.SafGitMirrorBridge,
        markdownParser: com.lomo.data.parser.MarkdownParser,
    ): GitSyncRepositoryImpl =
        GitSyncRepositoryImpl(
            gitSyncEngine,
            credentialStore,
            dataStore,
            memoSynchronizer,
            safGitMirrorBridge,
            markdownParser,
        )

    @Provides
    @Singleton
    fun provideGitSyncRepository(impl: GitSyncRepositoryImpl): GitSyncRepository = impl

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
    fun provideLanShareService(impl: com.lomo.data.share.ShareServiceManager): com.lomo.domain.repository.LanShareService = impl

    @Provides
    @Singleton
    fun provideAppVersionRepository(impl: AppVersionRepositoryImpl): AppVersionRepository = impl

    @Provides
    @Singleton
    fun provideSyncPolicyRepository(impl: SyncPolicyRepositoryImpl): SyncPolicyRepository = impl
}
