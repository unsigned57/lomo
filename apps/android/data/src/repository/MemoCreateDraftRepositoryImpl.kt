package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.MemoCreateDraft
import com.lomo.domain.repository.MemoCreateDraftRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
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
        val draft = MemoCreateDraft(draftId = DraftId.mint(), content = retired)
        dataStore.updateMemoCreateDraft(json.encodeToString(draft))
        dataStore.clearRetiredDraftText()
        return draft
    }

    private suspend fun decode(payload: String?): MemoCreateDraft? {
        if (payload.isNullOrBlank()) return null
        return try {
            json.decodeFromString<MemoCreateDraft>(payload)
        } catch (error: SerializationException) {
            migrateLegacy(payload) ?: throw IllegalStateException("Durable memo create draft is corrupt", error)
        }
    }

    /**
     * One-shot migration for records written before [MemoCreateDraft] carried a durable id. A
     * legacy payload is only accepted when it decodes exactly as the old shape; a fresh lease
     * identity is then minted and written back so the read path is canonical afterwards. Media
     * the killed session staged under its never-persisted in-memory id is already orphaned
     * ledger-side and is reclaimed by the normal orphan sweep — it cannot be re-bound because
     * that id was never durable anywhere.
     */
    private suspend fun migrateLegacy(payload: String): MemoCreateDraft? {
        val legacy = try {
            json.decodeFromString<LegacyMemoCreateDraft>(payload)
        } catch (ignored: SerializationException) {
            // behavior-contract: silent-result-ok: a payload that fails the legacy schema is
            // simply not a legacy draft; null means "nothing to migrate"
            return null
        }
        val migrated = MemoCreateDraft(draftId = DraftId.mint(), content = legacy.content)
        dataStore.updateMemoCreateDraft(json.encodeToString(migrated))
        return migrated
    }
}

@Serializable
private data class LegacyMemoCreateDraft(
    val content: String,
)
