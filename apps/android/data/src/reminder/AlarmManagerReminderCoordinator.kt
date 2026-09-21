package com.lomo.data.reminder

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lomo.domain.repository.ReminderCoordinator
import com.lomo.domain.model.ReminderIntervalDefaults
import com.lomo.domain.repository.MarkdownReminderRepository
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.repository.ReminderTokenFactory
import com.lomo.data.engine.store.StoreMemoQuery
import com.lomo.data.engine.store.StorePageCursor
import com.lomo.data.engine.store.StorePort
import com.lomo.data.engine.store.StoreMemoSummary
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.repository.EngineReadinessRepository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


private const val PREFS_NAME = "lomo_reminder_prefs"
private const val KEY_INTERVAL_MILLIS = "reminder_interval_millis"

internal fun forEachStoreMemoPage(
    pageSize: Int,
    loadPage: (cursor: StorePageCursor?, limit: Int) -> com.lomo.data.engine.store.StoreMemoPage,
    consume: (StoreMemoSummary) -> Unit,
) {
    require(pageSize > 0) { "Memo page size must be positive" }
    var cursor: StorePageCursor? = null
    val seenCursors = mutableSetOf<String>()
    while (true) {
        val page = loadPage(cursor, pageSize)
        if (page.items.isEmpty()) return
        page.items.forEach(consume)
        val next = page.nextCursor ?: return
        check(seenCursors.add(next.encoded)) {
            "Store memo cursor repeated before reminder rebuild completed"
        }
        cursor = next
    }
}

interface MemoMutationReminderScheduler {
    suspend fun syncForMemo(memoId: String)

    /**
     * Cancels the exact reminder identities present in the pre-mutation snapshot.
     *
     * The set is deliberately explicit: an in-process active-alarm map is only a cache and
     * cannot be used as the cancellation authority after process death or a scheduler restart.
     */
    suspend fun cancelForMemo(
        memoId: String,
        reminderIds: Set<String>,
    )
}

/**
 * Production scheduler still bridges Room-era marker lists into [AlarmSchedulePort].
 *
 * P3-08: all AlarmManager I/O goes through [schedulePort] (schedule/cancel + capability/mode).
 * Recurrence/next-trigger **plan** authority moves to Rust (P3-07); full DI cutover is P3-10.
 *
 * Residual tails (documented, not product rewrite here):
 * - markDone/recordFired still rewrite Markdown via domain repositories until P3-10.
 * - Camera/share/widget external writes still use existing memo mutation paths; they must not
 *   invent private file writes outside command submission (enforced at those call sites).
 * - The snooze *interval preference* is a user setting held in prefs; snooze *state* is durable
 *   app-private Rust data written through `sessionSnoozeReminder` and consumed by the plan query.
 * - Scheduled-occurrence cancellation is ledger-authoritative
 *   ([ReminderExecutionLedger]); no process-local map decides which PendingIntents exist.
 */
