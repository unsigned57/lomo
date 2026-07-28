package com.lomo.data.di

import com.lomo.data.local.FileBackedSyncDatabase
import com.lomo.data.local.dao.PendingSyncReviewDao
import com.lomo.data.local.dao.SyncStateResetDao
import com.lomo.data.repository.FileBackedPendingSyncReviewStore
import com.lomo.data.repository.PendingSyncReviewStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Post P3-10: no Room. Post P5-13: only Sync Inbox pending-review tables remain file-backed.
 */
val databaseModule =
    module {
        single { FileBackedSyncDatabase(androidContext()) }

        single<PendingSyncReviewDao> { get<FileBackedSyncDatabase>().pendingSyncReviewDao }
        single<SyncStateResetDao> { get<FileBackedSyncDatabase>().syncStateResetDao }

        singleOf(::FileBackedPendingSyncReviewStore) bind PendingSyncReviewStore::class
    }
