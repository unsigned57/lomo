package com.lomo.data.reminder

/**
 * Applies a Rust-owned rolling-window alarm plan through [AlarmSchedulePort] only.
 *
 * Cancels prior request codes for the same identities when re-applied, then schedules the
 * next N alarms. Boot / cold-start `rebuildAll` feeds a full plan into [applyPlan].
 */
data class PlannedReminderAlarm(
    val memoId: String,
    val reminderId: String,
    val triggerAtUtcMillis: Long,
    val isCatchUp: Boolean = false,
)

data class RollingWindowApplyResult(
    val scheduled: List<AlarmScheduleResult>,
    val cancelledCount: Int,
)

class ReminderRollingWindowScheduler(
    private val port: AlarmSchedulePort,
    private val requestCodeOf: (memoId: String, reminderId: String) -> Int =
        ReminderRequestCodePolicy::alarmRequestCode,
) {
    private val activeKeys = linkedMapOf<String, Pair<String, String>>()

    /**
     * Full rolling-window replace (boot / rebuildAll): cancels identities absent from [alarms],
     * then schedules the provided window.
     */
    fun applyPlan(alarms: List<PlannedReminderAlarm>): RollingWindowApplyResult =
        applyPlanInternal(alarms, scopeMemoIds = null)

    /**
     * Memo-scoped reschedule: only cancels/replaces identities for [memoIds]; other memos keep
     * their active alarms.
     */
    fun applyPlanForMemos(
        memoIds: Set<String>,
        alarms: List<PlannedReminderAlarm>,
    ): RollingWindowApplyResult = applyPlanInternal(alarms, scopeMemoIds = memoIds)

    private fun applyPlanInternal(
        alarms: List<PlannedReminderAlarm>,
        scopeMemoIds: Set<String>?,
    ): RollingWindowApplyResult {
        var cancelled = 0
        val nextKeys = alarms.map { keyOf(it.memoId, it.reminderId) }.toSet()
        val stale =
            activeKeys.keys.filter { key ->
                val (memoId, _) = activeKeys[key] ?: return@filter false
                val inScope = scopeMemoIds == null || memoId in scopeMemoIds
                inScope && key !in nextKeys
            }
        for (key in stale) {
            val (memoId, reminderId) = activeKeys.remove(key) ?: continue
            port.cancel(requestCodeOf(memoId, reminderId), memoId, reminderId)
            cancelled++
        }
        val scheduled = mutableListOf<AlarmScheduleResult>()
        for (alarm in alarms) {
            val requestCode = requestCodeOf(alarm.memoId, alarm.reminderId)
            port.cancel(requestCode, alarm.memoId, alarm.reminderId)
            val result =
                port.schedule(
                    AlarmScheduleRequest(
                        requestCode = requestCode,
                        triggerAtUtcMillis = alarm.triggerAtUtcMillis,
                        memoId = alarm.memoId,
                        reminderId = alarm.reminderId,
                    ),
                )
            scheduled += result
            activeKeys[keyOf(alarm.memoId, alarm.reminderId)] = alarm.memoId to alarm.reminderId
        }
        return RollingWindowApplyResult(scheduled = scheduled, cancelledCount = cancelled)
    }

    fun cancelAll() {
        for ((_, pair) in activeKeys.toMap()) {
            val (memoId, reminderId) = pair
            port.cancel(requestCodeOf(memoId, reminderId), memoId, reminderId)
        }
        activeKeys.clear()
    }

    fun capability(): ExactAlarmCapability = port.exactAlarmCapability()

    private fun keyOf(
        memoId: String,
        reminderId: String,
    ): String = "$memoId\u001f$reminderId"
}
