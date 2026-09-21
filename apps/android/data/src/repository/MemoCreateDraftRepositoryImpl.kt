package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.MemoCreateDraft
import com.lomo.domain.repository.MemoCreateDraftRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * DataStore implementation of the durable new-memo draft slot.
 *
 * The retired plain-text key is imported once and then removed: the migration copies only the text
 * the old track actually owned, and never invents an attachment or a baseline.
 */
class MemoCreateDraftRepositoryImpl(
    private val dataStore: LomoDataStore,
) : MemoCreateDraftRepository {
    private val operationLock = Mutex()
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun read(): MemoCreateDraft? =
        operationLock.withLock {
            decode(dataStore.memoCreateDraft.first()) ?: importRetiredDraftText()
        }

    override suspend fun write(draft: MemoCreateDraft) {
        operationLock.withLock {
            dataStore.updateMemoCreateDraft(json.encodeToString(draft))
        }
    }

    override suspend fun clear() {
        operationLock.withLock {
            dataStore.updateMemoCreateDraft(null)
        }
    }

    private suspend fun importRetiredDraftText(): MemoCreateDraft? {
        val retired = dataStore.retiredDraftText.first()
        if (retired.isBlank()) {
            return null
        }
        val draft = MemoCreateDraft(retired)
        dataStore.updateMemoCreateDraft(json.encodeToString(draft))
        dataStore.clearRetiredDraftText()
        return draft
    }

    private fun decode(payload: String?): MemoCreateDraft? {
        if (payload.isNullOrBlank()) return null
        return try {
            json.decodeFromString<MemoCreateDraft>(payload)
        } catch (error: SerializationException) {
            throw IllegalStateException("Durable memo create draft is corrupt", error)
        }
    }
}
