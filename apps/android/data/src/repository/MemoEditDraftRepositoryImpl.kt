package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.MemoEditDraft
import com.lomo.domain.repository.MemoEditDraftRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** DataStore implementation of the single durable edit-session slot. */
class MemoEditDraftRepositoryImpl(
    private val dataStore: LomoDataStore,
) : MemoEditDraftRepository {
    private val operationLock = Mutex()
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun read(): MemoEditDraft? =
        operationLock.withLock {
            decode(dataStore.memoEditDraft.first())
        }

    override suspend fun write(draft: MemoEditDraft) {
        operationLock.withLock {
            dataStore.updateMemoEditDraft(json.encodeToString(draft))
        }
    }

    override suspend fun clear(memoId: String) {
        require(memoId.isNotBlank()) { "Memo edit draft clear requires a memo id" }
        operationLock.withLock {
            val current = decode(dataStore.memoEditDraft.first())
            if (current?.memoId == memoId) {
                dataStore.updateMemoEditDraft(null)
            }
        }
    }

    private fun decode(payload: String?): MemoEditDraft? {
        if (payload.isNullOrBlank()) return null
        return try {
            json.decodeFromString<MemoEditDraft>(payload)
        } catch (error: SerializationException) {
            throw IllegalStateException("Durable memo edit draft is corrupt", error)
        }
    }
}