class AlarmManagerReminderScheduler(
    private val context: Context,
    private val storePort: StorePort,
    private val readiness: EngineReadinessRepository,
    private val schedulePort: AlarmSchedulePort = AndroidAlarmSchedulePort(context),
    private val rollingWindow: ReminderRollingWindowScheduler =
        ReminderRollingWindowScheduler(schedulePort, ReminderExecutionLedger(context)),
) : MemoMutationReminderScheduler {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _globalIntervalMillis =
        MutableStateFlow(
            prefs.getLong(KEY_INTERVAL_MILLIS, ReminderIntervalDefaults.DEFAULT_MILLIS),
        )
    val globalIntervalMillis: StateFlow<Long> = _globalIntervalMillis.asStateFlow()

    fun exactAlarmCapability(): ExactAlarmCapability = schedulePort.exactAlarmCapability()

    fun setGlobalIntervalMillis(millis: Long) {
        require(millis in ReminderIntervalDefaults.SUPPORTED_MILLIS) {
            "Unsupported snooze interval: $millis"
        }
        prefs.edit { putLong(KEY_INTERVAL_MILLIS, millis) }
        _globalIntervalMillis.value = millis
    }

    override suspend fun syncForMemo(memoId: String) {
        val alarms = withSnoozeRecovery { queryRustPlan(System.currentTimeMillis()) }
        rollingWindow.applyPlanForMemos(setOf(memoId), alarms)
    }

    override suspend fun cancelForMemo(
        memoId: String,
        reminderIds: Set<String>,
    ) {
        rollingWindow.cancelForMemo(memoId, reminderIds)
    }

    suspend fun rebuildAll() {
        val alarms = withSnoozeRecovery { queryRustPlan(System.currentTimeMillis()) }
        rollingWindow.applyPlan(alarms)
    }

    /**
     * Writes the durable snooze binding through the Rust session, then re-plans so the snoozed
     * occurrence is scheduled from durable state rather than a Kotlin-side ad-hoc alarm. The
     * deadline instant is computed on the owner clock from the validated duration preference.
     */
    suspend fun snooze(
        memoId: String,
        reminderId: String,
    ) {
        withSnoozeRecovery { storePort.snoozeReminder(reminderId, _globalIntervalMillis.value) }
        syncForMemo(memoId)
    }

    suspend fun cancelAlarm(
        memoId: String,
        reminderId: String,
    ) {
        rollingWindow.cancelForMemo(memoId, setOf(reminderId))
    }

    /**
     * Durable snooze corruption pauses planning with `reminder_recovery_needed`. Recovery is an
     * explicit FFI transition: the corrupt payload is quarantined as evidence and a fresh store is
     * persisted before the operation retries once.
     */
    private fun <T> withSnoozeRecovery(block: () -> T): T =
        try {
            block()
        } catch (failure: EngineCommandFailureException) {
            if (failure.failure.code != REMINDER_RECOVERY_NEEDED) throw failure
            storePort.recoverReminderSnooze()
            block()
        }

    private fun queryRustPlan(nowMillis: Long): List<PlannedReminderAlarm> {
        readiness.workspaceAuthority.value
            ?: error("Reminder planning requires an active workspace authority")
        // Time-zone facts, session set, rolling window, and generation are owned by the Rust
        // session plan; Kotlin only supplies the wall clock instant and applies the result.
        val plan = storePort.queryReminderPlan(nowMillis)
        return plan.alarms.map { alarm ->
            PlannedReminderAlarm(
                occurrenceId = alarm.occurrenceId,
                memoId = alarm.memoIdentity,
                reminderId = alarm.opaqueId,
                triggerAtUtcMillis = alarm.triggerAtUtcMs,
                isCatchUp = alarm.isCatchUp,
            )
        }
    }

    private companion object {
        const val REMINDER_RECOVERY_NEEDED = "reminder_recovery_needed"
    }
}

class AlarmManagerReminderCoordinator(
    private val scheduler: AlarmManagerReminderScheduler,
    private val memoQueryRepository: MemoQueryRepository,
    private val markdownReminderRepository: MarkdownReminderRepository,
    private val memoMutationRepository: MemoMutationRepository,
    private val reminderTokenFactory: ReminderTokenFactory,
) : ReminderCoordinator {
        override val globalIntervalMillis: StateFlow<Long> = scheduler.globalIntervalMillis

        override suspend fun setGlobalIntervalMillis(millis: Long) {
            scheduler.setGlobalIntervalMillis(millis)
        }

        override suspend fun syncForMemo(memoId: String) {
            scheduler.syncForMemo(memoId)
        }

        override suspend fun cancelForMemo(
            memoId: String,
            reminderIds: Set<String>,
        ) {
            scheduler.cancelForMemo(memoId, reminderIds)
        }

        override suspend fun rebuildAll() {
            scheduler.rebuildAll()
        }

        override suspend fun snooze(
            memoId: String,
            reminderId: String,
        ) {
            scheduler.snooze(memoId, reminderId)
        }

        override suspend fun markDone(
            memoId: String,
            reminderId: String,
        ) {
            mutateMemoMarker(memoId, reminderId) { token ->
                reminderTokenFactory.planMarkDone(token)
            }
            scheduler.cancelAlarm(memoId, reminderId)
        }

        override suspend fun recordFired(
            memoId: String,
            reminderId: String,
        ) {
            mutateMemoMarker(memoId, reminderId) { token ->
                reminderTokenFactory.planRecordFired(token)
            }
        }

        private suspend fun mutateMemoMarker(
            memoId: String,
            reminderId: String,
            planToken: (String) -> String,
        ) {
            val marker =
                memoQueryRepository
                    .getMemoById(memoId)
                    ?.run {
                        reminders.singleOrNull { it.reference.opaqueId == reminderId }
                    }
                    ?: return
            val newToken = planToken(marker.token)
            if (newToken == marker.token) return
            val mutation = markdownReminderRepository.rewriteReminder(marker.reference, newToken)
            memoMutationRepository.commitDocumentMutation(mutation)
            scheduler.syncForMemo(memoId)
        }

    }
