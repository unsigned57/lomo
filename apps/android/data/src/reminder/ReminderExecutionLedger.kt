package com.lomo.data.reminder

import android.content.Context
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable ledger of reminder occurrences currently scheduled with AlarmManager.
 *
 * AlarmManager PendingIntents survive process death, so a process-local map can never enumerate
 * stale alarms after a restart or a workspace re-plan. This ledger is the cancellation authority:
 * every scheduled occurrence records its durable identity (`occurrence_id` issued by the Rust
 * plan, embedding workspace generation + reminder id + trigger instant) so a later plan can cancel
 * exactly the PendingIntents that still exist — regardless of which process scheduled them.
 *
 * The ledger is app-private platform bookkeeping, not workspace data: it is never consulted for
 * reminder semantics (the durable memo projection owns those) and it is rebuilt in full on every
 * `applyPlan`. A payload that cannot be decoded is quarantined aside and rebuilt empty; orphaned
 * alarms self-validate against the projection when they fire.
 */
internal class ReminderExecutionLedger(
    private val rootDir: File,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) {
    constructor(context: Context) : this(File(context.filesDir, "lomo-reminder-ledger"))

    @Serializable
    data class ScheduledOccurrence(
        val memoId: String,
        val reminderId: String,
        val triggerAtUtcMillis: Long,
    )

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val entries = ConcurrentHashMap<String, ScheduledOccurrence>()
    private val loadMutex = Mutex()
    private val loaded = AtomicBoolean(false)

    /** All scheduled occurrences keyed by durable occurrence id. */
    suspend fun snapshot(): Map<String, ScheduledOccurrence> {
        ensureLoaded()
        return entries.toMap()
    }

    suspend fun recordScheduled(
        occurrenceId: String,
        occurrence: ScheduledOccurrence,
    ) {
        require(occurrenceId.isNotBlank()) { "Reminder occurrence identity must not be blank" }
        ensureLoaded()
        entries[occurrenceId] = occurrence
        persist()
    }

    suspend fun remove(occurrenceIds: Collection<String>) {
        if (occurrenceIds.isEmpty()) return
        ensureLoaded()
        occurrenceIds.forEach(entries::remove)
        persist()
    }

    /** Removes and returns every recorded occurrence for [memoId] limited to [reminderIds]. */
    suspend fun removeForReminders(
        memoId: String,
        reminderIds: Set<String>,
    ): Map<String, ScheduledOccurrence> {
        ensureLoaded()
        val matched =
            entries.filterValues { it.memoId == memoId && it.reminderId in reminderIds }
        matched.keys.forEach(entries::remove)
        persist()
        return matched
    }

    suspend fun clear() {
        ensureLoaded()
        entries.clear()
        persist()
    }

    private suspend fun ensureLoaded() {
        if (loaded.get()) return
        loadMutex.withLock {
            if (loaded.get()) return
            withContext(dispatcherProvider.io) {
                rootDir.mkdirs()
                val file = File(rootDir, FILE_NAME)
                if (file.isFile) {
                    val decoded =
                        // behavior-contract: silent-result-ok: corrupt ledger quarantined, rebuilt empty
                        runCatching { json.decodeFromString<LedgerEnvelope>(file.readText()).entries }
                            .getOrElse { error ->
                                if (error is SerializationException) {
                                    file.renameTo(
                                        File(rootDir, "$FILE_NAME.corrupt-${System.currentTimeMillis()}"),
                                    )
                                    emptyMap()
                                } else {
                                    throw error
                                }
                            }
                    decoded.forEach { (id, occurrence) -> entries[id] = occurrence }
                }
                loaded.set(true)
            }
        }
    }

    private suspend fun persist() {
        withContext(dispatcherProvider.io) {
            val file = File(rootDir, FILE_NAME)
            val tmp = File(rootDir, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(LedgerEnvelope(entries = entries.toMap())))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    @Serializable
    private data class LedgerEnvelope(
        val entries: Map<String, ScheduledOccurrence>,
    )

    private companion object {
        const val FILE_NAME = "execution_ledger.v1.json"
    }
}
