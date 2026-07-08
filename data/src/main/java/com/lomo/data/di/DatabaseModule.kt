package com.lomo.data.di

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
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

val databaseModule = module {
    single<MemoDatabase> {
        DatabaseTransitionStrategy.prepareBeforeOpen(
            context = androidContext(),
            targetVersion = MEMO_DATABASE_VERSION,
            migrationEdges = ALL_DATABASE_MIGRATION_EDGES,
        )
        Room.databaseBuilder(androidContext(), MemoDatabase::class.java, DatabaseTransitionStrategy.DATABASE_NAME)
            .setDriver(BundledSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*ALL_DATABASE_MIGRATIONS)
            .addCallback(DatabaseTransitionStrategy.cleanupLegacyArtifactsCallback())
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()
    }

    single<MemoDao> { get<MemoDatabase>().memoDao() }
    single<MemoBrowseDao> { get<MemoDatabase>().memoBrowseDao() }
    single<MemoPinDao> { get<MemoDatabase>().memoPinDao() }
    single<MemoSearchDao> { get<MemoDatabase>().memoSearchDao() }
    single<MemoWriteDao> { get<MemoDatabase>().memoWriteDao() }
    single<MemoTagDao> { get<MemoDatabase>().memoTagDao() }
    single<MemoFtsDao> { RoomMemoFtsDao(get()) }
    single<MemoIdentityDao> { get<MemoDatabase>().memoIdentityDao() }
    single<MemoTrashDao> { get<MemoDatabase>().memoTrashDao() }

    single<MemoStatisticsDao> { get<MemoDatabase>().memoStatisticsDao() }

    single<LocalFileStateDao> { get<MemoDatabase>().localFileStateDao() }
    single<MemoImageDao> { get<MemoDatabase>().memoImageDao() }
    single<MemoVersionDao> { get<MemoDatabase>().memoVersionDao() }

    single<RawWebDavSyncMetadataDao> { get<MemoDatabase>().rawWebDavSyncMetadataDao() }
    single { RoomBackedWebDavSyncMetadataStore(get(), get()) }
    single<WebDavSyncMetadataDao> { get<RoomBackedWebDavSyncMetadataStore>() }

    single<RawS3SyncMetadataDao> { get<MemoDatabase>().rawS3SyncMetadataDao() }
    single { RoomBackedS3SyncMetadataStore(get(), get()) }
    single<S3SyncMetadataDao> { get<RoomBackedS3SyncMetadataStore>() }

    single<S3RemoteIndexDao> { get<MemoDatabase>().s3RemoteIndexDao() }
    single<S3RemoteShardStateDao> { get<MemoDatabase>().s3RemoteShardStateDao() }
    single<S3SyncProtocolStateDao> { get<MemoDatabase>().s3SyncProtocolStateDao() }
    single<S3LocalChangeJournalDao> { get<MemoDatabase>().s3LocalChangeJournalDao() }
    single<SyncStateResetDao> { get<MemoDatabase>().syncStateResetDao() }

    single<PendingSyncConflictDao> { get<MemoDatabase>().pendingSyncConflictDao() }
    single<PendingSyncReviewDao> { get<MemoDatabase>().pendingSyncReviewDao() }

    singleOf(::RoomPendingSyncConflictStore) bind PendingSyncConflictStore::class
    singleOf(::RoomPendingSyncReviewStore) bind PendingSyncReviewStore::class

    single<WebDavLocalFingerprintDao> { get<MemoDatabase>().webDavLocalFingerprintDao() }
    single<WebDavLocalChangeJournalDao> { get<MemoDatabase>().webDavLocalChangeJournalDao() }

    single<MemoOutboxDao> { get<MemoDatabase>().memoOutboxDao() }
}
