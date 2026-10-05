package com.lomo.data.reminder

/**
 * Applies a Rust-owned rolling-window alarm plan through [AlarmSchedulePort] only.
 *
 * Alarm identity is the durable [PlannedReminderAlarm.occurrenceId] issued by the Rust plan
 * (workspace generation + reminder definition + trigger instant). Scheduled occurrences are
 * recorded in [ReminderExecutionLedger] so cancellation survives process death: stale PendingIntents
 * are discovered from the ledger, never from an in-process map. Boot / cold-start `rebuildAll`
 * feeds a full plan into [applyPlan].
 */
data class PlannedReminderAlarm(
    val occurrenceId: String,
    val memoId: String,
    val reminderId: String,
    val triggerAtUtcMillis: Long,
    val isCatchUp: Boolean = false,
)

data class RollingWindowApplyResult(
    val scheduled: List<AlarmScheduleResult>,
    val cancelledCount: Int,
    /**
     * Occurrences whose durable identity (occurrenceId + memoId + reminderId + triggerAt) and
     * delivery mode already match the ledger — their PendingIntents still hold, so no binder
     * call was made.
     */
    val unchangedCount: Int,
)

class ReminderRollingWindowScheduler
internal constructor(
    private val port: AlarmSchedulePort,
    private val ledger: ReminderExecutionLedger,
) {
    /**
     * Full rolling-window replace (boot / rebuildAll): cancels recorded occurrences absent from
     * [alarms], then schedules the provided window.
     */
    suspend fun applyPlan(alarms: List<PlannedReminderAlarm>): RollingWindowApplyResult =
        applyPlanInternal(alarms, scopeMemoIds = null)

    /**
     * Memo-scoped reschedule: only cancels/replaces occurrences for [memoIds]; other memos keep
     * their active alarms.
     */
    suspend fun applyPlanForMemos(
        memoIds: Set<String>,
        alarms: List<PlannedReminderAlarm>,
    ): RollingWindowApplyResult = applyPlanInternal(alarms, scopeMemoIds = memoIds)

    /**
     * Cancels a memo's alarms for the reminder identities supplied by the caller.
     *
     * The durable ledger is the cancellation authority: a delete can happen after a process restart
     * or before this scheduler instance ever applied a plan, so cancellation enumerates recorded
     * occurrences for those reminder ids rather than any in-process cache.
     */
    suspend fun cancelForMemo(
        memoId: String,
        reminderIds: Set<String>,
    ) {
        require(memoId.isNotBlank()) { "Memo identity must not be blank" }
        reminderIds.forEach { reminderId ->
            require(reminderId.isNotBlank()) { "Reminder identity must not be blank" }
        }
        val removed = ledger.removeForReminders(memoId, reminderIds)
        // behavior-contract: loop-io-ok: no bulk alarm cancel API; each iteration is one occurrence
        removed.forEach { (occurrenceId, occurrence) ->
            port.cancel(occurrenceId, occurrence.memoId, occurrence.reminderId)
        }
    }

    private suspend fun applyPlanInternal(
        alarms: List<PlannedReminderAlarm>,
        scopeMemoIds: Set<String>?,
    ): RollingWindowApplyResult {
        val known = ledger.snapshot()
        val nextIds = alarms.mapTo(HashSet()) { it.occurrenceId }
        val stale =
            known.filter { (occurrenceId, occurrence) ->
                val inScope = scopeMemoIds == null || occurrence.memoId in scopeMemoIds
                inScope && occurrenceId !in nextIds
            }
        ledger.remove(stale.keys)
        // behavior-contract: loop-io-ok: no bulk alarm cancel API; each iteration is one occurrence
        stale.forEach { (occurrenceId, occurrence) ->
            port.cancel(occurrenceId, occurrence.memoId, occurrence.reminderId)
        }
        // The ledger-recorded mode is compared against the mode a fresh schedule would use so
        // a capability flip (exact-alarm grant revoked) or an earlier rescue fallback
        // re-schedules instead of trusting a stale PendingIntent.
        val plannedMode = port.plannedMode()
        var unchangedCount = 0
        val scheduled = mutableListOf<AlarmScheduleResult>()
        for (alarm in alarms) {
            require(alarm.occurrenceId.isNotBlank()) { "Reminder occurrence identity must not be blank" }
            val recorded = known[alarm.occurrenceId]
            if (recorded != null && recorded.matchesPlan(alarm, plannedMode)) {
                unchangedCount += 1
                continue
            }
            // behavior-contract: loop-io-ok: no bulk alarm schedule API; each iteration is one occurrence
            val result =
                port.schedule(
                    AlarmScheduleRequest(
                        occurrenceId = alarm.occurrenceId,
                        triggerAtUtcMillis = alarm.triggerAtUtcMillis,
                        memoId = alarm.memoId,
                        reminderId = alarm.reminderId,
                    ),
                )
            scheduled += result
            ledger.recordScheduled(
                alarm.occurrenceId,
                ReminderExecutionLedger.ScheduledOccurrence(
                    memoId = alarm.memoId,
                    reminderId = alarm.reminderId,
                    triggerAtUtcMillis = alarm.triggerAtUtcMillis,
                    mode = result.mode,
                ),
            )
        }
        return RollingWindowApplyResult(
            scheduled = scheduled,
            cancelledCount = stale.size,
            unchangedCount = unchangedCount,
        )
    }

    suspend fun cancelAll() {
        val known = ledger.snapshot()
        ledger.clear()
        // behavior-contract: loop-io-ok: no bulk alarm cancel API; each iteration is one occurrence
        known.forEach { (occurrenceId, occurrence) ->
            port.cancel(occurrenceId, occurrence.memoId, occurrence.reminderId)
        }
    }

    fun capability(): ExactAlarmCapability = port.exactAlarmCapability()
}

private fun ReminderExecutionLedger.ScheduledOccurrence.matchesPlan(
    alarm: PlannedReminderAlarm,
    plannedMode: AlarmTriggerMode,
): Boolean =
    memoId == alarm.memoId &&
        reminderId == alarm.reminderId &&
        triggerAtUtcMillis == alarm.triggerAtUtcMillis &&
        mode == plannedMode
