package com.lomo.data.repository

import androidx.room.withTransaction
import com.lomo.data.local.MemoDatabase
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface S3SyncTransactionRunner {
    suspend fun <T> runInTransaction(block: suspend () -> T): T
}

object NoOpS3SyncTransactionRunner : S3SyncTransactionRunner {
    override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
}

@Singleton
class RoomBackedS3SyncTransactionRunner
    @Inject
    constructor(
        private val database: MemoDatabase,
    ) : S3SyncTransactionRunner {
        override suspend fun <T> runInTransaction(block: suspend () -> T): T =
            database.withTransaction {
                block()
            }
    }

@Module
@InstallIn(SingletonComponent::class)
internal interface S3SyncTransactionRunnerBindingsModule {
    @Binds
    fun bindS3SyncTransactionRunner(impl: RoomBackedS3SyncTransactionRunner): S3SyncTransactionRunner
}
