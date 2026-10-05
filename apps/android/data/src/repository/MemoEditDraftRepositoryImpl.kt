package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.MemoEditDraft
import com.lomo.domain.repository.MemoEditDraftRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
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

    private suspend fun decode(payload: String?): MemoEditDraft? {
        if (payload.isNullOrBlank()) return null
        return try {
            json.decodeFromString<MemoEditDraft>(payload)
        } catch (error: SerializationException) {
            migrateLegacy(payload) ?: throw IllegalStateException("Durable memo edit draft is corrupt", error)
        }
    }

    /**
     * One-shot migration for records written before [MemoEditDraft] carried a durable id. A
     * legacy payload is only accepted when it decodes exactly as the old shape; a fresh lease
     * identity is then minted and written back so the read path is canonical afterwards. Media
     * the killed session staged under its never-persisted in-memory id is already orphaned
     * ledger-side and is reclaimed by the normal orphan sweep — it cannot be re-bound because
     * that id was never durable anywhere.
     */
    private suspend fun migrateLegacy(payload: String): MemoEditDraft? {
        val legacy = try {
            json.decodeFromString<LegacyMemoEditDraft>(payload)
        } catch (ignored: SerializationException) {
            // behavior-contract: silent-result-ok: a payload that fails the legacy schema is
            // simply not a legacy draft; null means "nothing to migrate"
            return null
        }
        val migrated = MemoEditDraft(
            draftId = DraftId.mint(),
            memoId = legacy.memoId,
            baselineRevision = legacy.baselineRevision,
            baselineFingerprint = legacy.baselineFingerprint,
            content = legacy.content,
        )
        dataStore.updateMemoEditDraft(json.encodeToString(migrated))
        return migrated
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
}

@Serializable
private data class LegacyMemoEditDraft(
    val memoId: String,
    val baselineRevision: Long,
    val baselineFingerprint: String,
    val content: String,
)
