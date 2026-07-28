package com.lomo.data.local

import android.content.Context
import com.lomo.data.local.dao.PendingSyncReviewDao
import com.lomo.data.local.dao.SyncStateResetDao
import com.lomo.data.local.entity.PendingSyncReviewEntity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Post P5-13: file-backed tables retained only for independent Sync Inbox pending review.
 *
 * Remote-sync journals/index/shards/conflicts are owned by `.lomo/sync/v1` in `lomo-sync`.
 * Memo/query/FTS projections remain in the Rust store owner only.
 */
class FileBackedSyncDatabase(
    private val rootDir: File,
) {
    constructor(context: Context) : this(File(context.filesDir, "lomo-sync-tables"))

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val mutex = Mutex()

    private val pendingReviews = ConcurrentHashMap<String, PendingSyncReviewEntity>()

    init {
        rootDir.mkdirs()
        loadAll()
    }

    val pendingSyncReviewDao: PendingSyncReviewDao = PendingSyncReviewDaoImpl()
    val syncStateResetDao: SyncStateResetDao = SyncStateResetDaoImpl()

    suspend fun <T> runInTransaction(block: suspend () -> T): T = mutex.withLock { block() }

    private fun key2(
        a: String,
        b: String,
    ): String = a + "\u0000" + b

    private fun loadAll() {
        loadList<PendingSyncReviewEntity>("pending_reviews.json") {
            pendingReviews[key2(it.workspaceGeneration, it.backend)] = it
        }
    }

    private inline fun <reified T> loadList(
        name: String,
        put: (T) -> Unit,
    ) {
        val file = File(rootDir, name)
        if (!file.isFile) return
        // behavior-contract: silent-result-ok: corrupt inbox table file is clean-slate discarded
        runCatching {
            json.decodeFromString<ListEnvelope<T>>(file.readText()).items.forEach(put)
        }
    }

    private inline fun <reified T> persist(
        name: String,
        items: Collection<T>,
    ) {
        val file = File(rootDir, name)
        val tmp = File(rootDir, "$name.tmp")
        tmp.writeText(json.encodeToString(ListEnvelope(items = items.toList())))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    @Serializable
    private data class ListEnvelope<T>(
        val items: List<T>,
    )

    private inner class PendingSyncReviewDaoImpl : PendingSyncReviewDao {
        override suspend fun getByBackend(
            backend: String,
            workspaceGeneration: String,
        ): PendingSyncReviewEntity? = pendingReviews[key2(workspaceGeneration, backend)]

        override suspend fun upsert(entity: PendingSyncReviewEntity) {
            pendingReviews[key2(entity.workspaceGeneration, entity.backend)] = entity
            persist("pending_reviews.json", pendingReviews.values)
        }

        override suspend fun deleteByBackend(
            backend: String,
            workspaceGeneration: String,
        ) {
            pendingReviews.remove(key2(workspaceGeneration, backend))
            persist("pending_reviews.json", pendingReviews.values)
        }
    }

    private inner class SyncStateResetDaoImpl : SyncStateResetDao {
        override suspend fun clearPendingSyncReviews() {
            pendingReviews.clear()
            persist("pending_reviews.json", emptyList<PendingSyncReviewEntity>())
        }
    }
}
