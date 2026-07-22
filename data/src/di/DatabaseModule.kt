package com.lomo.data.di

import com.lomo.data.local.FileBackedSyncDatabase
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
import com.lomo.data.repository.FileBackedS3SyncTransactionRunner
import com.lomo.data.repository.FileBackedPendingSyncConflictStore
import com.lomo.data.repository.FileBackedPendingSyncReviewStore
import com.lomo.data.repository.FileBackedS3SyncMetadataStore
import com.lomo.data.repository.FileBackedWebDavSyncMetadataStore
import com.lomo.data.repository.PendingSyncConflictStore
import com.lomo.data.repository.PendingSyncReviewStore
import com.lomo.data.repository.S3SyncTransactionRunner
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Post P3-10: no Room. Sync/cache tables are file-backed; memo projections live in the Rust store.
 */
val databaseModule =
    module {
        single { FileBackedSyncDatabase(androidContext()) }

        single<PendingSyncConflictDao> { get<FileBackedSyncDatabase>().pendingSyncConflictDao }
        single<PendingSyncReviewDao> { get<FileBackedSyncDatabase>().pendingSyncReviewDao }
        single<S3LocalChangeJournalDao> { get<FileBackedSyncDatabase>().s3LocalChangeJournalDao }
        single<S3RemoteIndexDao> { get<FileBackedSyncDatabase>().s3RemoteIndexDao }
        single<S3RemoteShardStateDao> { get<FileBackedSyncDatabase>().s3RemoteShardStateDao }
        single<RawS3SyncMetadataDao> { get<FileBackedSyncDatabase>().rawS3SyncMetadataDao }
        single { FileBackedS3SyncMetadataStore(get(), get()) }
        single<S3SyncMetadataDao> { get<FileBackedS3SyncMetadataStore>() }
        single<S3SyncProtocolStateDao> { get<FileBackedSyncDatabase>().s3SyncProtocolStateDao }
        single<WebDavLocalChangeJournalDao> { get<FileBackedSyncDatabase>().webDavLocalChangeJournalDao }
        single<WebDavLocalFingerprintDao> { get<FileBackedSyncDatabase>().webDavLocalFingerprintDao }
        single<RawWebDavSyncMetadataDao> { get<FileBackedSyncDatabase>().rawWebDavSyncMetadataDao }
        single { FileBackedWebDavSyncMetadataStore(get(), get()) }
        single<WebDavSyncMetadataDao> { get<FileBackedWebDavSyncMetadataStore>() }
        single<SyncStateResetDao> { get<FileBackedSyncDatabase>().syncStateResetDao }

        singleOf(::FileBackedPendingSyncConflictStore) bind PendingSyncConflictStore::class
        singleOf(::FileBackedPendingSyncReviewStore) bind PendingSyncReviewStore::class
        singleOf(::FileBackedS3SyncTransactionRunner) bind S3SyncTransactionRunner::class
    }
