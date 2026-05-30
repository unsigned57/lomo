package com.lomo.data.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.lomo.data.R
import com.lomo.domain.model.ReminderMarker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun ensureChannel() {
            val existing = notificationManager.getNotificationChannel(ReminderIntents.NOTIFICATION_CHANNEL_ID)
            if (existing != null) return
            val channel =
                NotificationChannel(
                    ReminderIntents.NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.reminder_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.reminder_channel_description)
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
                memoTitle.ifBlank { context.getString(R.string.reminder_notification_default_body) }
            val builder =
                NotificationCompat
                    .Builder(context, ReminderIntents.NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_lomo_reminder_status)
                    .setContentTitle(context.getString(R.string.reminder_notification_title))
                    .setContentText(contentBody)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(memoTitle))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(openPending)
                    .addAction(0, context.getString(R.string.reminder_action_open), openPending)
                    .addAction(0, context.getString(R.string.reminder_action_snooze), snoozePending)
                    .addAction(0, context.getString(R.string.reminder_action_done), donePending)

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
