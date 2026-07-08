package com.lomo.data.repository

import com.lomo.data.local.dao.WebDavLocalChangeJournalDao
import com.lomo.data.local.entity.WebDavLocalChangeJournalEntity
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider


enum class WebDavLocalChangeKind {
    MEMO,
    IMAGE,
    VOICE,
}

enum class WebDavLocalChangeType {
    UPSERT,
    DELETE,
}

data class WebDavLocalChangeJournalEntry(
    val id: String,
    val kind: WebDavLocalChangeKind,
    val filename: String,
    val changeType: WebDavLocalChangeType,
    val updatedAt: Long,
) {
    fun relativePath(layout: SyncDirectoryLayout): String =
        when (kind) {
            WebDavLocalChangeKind.MEMO -> "$WEBDAV_ROOT/${layout.memoFolder}/$filename"
            WebDavLocalChangeKind.IMAGE -> "$WEBDAV_ROOT/${layout.imageFolder}/$filename"
            WebDavLocalChangeKind.VOICE -> "$WEBDAV_ROOT/${layout.voiceFolder}/$filename"
        }
}

interface WebDavLocalChangeJournalStore {
    val incrementalSyncEnabled: Boolean

    suspend fun read(): Map<String, WebDavLocalChangeJournalEntry>

    suspend fun upsert(entry: WebDavLocalChangeJournalEntry)

    suspend fun remove(ids: Collection<String>)

    suspend fun clear()
}

class RoomBackedWebDavLocalChangeJournalStore(
    private val dao: WebDavLocalChangeJournalDao,
    private val generationProvider: WorkspaceSyncGenerationProvider,
) : WebDavLocalChangeJournalStore {
        override val incrementalSyncEnabled: Boolean = true

        override suspend fun read(): Map<String, WebDavLocalChangeJournalEntry> =
            dao.getAll(activeGeneration()).associate { entity -> entity.id to entity.toModel() }

        override suspend fun upsert(entry: WebDavLocalChangeJournalEntry) {
            dao.upsert(entry.toEntity(activeGeneration()))
        }

        override suspend fun remove(ids: Collection<String>) {
            if (ids.isEmpty()) return
            dao.deleteByIds(ids = ids, workspaceGeneration = activeGeneration())
        }

        override suspend fun clear() {
            dao.clearAll(activeGeneration())
        }

        private suspend fun activeGeneration(): String = generationProvider.activeGeneration().value
    }

interface WebDavLocalChangeRecorder {
    suspend fun recordMemoUpsert(filename: String)

    suspend fun recordMemoDelete(filename: String)

    suspend fun recordImageUpsert(filename: String)

    suspend fun recordImageDelete(filename: String)

    suspend fun recordVoiceUpsert(filename: String)

    suspend fun recordVoiceDelete(filename: String)
}

class DefaultWebDavLocalChangeRecorder(
    private val journalStore: WebDavLocalChangeJournalStore,
) : WebDavLocalChangeRecorder {
        override suspend fun recordMemoUpsert(filename: String) =
            record(kind = WebDavLocalChangeKind.MEMO, filename = filename, changeType = WebDavLocalChangeType.UPSERT)

        override suspend fun recordMemoDelete(filename: String) =
            record(kind = WebDavLocalChangeKind.MEMO, filename = filename, changeType = WebDavLocalChangeType.DELETE)

        override suspend fun recordImageUpsert(filename: String) =
            record(kind = WebDavLocalChangeKind.IMAGE, filename = filename, changeType = WebDavLocalChangeType.UPSERT)

        override suspend fun recordImageDelete(filename: String) =
            record(kind = WebDavLocalChangeKind.IMAGE, filename = filename, changeType = WebDavLocalChangeType.DELETE)

        override suspend fun recordVoiceUpsert(filename: String) =
            record(kind = WebDavLocalChangeKind.VOICE, filename = filename, changeType = WebDavLocalChangeType.UPSERT)

        override suspend fun recordVoiceDelete(filename: String) =
            record(kind = WebDavLocalChangeKind.VOICE, filename = filename, changeType = WebDavLocalChangeType.DELETE)

        private suspend fun record(
            kind: WebDavLocalChangeKind,
            filename: String,
            changeType: WebDavLocalChangeType,
        ) {
            if (!journalStore.incrementalSyncEnabled || filename.isBlank()) {
                return
            }
            journalStore.upsert(
                WebDavLocalChangeJournalEntry(
                    id = "$kind:$filename",
                    kind = kind,
                    filename = filename,
                    changeType = changeType,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }



private fun WebDavLocalChangeJournalEntry.toEntity(workspaceGeneration: String): WebDavLocalChangeJournalEntity =
    WebDavLocalChangeJournalEntity(
        workspaceGeneration = workspaceGeneration,
        id = id,
        kind = kind.name,
        filename = filename,
        changeType = changeType.name,
        updatedAt = updatedAt,
    )

private fun WebDavLocalChangeJournalEntity.toModel(): WebDavLocalChangeJournalEntry =
    WebDavLocalChangeJournalEntry(
        id = id,
        kind = WebDavLocalChangeKind.valueOf(kind),
        filename = filename,
        changeType = WebDavLocalChangeType.valueOf(changeType),
        updatedAt = updatedAt,
    )
