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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "lomo_reminder_prefs"
private const val KEY_INTERVAL_MILLIS = "reminder_interval_millis"

@Singleton
class AlarmManagerReminderCoordinator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val memoQueryRepository: MemoQueryRepository,
        private val memoMutationRepository: MemoMutationRepository,
    ) : ReminderCoordinator {
        private val alarmManager: AlarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        private val parseReminders = ParseRemindersUseCase()
        private val rewriteToken = RewriteReminderTokenUseCase()

        private val _globalIntervalMillis =
            MutableStateFlow(
                prefs.getLong(KEY_INTERVAL_MILLIS, ReminderIntervalDefaults.DEFAULT_MILLIS),
            )
        override val globalIntervalMillis: StateFlow<Long> = _globalIntervalMillis.asStateFlow()

        override suspend fun setGlobalIntervalMillis(millis: Long) {
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

        override suspend fun rebuildAll() {
            val memos = memoQueryRepository.getAllMemosList().first()
            val nowMillis = System.currentTimeMillis()
            memos.forEach { memo ->
                parseReminders(memo.content).forEach { marker ->
                    reschedule(memo.id, marker, nowMillis)
                }
            }
        }

        override suspend fun snooze(
            memoId: String,
            tokenRaw: String,
        ) {
            val interval = _globalIntervalMillis.value
            val pendingIntent = alarmPendingIntent(memoId, tokenRaw)
            val triggerAt = System.currentTimeMillis() + interval
            setAlarmClock(triggerAt, pendingIntent)
        }

        override suspend fun markDone(
            memoId: String,
            tokenRaw: String,
        ) {
            mutateMemoMarker(memoId, tokenRaw) { marker ->
                ReminderMarker.canonicalToken(
                    dueAt = marker.dueAt,
                    repeatCount = marker.repeatCount,
                    firedCount = marker.firedCount,
                    done = true,
                )
            }
            alarmManager.cancel(alarmPendingIntent(memoId, tokenRaw))
        }

        override suspend fun recordFired(
            memoId: String,
            tokenRaw: String,
        ) {
            mutateMemoMarker(memoId, tokenRaw) { marker ->
                val newFired = (marker.firedCount + 1).coerceAtMost(marker.repeatCount)
                val isExhausted = newFired >= marker.repeatCount
                ReminderMarker.canonicalToken(
                    dueAt = marker.dueAt,
                    repeatCount = marker.repeatCount,
                    firedCount = newFired,
                    done = isExhausted,
                )
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

        private fun reschedule(
            memoId: String,
            marker: ReminderMarker,
            nowMillis: Long,
        ) {
            val pendingIntent = alarmPendingIntent(memoId, marker.raw)
            alarmManager.cancel(pendingIntent)
            if (marker.isExhausted) return
            val triggerAt =
                marker.dueAt
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
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
                requestCodeFor(memoId, tokenRaw),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        companion object {
            internal fun requestCodeFor(
                memoId: String,
                tokenRaw: String,
            ): Int = ("$memoId|$tokenRaw").hashCode()

            internal fun nowLocal(): LocalDateTime = LocalDateTime.now()
        }
    }
