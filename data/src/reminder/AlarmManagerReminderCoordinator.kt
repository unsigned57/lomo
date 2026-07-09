package com.lomo.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.lomo.domain.repository.ReminderCoordinator
import com.lomo.domain.model.ReminderIntervalDefaults
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.usecase.ParseRemindersUseCase
import com.lomo.domain.usecase.RewriteReminderTokenUseCase
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.Recurrence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.ZoneId


private const val PREFS_NAME = "lomo_reminder_prefs"
private const val KEY_INTERVAL_MILLIS = "reminder_interval_millis"

interface MemoMutationReminderScheduler {
    suspend fun syncForMemo(
        memoId: String,
        content: String,
    )

    suspend fun cancelForMemo(memoId: String)
}

class AlarmManagerReminderScheduler(
    private val context: Context,
    private val memoQueryRepository: MemoQueryRepository,
) : MemoMutationReminderScheduler {
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val parseReminders = ParseRemindersUseCase()

    private val _globalIntervalMillis =
        MutableStateFlow(
            prefs.getLong(KEY_INTERVAL_MILLIS, ReminderIntervalDefaults.DEFAULT_MILLIS),
        )
    val globalIntervalMillis: StateFlow<Long> = _globalIntervalMillis.asStateFlow()

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

    override suspend fun syncForMemo(
        memoId: String,
        content: String,
    ) {
        val markers = parseReminders(content)
        val nowMillis = System.currentTimeMillis()
        markers.forEach { marker -> reschedule(memoId, marker, nowMillis) }
    }

    override suspend fun cancelForMemo(memoId: String) {
        // Best-effort: alarms for stale markers are leaked until reboot or next edit.
        // Memo content is authoritative; boot receiver and subsequent CRUD rebuild.
    }

    suspend fun rebuildAll() {
        val memos = memoQueryRepository.getAllMemosList().first()
        val nowMillis = System.currentTimeMillis()
        memos.forEach { memo ->
            parseReminders(memo.content).forEach { marker ->
                reschedule(memo.id, marker, nowMillis)
            }
        }
    }

    suspend fun snooze(
        memoId: String,
        tokenRaw: String,
    ) {
        val interval = _globalIntervalMillis.value
        val pendingIntent = alarmPendingIntent(memoId, tokenRaw)
        val triggerAt = System.currentTimeMillis() + interval
        setAlarmClock(triggerAt, pendingIntent)
    }

    fun cancelAlarm(
        memoId: String,
        tokenRaw: String,
    ) {
        alarmManager.cancel(alarmPendingIntent(memoId, tokenRaw))
    }

    private fun reschedule(
        memoId: String,
        marker: ReminderMarker,
        nowMillis: Long,
    ) {
        val pendingIntent = alarmPendingIntent(memoId, marker.raw)
        alarmManager.cancel(pendingIntent)
        if (marker.isExhausted) return
        val baseTriggerAt =
            marker.dueAt
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        val triggerAt = if (marker.repeatCount > 1 && marker.firedCount > 0) {
            baseTriggerAt + (marker.firedCount * marker.intervalMinutes * 60 * 1000L)
        } else {
            baseTriggerAt
        }
        val whenToFire = if (triggerAt <= nowMillis) nowMillis + 500L else triggerAt
        setAlarmClock(whenToFire, pendingIntent)
    }

    private fun setAlarmClock(
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            } else {
                val info = AlarmManager.AlarmClockInfo(triggerAtMillis, null)
                alarmManager.setAlarmClock(info, pendingIntent)
            }
        } catch (security: SecurityException) {
            Timber.tag("ReminderCoordinator").w(security, "exact alarm denied, fallback used")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        }
    }

    private fun alarmPendingIntent(
        memoId: String,
        tokenRaw: String,
    ): PendingIntent {
        val intent =
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ReminderIntents.ACTION_FIRE
                putExtra(ReminderIntents.EXTRA_MEMO_ID, memoId)
                putExtra(ReminderIntents.EXTRA_TOKEN_RAW, tokenRaw)
            }
        return PendingIntent.getBroadcast(
            context,
            ReminderRequestCodePolicy.alarmRequestCode(memoId, tokenRaw),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class AlarmManagerReminderCoordinator(
    private val scheduler: AlarmManagerReminderScheduler,
    private val memoQueryRepository: MemoQueryRepository,
    private val memoMutationRepository: MemoMutationRepository,
) : ReminderCoordinator {
        private val parseReminders = ParseRemindersUseCase()
        private val rewriteToken = RewriteReminderTokenUseCase()

        override val globalIntervalMillis: StateFlow<Long> = scheduler.globalIntervalMillis

        override suspend fun setGlobalIntervalMillis(millis: Long) {
            scheduler.setGlobalIntervalMillis(millis)
        }

        override suspend fun syncForMemo(
            memoId: String,
            content: String,
        ) {
            scheduler.syncForMemo(memoId, content)
        }

        override suspend fun cancelForMemo(memoId: String) {
            scheduler.cancelForMemo(memoId)
        }

        override suspend fun rebuildAll() {
            scheduler.rebuildAll()
        }

        override suspend fun snooze(
            memoId: String,
            tokenRaw: String,
        ) {
            scheduler.snooze(memoId, tokenRaw)
        }

        override suspend fun markDone(
            memoId: String,
            tokenRaw: String,
        ) {
            mutateMemoMarker(memoId, tokenRaw) { marker ->
                if (marker.recurrence != Recurrence.NONE) {
                    val nextDueAt = when (marker.recurrence) {
                        Recurrence.DAILY -> marker.dueAt.plusDays(1)
                        Recurrence.WEEKLY -> marker.dueAt.plusWeeks(1)
                        else -> marker.dueAt
                    }
                    ReminderMarker.canonicalToken(
                        dueAt = nextDueAt,
                        repeatCount = marker.repeatCount,
                        firedCount = 0,
                        done = false,
                        intervalMinutes = marker.intervalMinutes,
                        recurrence = marker.recurrence,
                    )
                } else {
                    ReminderMarker.canonicalToken(
                        dueAt = marker.dueAt,
                        repeatCount = marker.repeatCount,
                        firedCount = marker.firedCount,
                        done = true,
                        intervalMinutes = marker.intervalMinutes,
                        recurrence = marker.recurrence,
                    )
                }
            }
            scheduler.cancelAlarm(memoId, tokenRaw)
        }

        override suspend fun recordFired(
            memoId: String,
            tokenRaw: String,
        ) {
            mutateMemoMarker(memoId, tokenRaw) { marker ->
                val newFired = (marker.firedCount + 1).coerceAtMost(marker.repeatCount)
                val isExhausted = newFired >= marker.repeatCount
                if (isExhausted && marker.recurrence != Recurrence.NONE) {
                    val nextDueAt = when (marker.recurrence) {
                        Recurrence.DAILY -> marker.dueAt.plusDays(1)
                        Recurrence.WEEKLY -> marker.dueAt.plusWeeks(1)
                        else -> marker.dueAt
                    }
                    ReminderMarker.canonicalToken(
                        dueAt = nextDueAt,
                        repeatCount = marker.repeatCount,
                        firedCount = 0,
                        done = false,
                        intervalMinutes = marker.intervalMinutes,
                        recurrence = marker.recurrence,
                    )
                } else {
                    ReminderMarker.canonicalToken(
                        dueAt = marker.dueAt,
                        repeatCount = marker.repeatCount,
                        firedCount = newFired,
                        done = isExhausted,
                        intervalMinutes = marker.intervalMinutes,
                        recurrence = marker.recurrence,
                    )
                }
            }
        }

        private suspend fun mutateMemoMarker(
            memoId: String,
            tokenRaw: String,
            mutator: (ReminderMarker) -> String,
        ) {
            val memo = memoQueryRepository.getMemoById(memoId) ?: return
            val marker = parseReminders(memo.content).firstOrNull { it.raw == tokenRaw } ?: return
            val newToken = mutator(marker)
            if (newToken == marker.raw) return
            val newContent = rewriteToken(memo.content, marker, newToken)
            memoMutationRepository.updateMemo(memo, newContent)
        }

    }
