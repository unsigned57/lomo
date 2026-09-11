package com.lomo.data.sync.pendingreview

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * File-backed [PendingReviewTable]: one JSON document of all records, rewritten atomically on
 * every mutation. Records load lazily on first use so graph construction never blocks the caller.
 */
class FileBackedPendingReviewTable(
    private val rootDir: File,
) : PendingReviewTable {
    constructor(context: Context) : this(File(context.filesDir, "lomo-sync-tables"))

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val pendingReviews = ConcurrentHashMap<String, PendingSyncReviewRecord>()
    private val loadMutex = Mutex()
    private val loaded = AtomicBoolean(false)

    override suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncReviewRecord? {
        ensureLoaded()
        return pendingReviews[key2(workspaceGeneration, backend)]
    }

    override suspend fun upsert(record: PendingSyncReviewRecord) {
        ensureLoaded()
        pendingReviews[key2(record.workspaceGeneration, record.backend)] = record
        persist(pendingReviews.values)
    }

    override suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    ) {
        ensureLoaded()
        pendingReviews.remove(key2(workspaceGeneration, backend))
        persist(pendingReviews.values)
    }

    override suspend fun clearAll() {
        ensureLoaded()
        pendingReviews.clear()
        persist(emptyList())
    }

    private fun key2(
        a: String,
        b: String,
    ): String = a + "\u0000" + b

    private suspend fun ensureLoaded() {
        if (loaded.get()) return
        loadMutex.withLock {
            if (!loaded.get()) {
                withContext(Dispatchers.IO) {
                    rootDir.mkdirs()
                    val file = File(rootDir, "pending_reviews.json")
                    if (file.isFile) {
                        // behavior-contract: silent-result-ok: corrupt inbox table file is clean-slate discarded
                        runCatching {
                            json
                                .decodeFromString<ListEnvelope<PendingSyncReviewRecord>>(file.readText())
                                .items
                                .forEach { record ->
                                    pendingReviews[key2(record.workspaceGeneration, record.backend)] = record
                                }
                        }
                    }
                    loaded.set(true)
                }
            }
        }
    }

    private suspend fun persist(items: Collection<PendingSyncReviewRecord>) {
        withContext(Dispatchers.IO) {
            val file = File(rootDir, "pending_reviews.json")
            val tmp = File(rootDir, "pending_reviews.json.tmp")
            tmp.writeText(json.encodeToString(ListEnvelope(items = items.toList())))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    @Serializable
    private data class ListEnvelope<T>(
        val items: List<T>,
    )
}
