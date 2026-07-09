package com.lomo.data.local

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection

suspend fun <T> MemoDatabase.withDriverTransaction(block: suspend () -> T): T =
    useWriterConnection { transactor ->
        transactor.immediateTransaction {
            block()
        }
    }

suspend fun <T> MemoDatabase.withDriverTransactionAndSuspendedMemoFtsTriggers(block: suspend () -> T): T =
    useWriterConnection { transactor ->
        transactor.immediateTransaction {
            dropMemoFtsExternalContentTriggers(transactor)
            try {
                block()
            } finally {
                createMemoFtsExternalContentTriggers(transactor)
                rebuildMemoFtsExternalContentIndex(transactor)
            }
        }
    }
