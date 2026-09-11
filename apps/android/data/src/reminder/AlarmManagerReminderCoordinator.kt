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
import com.lomo.data.engine.store.StoreReminderQuery
import com.lomo.data.engine.store.StoreReminderSession
import com.lomo.data.engine.store.StoreTimeZoneContext
import com.lomo.data.engine.store.StoreZoneTransition
import com.lomo.domain.repository.EngineReadinessRepository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId


private const val PREFS_NAME = "lomo_reminder_prefs"
private const val KEY_INTERVAL_MILLIS = "reminder_interval_millis"
private const val REMINDER_MEMO_PAGE_SIZE = 50

internal suspend fun forEachStoreMemoPage(
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
 * - Snooze interval prefs remain process-local until Rust app-private snooze is production-wired.
 */
class AlarmManagerReminderScheduler(
    private val context: Context,
    private val memoQueryRepository: MemoQueryRepository,
    private val storePort: StorePort,
    private val readiness: EngineReadinessRepository,
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
        val memo = memoQueryRepository.getMemoById(memoId)
        val nowMillis = System.currentTimeMillis()
        val sessions =
            if (memo == null) {
                emptyList()
            } else {
                memo.reminders.map { marker -> marker.toStoreSession(memo.id) }
            }
        val alarms = queryRustPlan(nowMillis, sessions)
        rollingWindow.applyPlanForMemos(setOf(memoId), alarms)
    }

    override suspend fun cancelForMemo(
        memoId: String,
        reminderIds: Set<String>,
    ) {
        rollingWindow.cancelForMemo(memoId, reminderIds)
    }

    suspend fun rebuildAll() {
        val nowMillis = System.currentTimeMillis()
        val sessions = mutableListOf<StoreReminderSession>()
        forEachStoreMemoPage(
            pageSize = REMINDER_MEMO_PAGE_SIZE,
            loadPage = { cursor, limit ->
                storePort.queryMemos(
                    query = StoreMemoQuery(),
                    cursor = cursor,
                    pageSize = limit,
                )
            },
        ) { summary ->
            summary.reminders.mapTo(sessions) { marker -> marker.toStoreSession(summary.memoId) }
        }
        val alarms = queryRustPlan(nowMillis, sessions)
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

    private fun queryRustPlan(
        nowMillis: Long,
        sessions: List<StoreReminderSession>,
    ): List<PlannedReminderAlarm> {
        val authority =
            readiness.workspaceAuthority.value
                ?: error("Reminder planning requires an active workspace authority")
        val plan =
            storePort.queryReminderPlan(
                StoreReminderQuery(
                    nowUtcMs = nowMillis,
                    zone = zoneContext(nowMillis),
                    sessions = sessions,
                    rollingWindow = REMINDER_ROLLING_WINDOW,
                    workspaceGeneration = authority.generation,
                ),
            )
        require(plan.workspaceGeneration == authority.generation.toString()) {
            "Rust reminder plan belongs to a different workspace generation"
        }
        return plan.alarms.map { alarm ->
            PlannedReminderAlarm(
                memoId = alarm.memoIdentity,
                reminderId = alarm.opaqueId,
                triggerAtUtcMillis = alarm.triggerAtUtcMs,
                isCatchUp = alarm.isCatchUp,
            )
        }
    }

    private fun zoneContext(nowMillis: Long): StoreTimeZoneContext {
        val zone = ZoneId.systemDefault()
        val rules = zone.rules
        val now = java.time.Instant.ofEpochMilli(nowMillis)
        val start = now.minusSeconds(ZONE_TRANSITION_LOOKBACK_SECONDS)
        val end = now.plusSeconds(ZONE_TRANSITION_LOOKAHEAD_SECONDS)
        val transitions = mutableListOf<StoreZoneTransition>()
        var cursor = start
        var exhausted = false
        while (transitions.size < MAX_ZONE_TRANSITIONS && !exhausted) {
            val transition = rules.nextTransition(cursor)
            if (transition == null || transition.instant.isAfter(end)) {
                exhausted = true
            } else {
                transitions +=
                    StoreZoneTransition(
                        transitionUtcMs = transition.instant.toEpochMilli(),
                        offsetBeforeSecs = transition.offsetBefore.totalSeconds,
                        offsetAfterSecs = transition.offsetAfter.totalSeconds,
                    )
                cursor = transition.instant.plusMillis(1L)
            }
        }
        return StoreTimeZoneContext(
            zoneId = zone.id,
            baseOffsetSecs = rules.getOffset(start).totalSeconds,
            transitions = transitions,
        )
    }

    private fun ReminderMarker.toStoreSession(memoId: String): StoreReminderSession =
        StoreReminderSession(
            opaqueId = reference.opaqueId,
            memoIdentity = memoId,
            memoRevision = reference.revision,
            token = token,
            dueAtLocal = dueAt.format(ReminderMarker.TIMESTAMP_FORMAT),
            repeatCount = repeatCount,
            firedCount = firedCount,
            done = done,
            intervalMinutes = intervalMinutes,
            recurrenceCode = recurrence.code,
        )

    private companion object {
        const val REMINDER_ROLLING_WINDOW = 64
        const val MAX_ZONE_TRANSITIONS = 32
        const val ZONE_TRANSITION_LOOKBACK_SECONDS = 370L * 24L * 60L * 60L
        const val ZONE_TRANSITION_LOOKAHEAD_SECONDS = 370L * 24L * 60L * 60L
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
                    ?.reminders
                    ?.singleOrNull { it.reference.opaqueId == reminderId }
                    ?: return
            val newToken = planToken(marker.token)
            if (newToken == marker.token) return
            val mutation = markdownReminderRepository.rewriteReminder(marker.reference, newToken)
            memoMutationRepository.commitDocumentMutation(mutation)
            scheduler.syncForMemo(memoId)
        }

    }
