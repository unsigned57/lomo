package com.lomo.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.repository.MediaRepositoryImpl
import com.lomo.data.repository.MemoRepositoryImpl
import com.lomo.data.repository.SettingsRepositoryImpl
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
    ): MemoDatabase =
        Room
            .databaseBuilder(context, MemoDatabase::class.java, "lomo.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(com.lomo.data.local.MIGRATION_18_19)
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides
    @Singleton
    fun provideMemoDao(database: MemoDatabase): MemoDao = database.memoDao()

    @Provides
    @Singleton
    fun provideLocalFileStateDao(database: MemoDatabase): LocalFileStateDao = database.localFileStateDao()

    @Provides
    @Singleton
    fun provideMarkdownParser(): MarkdownParser = MarkdownParser()

    @Provides
    @Singleton
    fun provideMemoTextProcessor(): com.lomo.data.util.MemoTextProcessor =
        com.lomo.data.util
            .MemoTextProcessor()

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
    fun provideMemoSynchronizer(
        dataSource: com.lomo.data.source.FileDataSource,
        dao: MemoDao,
        localFileStateDao: LocalFileStateDao,
        parser: MarkdownParser,
        mutationHandler: com.lomo.data.repository.MemoMutationHandler,
    ): com.lomo.data.repository.MemoSynchronizer =
        com.lomo.data.repository.MemoSynchronizer(
            dataSource,
            dao,
            localFileStateDao,
            parser,
            mutationHandler,
        )

    @Provides
    @Singleton
    fun provideMemoRepositoryImpl(
        dao: MemoDao,
        dataSource: com.lomo.data.source.FileDataSource,
        synchronizer: com.lomo.data.repository.MemoSynchronizer,
        dataStore: com.lomo.data.local.datastore.LomoDataStore,
    ): MemoRepositoryImpl =
        MemoRepositoryImpl(
            dao,
            dataSource,
            synchronizer,
            dataStore,
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
    fun provideVoiceRecorder(audioRecorder: com.lomo.data.media.AudioRecorder): com.lomo.domain.repository.VoiceRecorder = audioRecorder
}
