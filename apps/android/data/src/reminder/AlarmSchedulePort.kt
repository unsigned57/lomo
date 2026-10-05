package com.lomo.data.reminder

import android.os.Build

/**
 * Pure AlarmManager schedule/cancel port (P3-08).
 *
 * Owns **no** recurrence/fired/done/snooze business state — only platform alarm execution and
 * capability/error reporting. Rust owns the reminder plan; this port applies opaque schedule
 * requests.
 */
enum class AlarmTriggerMode {
    /** `AlarmManager.setAlarmClock` (the only exact path). */
    AlarmClock,

    /**
     * `setAndAllowWhileIdle` fallback when exact-alarm permission is missing or denied.
     * Despite waking during Doze this API is *inexact* — delivery may be deferred — so it is
     * never reported as an exact mode.
     */
    AllowWhileIdle,

    /** Last-resort non-exact `set` when other paths throw. */
    InexactFallback,
}

data class ExactAlarmCapability(
    val canScheduleExactAlarms: Boolean,
    val sdkInt: Int,
)

data class AlarmScheduleRequest(
    /** Durable occurrence identity issued by the Rust plan; the sole PendingIntent identity key. */
    val occurrenceId: String,
    val triggerAtUtcMillis: Long,
    val memoId: String,
    val reminderId: String,
)

data class AlarmScheduleResult(
    val mode: AlarmTriggerMode,
    val triggerAtUtcMillis: Long,
    val platformError: String? = null,
)

/**
 * Platform-neutral schedule/cancel surface. Production uses [AndroidAlarmSchedulePort];
 * tests inject fakes.
 */
interface AlarmSchedulePort {
    fun exactAlarmCapability(): ExactAlarmCapability

    /**
     * The mode [schedule] will attempt first under current capability, absent a runtime
     * `SecurityException` fallback. Derived solely from [exactAlarmCapability] so the durable
     * ledger can compare a recorded mode against the mode a fresh schedule would use and
     * re-schedule occurrences whose platform delivery mode has drifted (permission revoked
     * after scheduling, or a previous InexactFallback/AllowWhileIdle rescue).
     */
    fun plannedMode(): AlarmTriggerMode =
        exactAlarmCapability().let { capability ->
            if (capability.sdkInt >= Build.VERSION_CODES.S && !capability.canScheduleExactAlarms) {
                AlarmTriggerMode.AllowWhileIdle
            } else {
                AlarmTriggerMode.AlarmClock
            }
        }

    fun schedule(request: AlarmScheduleRequest): AlarmScheduleResult

    /**
     * Cancels the platform alarm whose PendingIntent carries [occurrenceId]. [memoId] and
     * [reminderId] are forwarded as receiver extras so the intent shape matches the scheduled one.
     */
    fun cancel(occurrenceId: String, memoId: String, reminderId: String)
}
