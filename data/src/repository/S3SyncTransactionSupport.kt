package com.lomo.data.repository

import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.withDriverTransaction


interface S3SyncTransactionRunner {
    suspend fun <T> runInTransaction(block: suspend () -> T): T
}

class RoomBackedS3SyncTransactionRunner(
    private val database: MemoDatabase,
) : S3SyncTransactionRunner {
        override suspend fun <T> runInTransaction(block: suspend () -> T): T =
            database.withDriverTransaction {
                block()
            }
    }


