package com.lomo.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import timber.log.Timber

/**
 * Platform side-effects for [AndroidAlarmSchedulePort].
 *
 * Production uses [AndroidAlarmManagerGateway]; host tests inject recording fakes so schedule
 * mode selection and fallback paths are host-provable without a real AlarmManager.
 */
interface AlarmPlatformGateway {
    fun canScheduleExactAlarms(): Boolean

    /**
     * Builds the broadcast PendingIntent for one scheduled occurrence.
     *
     * PendingIntent identity is (component, action, data, requestCode) — extras never match — so
     * [occurrenceId] is carried in the intent data URI and in the deterministic request code.
     * Two occurrences of the same reminder therefore never alias one PendingIntent.
     */
    fun pendingIntent(
        occurrenceId: String,
        memoId: String,
        reminderId: String,
    ): PendingIntent

    fun setAlarmClock(
        triggerAtUtcMillis: Long,
        operation: PendingIntent,
    )

    fun setAndAllowWhileIdle(
        triggerAtUtcMillis: Long,
        operation: PendingIntent,
    )

    fun setInexact(
        triggerAtUtcMillis: Long,
        operation: PendingIntent,
    )

    fun cancel(operation: PendingIntent)
}

/** Production [AlarmPlatformGateway] over real [AlarmManager] + broadcast PendingIntent. */
class AndroidAlarmManagerGateway(
    private val context: Context,
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager,
) : AlarmPlatformGateway {
    override fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    override fun pendingIntent(
        occurrenceId: String,
        memoId: String,
        reminderId: String,
    ): PendingIntent {
        val intent =
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ReminderIntents.ACTION_FIRE
                data = Uri.parse("${ReminderIntents.ALARM_DATA_URI_PREFIX}${Uri.encode(occurrenceId)}")
                putExtra(ReminderIntents.EXTRA_MEMO_ID, memoId)
                putExtra(ReminderIntents.EXTRA_REMINDER_ID, reminderId)
                putExtra(ReminderIntents.EXTRA_OCCURRENCE_ID, occurrenceId)
            }
        return PendingIntent.getBroadcast(
            context,
            ReminderRequestCodePolicy.alarmRequestCode(occurrenceId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun setAlarmClock(
        triggerAtUtcMillis: Long,
        operation: PendingIntent,
    ) {
        val info = AlarmManager.AlarmClockInfo(triggerAtUtcMillis, null)
        alarmManager.setAlarmClock(info, operation)
    }

    override fun setAndAllowWhileIdle(
        triggerAtUtcMillis: Long,
        operation: PendingIntent,
    ) {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtUtcMillis,
            operation,
        )
    }

    override fun setInexact(
        triggerAtUtcMillis: Long,
        operation: PendingIntent,
    ) {
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtUtcMillis, operation)
    }

    override fun cancel(operation: PendingIntent) {
        alarmManager.cancel(operation)
    }
}

/**
 * AlarmManager-backed [AlarmSchedulePort]. Reports exact-alarm capability, actual trigger mode,
 * and platform errors. Does not interpret reminder tokens or recurrence.
 *
 * Mode selection and fallback policy are host-testable via [AlarmPlatformGateway].
 */
class AndroidAlarmSchedulePort(
    private val gateway: AlarmPlatformGateway,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : AlarmSchedulePort {
    constructor(context: Context) : this(gateway = AndroidAlarmManagerGateway(context))

    override fun exactAlarmCapability(): ExactAlarmCapability =
        ExactAlarmCapability(
            canScheduleExactAlarms = gateway.canScheduleExactAlarms(),
            sdkInt = sdkInt,
        )

    override fun schedule(request: AlarmScheduleRequest): AlarmScheduleResult {
        val pendingIntent =
            gateway.pendingIntent(request.occurrenceId, request.memoId, request.reminderId)
        return try {
            // plannedMode() is the single mode-selection law; the fallback chain below only
            // rescues a runtime SecurityException, never re-decides the mode.
            when (plannedMode()) {
                AlarmTriggerMode.AllowWhileIdle -> {
                    gateway.setAndAllowWhileIdle(request.triggerAtUtcMillis, pendingIntent)
                    AlarmScheduleResult(
                        mode = AlarmTriggerMode.AllowWhileIdle,
                        triggerAtUtcMillis = request.triggerAtUtcMillis,
                    )
                }
                else -> {
                    gateway.setAlarmClock(request.triggerAtUtcMillis, pendingIntent)
                    AlarmScheduleResult(
                        mode = AlarmTriggerMode.AlarmClock,
                        triggerAtUtcMillis = request.triggerAtUtcMillis,
                    )
                }
            }
        } catch (security: SecurityException) {
            Timber.tag("AlarmSchedulePort").w(security, "exact alarm denied, fallback used")
            try {
                gateway.setAndAllowWhileIdle(request.triggerAtUtcMillis, pendingIntent)
                AlarmScheduleResult(
                    mode = AlarmTriggerMode.AllowWhileIdle,
                    triggerAtUtcMillis = request.triggerAtUtcMillis,
                    platformError = security.message,
                )
            } catch (denied: SecurityException) {
                gateway.setInexact(request.triggerAtUtcMillis, pendingIntent)
                AlarmScheduleResult(
                    mode = AlarmTriggerMode.InexactFallback,
                    triggerAtUtcMillis = request.triggerAtUtcMillis,
                    platformError = denied.message ?: security.message,
                )
            } catch (invalid: IllegalArgumentException) {
                gateway.setInexact(request.triggerAtUtcMillis, pendingIntent)
                AlarmScheduleResult(
                    mode = AlarmTriggerMode.InexactFallback,
                    triggerAtUtcMillis = request.triggerAtUtcMillis,
                    platformError = invalid.message ?: security.message,
                )
            }
        }
    }

    override fun cancel(
        occurrenceId: String,
        memoId: String,
        reminderId: String,
    ) {
        gateway.cancel(gateway.pendingIntent(occurrenceId, memoId, reminderId))
    }
}
