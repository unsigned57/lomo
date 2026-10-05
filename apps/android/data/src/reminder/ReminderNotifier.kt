package com.lomo.data.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.lomo.data.resources.DataAndroidResources
import com.lomo.domain.model.ReminderMarker
import timber.log.Timber

/**
 * Typed outcome of posting a reminder notification.
 *
 * `NotificationManager.notify` cannot report whether the notification actually reached the
 * shade — when POST_NOTIFICATIONS is revoked or the channel is blocked it silently no-ops.
 * The fire path must distinguish real delivery from a silent drop so it does not consume the
 * occurrence (recordFired) for a notification the user never saw.
 */
sealed interface ReminderDelivery {
    /** The notification was posted with permission granted and channel unblocked. */
    data object Delivered : ReminderDelivery

    /** The notification could not reach the shade; the occurrence remains unfired. */
    data class NotDelivered(
        val cause: Cause,
    ) : ReminderDelivery {
        enum class Cause {
            /** App-level notification permission/setting is off. */
            NotificationsDisabled,

            /** The reminder channel exists but the user blocked it. */
            ChannelBlocked,

            /** `notify()` itself threw (platform rejection). */
            NotifyRejected,
        }
    }
}

class ReminderNotifier(
    private val context: Context,
    private val resources: DataAndroidResources,
) {
        private val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun ensureChannel() {
            val existing = notificationManager.getNotificationChannel(ReminderIntents.NOTIFICATION_CHANNEL_ID)
            if (existing != null) return
            val channel =
                NotificationChannel(
                    ReminderIntents.NOTIFICATION_CHANNEL_ID,
                    resources.getString(resources.reminderChannelName),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = resources.getString(resources.reminderChannelDescription)
                }
            notificationManager.createNotificationChannel(channel)
        }

        fun showFor(
            memoId: String,
            marker: ReminderMarker,
            occurrenceId: String,
            memoTitle: String,
            mainActivityIntent: Intent,
        ): ReminderDelivery {
            ensureChannel()
            if (!notificationManager.areNotificationsEnabled()) {
                return ReminderDelivery.NotDelivered(ReminderDelivery.NotDelivered.Cause.NotificationsDisabled)
            }
            if (
                notificationManager
                    .getNotificationChannel(ReminderIntents.NOTIFICATION_CHANNEL_ID)
                    ?.importance == NotificationManager.IMPORTANCE_NONE
            ) {
                return ReminderDelivery.NotDelivered(ReminderDelivery.NotDelivered.Cause.ChannelBlocked)
            }
            val reminderId = marker.reference.opaqueId
            val notificationId = ReminderRequestCodePolicy.notificationId(memoId, reminderId)
            val tag = ReminderRequestCodePolicy.occurrenceTag(occurrenceId)

            val openIntent =
                mainActivityIntent.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    action = ReminderIntents.ACTION_OPEN
                    putExtra(ReminderIntents.EXTRA_MEMO_ID, memoId)
                    putExtra(ReminderIntents.EXTRA_REMINDER_ID, reminderId)
                }
            val openPending =
                PendingIntent.getActivity(
                    context,
                    notificationId,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val snoozePending = actionBroadcast(ReminderIntents.ACTION_SNOOZE, memoId, reminderId, occurrenceId)
            val donePending = actionBroadcast(ReminderIntents.ACTION_DONE, memoId, reminderId, occurrenceId)

            val contentBody =
                memoTitle.ifBlank { resources.getString(resources.reminderNotificationDefaultBody) }
            val builder =
                NotificationCompat
                    .Builder(context, ReminderIntents.NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(resources.reminderSmallIcon)
                    .setContentTitle(resources.getString(resources.reminderNotificationTitle))
                    .setContentText(contentBody)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(memoTitle))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(openPending)
                    .addAction(0, resources.getString(resources.reminderActionOpen), openPending)
                    .addAction(0, resources.getString(resources.reminderActionSnooze), snoozePending)
                    .addAction(0, resources.getString(resources.reminderActionDone), donePending)

            return try {
                notificationManager.notify(tag, notificationId, builder.build())
                ReminderDelivery.Delivered
            } catch (security: SecurityException) {
                Timber.tag("ReminderNotifier").w(security, "reminder notification rejected by platform")
                ReminderDelivery.NotDelivered(ReminderDelivery.NotDelivered.Cause.NotifyRejected)
            }
        }

        fun cancel(
            notificationId: Int,
            occurrenceId: String?,
        ) {
            if (occurrenceId == null) {
                notificationManager.cancel(notificationId)
            } else {
                notificationManager.cancel(ReminderRequestCodePolicy.occurrenceTag(occurrenceId), notificationId)
            }
        }

        private fun actionBroadcast(
            action: String,
            memoId: String,
            reminderId: String,
            occurrenceId: String,
        ): PendingIntent {
            val intent =
                Intent(context, ReminderActionReceiver::class.java).apply {
                    this.action = action
                    data =
                        android.net.Uri.parse(
                            "${ReminderIntents.ALARM_DATA_URI_PREFIX}action/" +
                                "${android.net.Uri.encode(occurrenceId)}/$action",
                        )
                    putExtra(ReminderIntents.EXTRA_MEMO_ID, memoId)
                    putExtra(ReminderIntents.EXTRA_REMINDER_ID, reminderId)
                    putExtra(ReminderIntents.EXTRA_OCCURRENCE_ID, occurrenceId)
                }
            return PendingIntent.getBroadcast(
                context,
                ReminderRequestCodePolicy.actionRequestCode(memoId, reminderId, "$action:$occurrenceId"),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
