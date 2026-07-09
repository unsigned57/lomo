package com.lomo.data.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.lomo.data.resources.DataAndroidResources
import com.lomo.domain.model.ReminderMarker


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
            memoTitle: String,
            mainActivityIntent: Intent,
        ) {
            ensureChannel()
            val notificationId = ReminderRequestCodePolicy.notificationId(memoId, marker.raw)

            val openIntent =
                mainActivityIntent.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    action = ReminderIntents.ACTION_OPEN
                    putExtra(ReminderIntents.EXTRA_MEMO_ID, memoId)
                    putExtra(ReminderIntents.EXTRA_TOKEN_RAW, marker.raw)
                }
            val openPending =
                PendingIntent.getActivity(
                    context,
                    notificationId,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val snoozePending = actionBroadcast(ReminderIntents.ACTION_SNOOZE, memoId, marker.raw)
            val donePending = actionBroadcast(ReminderIntents.ACTION_DONE, memoId, marker.raw)

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

            notificationManager.notify(notificationId, builder.build())
        }

        fun cancel(notificationId: Int) {
            notificationManager.cancel(notificationId)
        }

        private fun actionBroadcast(
            action: String,
            memoId: String,
            tokenRaw: String,
        ): PendingIntent {
            val intent =
                Intent(context, ReminderActionReceiver::class.java).apply {
                    this.action = action
                    putExtra(ReminderIntents.EXTRA_MEMO_ID, memoId)
                    putExtra(ReminderIntents.EXTRA_TOKEN_RAW, tokenRaw)
                }
            return PendingIntent.getBroadcast(
                context,
                ReminderRequestCodePolicy.actionRequestCode(memoId, tokenRaw, action),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
