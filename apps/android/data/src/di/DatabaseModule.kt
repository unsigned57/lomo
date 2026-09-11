package com.lomo.data.di

import com.lomo.data.repository.FileBackedPendingSyncReviewStore
import com.lomo.data.sync.pendingreview.FileBackedPendingReviewTable
import com.lomo.data.sync.pendingreview.PendingReviewTable
import com.lomo.data.repository.PendingSyncReviewStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Post P3-10: no Room. Post P5-13: the Sync Inbox pending-review table is the only Kotlin-owned
 * durable sync surface (independent SAF flow; Rust owns `.lomo/sync/v1` remote-sync state).
 */
val databaseModule =
    module {
        single<FileBackedPendingReviewTable> { FileBackedPendingReviewTable(androidContext()) }
        single<PendingReviewTable> { get<FileBackedPendingReviewTable>() }

        singleOf(::FileBackedPendingSyncReviewStore) bind PendingSyncReviewStore::class
    }
