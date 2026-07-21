package com.lomo.data.repository

import com.lomo.data.local.FileBackedSyncDatabase

interface S3SyncTransactionRunner {
    suspend fun <T> runInTransaction(block: suspend () -> T): T
}

class FileBackedS3SyncTransactionRunner(
    private val database: FileBackedSyncDatabase,
) : S3SyncTransactionRunner {
    override suspend fun <T> runInTransaction(block: suspend () -> T): T = database.runInTransaction(block)
}
