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
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoBrowseDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoFtsDao
import com.lomo.data.local.dao.MemoIdentityDao
import com.lomo.data.local.dao.MemoImageDao
import com.lomo.data.local.dao.MemoOutboxDao
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.data.local.dao.MemoStatisticsDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoVersionDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.dao.PendingSyncConflictDao
import com.lomo.data.local.dao.PendingSyncReviewDao
import com.lomo.data.local.dao.RawS3SyncMetadataDao
import com.lomo.data.local.dao.RawWebDavSyncMetadataDao
import com.lomo.data.local.dao.RoomMemoFtsDao
import com.lomo.data.local.dao.S3LocalChangeJournalDao
import com.lomo.data.local.dao.S3RemoteIndexDao
import com.lomo.data.local.dao.S3RemoteShardStateDao
import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncProtocolStateDao
import com.lomo.data.local.dao.SyncStateResetDao
import com.lomo.data.local.dao.WebDavLocalChangeJournalDao
import com.lomo.data.local.dao.WebDavLocalFingerprintDao
import com.lomo.data.local.dao.WebDavSyncMetadataDao
import com.lomo.data.repository.PendingSyncConflictStore
import com.lomo.data.repository.PendingSyncReviewStore
import com.lomo.data.repository.RoomBackedS3SyncMetadataStore
import com.lomo.data.repository.RoomBackedWebDavSyncMetadataStore
import com.lomo.data.repository.RoomPendingSyncConflictStore
import com.lomo.data.repository.RoomPendingSyncReviewStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
