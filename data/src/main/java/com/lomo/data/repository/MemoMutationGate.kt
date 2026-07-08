package com.lomo.data.repository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
/**
 * Serializes workspace refresh against user-initiated memo mutations so that a running refresh
 * cannot tombstone a memo that a concurrent mutation just persisted, and a mutation cannot
 * race the refresh's file-scan + DB-replace window (see C5 in the CRUD blueprint).
 */
class MemoMutationGate {
        private val mutex = Mutex()
        suspend fun <R> withLock(action: suspend () -> R): R = mutex.withLock { action() }
    }
