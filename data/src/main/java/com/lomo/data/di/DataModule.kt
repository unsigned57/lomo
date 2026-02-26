package com.lomo.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lomo.data.local.ALL_DATABASE_MIGRATIONS
import com.lomo.data.local.DatabaseTransitionStrategy
import com.lomo.data.local.MEMO_DATABASE_VERSION
import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.repository.GitSyncRepositoryImpl
import com.lomo.data.repository.MediaRepositoryImpl
import com.lomo.data.repository.MemoRepositoryImpl
import com.lomo.data.repository.SettingsRepositoryImpl
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
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
    fun provideMemoRepositoryImpl(
        dao: MemoDao,
        synchronizer: com.lomo.data.repository.MemoSynchronizer,
    ): MemoRepositoryImpl =
        MemoRepositoryImpl(
            dao,
            synchronizer,
        )

    @Provides
    @Singleton
    fun provideSettingsRepositoryImpl(
        dao: MemoDao,
        dataSource: com.lomo.data.source.FileDataSource,
        dataStore: com.lomo.data.local.datastore.LomoDataStore,
    ): SettingsRepositoryImpl =
        SettingsRepositoryImpl(
            dao,
            dataSource,
            dataStore,
        )

    @Provides
    @Singleton
    fun provideMediaRepositoryImpl(dataSource: com.lomo.data.source.FileDataSource): MediaRepositoryImpl =
        MediaRepositoryImpl(
            dataSource,
        )

    @Provides
    @Singleton
    fun provideMemoRepository(impl: MemoRepositoryImpl): MemoRepository = impl

    @Provides
    @Singleton
    fun provideSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository = impl

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
    ): GitSyncRepositoryImpl =
        GitSyncRepositoryImpl(
            gitSyncEngine,
            credentialStore,
            dataStore,
            memoSynchronizer,
            safGitMirrorBridge,
        )

    @Provides
    @Singleton
    fun provideGitSyncRepository(impl: GitSyncRepositoryImpl): GitSyncRepository = impl

    @Provides
    @Singleton
    fun provideVoiceRecorder(audioRecorder: com.lomo.data.media.AudioRecorder): com.lomo.domain.repository.VoiceRecorder = audioRecorder
}
