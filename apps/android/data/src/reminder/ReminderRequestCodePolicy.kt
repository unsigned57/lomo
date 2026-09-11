package com.lomo.data.reminder

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object ReminderRequestCodePolicy {
    fun alarmRequestCode(
        memoId: String,
        reminderId: String,
    ): Int = stableInt(ALARM_NAMESPACE, memoId, reminderId)

    fun notificationId(
        memoId: String,
        reminderId: String,
    ): Int = stableInt(NOTIFICATION_NAMESPACE, memoId, reminderId)

    fun actionRequestCode(
        memoId: String,
        reminderId: String,
        action: String,
    ): Int = stableInt(ACTION_NAMESPACE, memoId, reminderId, action)

    private fun stableInt(
        namespace: String,
        vararg parts: String,
    ): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(namespace.toByteArray(StandardCharsets.UTF_8))
        parts.forEach { part ->
            digest.update(PART_SEPARATOR)
            digest.update(part.toByteArray(StandardCharsets.UTF_8))
        }
        return ByteBuffer.wrap(digest.digest()).int and Int.MAX_VALUE
    }

    private const val ALARM_NAMESPACE = "lomo.reminder.alarm.v1"
    private const val NOTIFICATION_NAMESPACE = "lomo.reminder.notification.v1"
    private const val ACTION_NAMESPACE = "lomo.reminder.action.v1"
    private const val PART_SEPARATOR: Byte = 0
}
