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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.ZoneId


private const val PREFS_NAME = "lomo_reminder_prefs"
private const val KEY_INTERVAL_MILLIS = "reminder_interval_millis"

interface MemoMutationReminderScheduler {
    suspend fun syncForMemo(memoId: String)

    suspend fun cancelForMemo(memoId: String)
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
 * - Snooze interval prefs remain process-local until Rust app-private snooze is production-wired.
 */
class AlarmManagerReminderScheduler(
    private val context: Context,
    private val memoQueryRepository: MemoQueryRepository,
    private val markdownReminderRepository: MarkdownReminderRepository,
    private val schedulePort: AlarmSchedulePort = AndroidAlarmSchedulePort(context),
    private val rollingWindow: ReminderRollingWindowScheduler =
        ReminderRollingWindowScheduler(schedulePort),
) : MemoMutationReminderScheduler {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _globalIntervalMillis =
        MutableStateFlow(
            prefs.getLong(KEY_INTERVAL_MILLIS, ReminderIntervalDefaults.DEFAULT_MILLIS),
        )
    val globalIntervalMillis: StateFlow<Long> = _globalIntervalMillis.asStateFlow()

    fun exactAlarmCapability(): ExactAlarmCapability = schedulePort.exactAlarmCapability()

    suspend fun setGlobalIntervalMillis(millis: Long) {
        val sanitized =
            if (millis in ReminderIntervalDefaults.SUPPORTED_MILLIS) {
                millis
            } else {
                ReminderIntervalDefaults.DEFAULT_MILLIS
            }
        prefs.edit { putLong(KEY_INTERVAL_MILLIS, sanitized) }
        _globalIntervalMillis.value = sanitized
    }

    override suspend fun syncForMemo(memoId: String) {
        val markers = markdownReminderRepository.remindersForMemo(memoId)
        val nowMillis = System.currentTimeMillis()
        val alarms =
            markers.mapNotNull { marker ->
                planAlarm(memoId, marker, nowMillis)
            }
        rollingWindow.applyPlanForMemos(setOf(memoId), alarms)
    }

    override suspend fun cancelForMemo(memoId: String) {
        // Best-effort: alarms for stale markers are leaked until reboot or next edit.
        // Memo content is authoritative; boot receiver and subsequent CRUD rebuild.
    }

    suspend fun rebuildAll() {
        val memos = memoQueryRepository.getAllMemosList().first()
        val nowMillis = System.currentTimeMillis()
        val alarms =
            memos.flatMap { memo ->
                markdownReminderRepository.remindersForMemo(memo.id).mapNotNull { marker ->
                    planAlarm(memo.id, marker, nowMillis)
                }
            }
        rollingWindow.applyPlan(alarms)
    }

    suspend fun snooze(
        memoId: String,
        reminderId: String,
    ) {
        val interval = _globalIntervalMillis.value
        val triggerAt = System.currentTimeMillis() + interval
        schedulePort.schedule(
            AlarmScheduleRequest(
                requestCode = ReminderRequestCodePolicy.alarmRequestCode(memoId, reminderId),
                triggerAtUtcMillis = triggerAt,
                memoId = memoId,
                reminderId = reminderId,
            ),
        )
    }

    fun cancelAlarm(
        memoId: String,
        reminderId: String,
    ) {
        schedulePort.cancel(
            ReminderRequestCodePolicy.alarmRequestCode(memoId, reminderId),
            memoId,
            reminderId,
        )
    }

    private fun planAlarm(
        memoId: String,
        marker: ReminderMarker,
        nowMillis: Long,
    ): PlannedReminderAlarm? {
        if (marker.isExhausted) return null
        val baseTriggerAt =
            marker.dueAt
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        val triggerAt =
            if (marker.repeatCount > 1 && marker.firedCount > 0) {
                baseTriggerAt + (marker.firedCount * marker.intervalMinutes * 60 * 1000L)
            } else {
                baseTriggerAt
            }
        val whenToFire = if (triggerAt <= nowMillis) nowMillis + 500L else triggerAt
        return PlannedReminderAlarm(
            memoId = memoId,
            reminderId = marker.reference.opaqueId,
            triggerAtUtcMillis = whenToFire,
            isCatchUp = triggerAt <= nowMillis,
        )
    }
}

class AlarmManagerReminderCoordinator(
    private val scheduler: AlarmManagerReminderScheduler,
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

        override suspend fun cancelForMemo(memoId: String) {
            scheduler.cancelForMemo(memoId)
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
                markdownReminderRepository
                    .remindersForMemo(memoId)
                    .singleOrNull { it.reference.opaqueId == reminderId }
                    ?: return
            val newToken = planToken(marker.token)
            if (newToken == marker.token) return
            markdownReminderRepository.rewriteReminder(marker.reference, newToken)
            memoMutationRepository.refreshMemos()
            scheduler.syncForMemo(memoId)
        }

    }
