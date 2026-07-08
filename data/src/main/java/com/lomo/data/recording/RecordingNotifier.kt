package com.lomo.data.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.lomo.data.R


class RecordingNotifier(
    private val context: Context,
) {
        private val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun ensureChannel() {
            val existing = notificationManager.getNotificationChannel(RecordingIntents.NOTIFICATION_CHANNEL_ID)
            if (existing != null) return
            val channel =
                NotificationChannel(
                    RecordingIntents.NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.recording_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.recording_channel_description)
                }
            notificationManager.createNotificationChannel(channel)
        }

        fun buildOngoingNotification(durationMillis: Long): NotificationCompat.Builder {
            ensureChannel()
            val stopPending = actionBroadcast(RecordingIntents.ACTION_STOP)
            val cancelPending = actionBroadcast(RecordingIntents.ACTION_CANCEL)
            return NotificationCompat
                .Builder(context, RecordingIntents.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(context.getString(R.string.recording_notification_title))
                .setContentText(formatDuration(durationMillis))
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, context.getString(R.string.recording_notification_stop), stopPending)
                .addAction(0, context.getString(R.string.recording_notification_cancel), cancelPending)
        }

        fun showSavedConfirmation(
            memoId: String,
            openIntent: Intent,
        ) {
            ensureChannel()
            openIntent.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                action = RecordingIntents.ACTION_OPEN_SAVED_MEMO
                setPackage(context.packageName)
                putExtra(RecordingIntents.EXTRA_MEMO_ID, memoId)
            }
            val openPending =
                PendingIntent.getActivity(
                    context,
                    RecordingIntents.SAVED_NOTIFICATION_ID,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(context, RecordingIntents.NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_mic)
                    .setContentTitle(context.getString(R.string.recording_saved_notification_title))
                    .setContentText(context.getString(R.string.recording_saved_notification_text))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(openPending)
                    .addAction(0, context.getString(R.string.recording_saved_notification_action), openPending)
                    .build()
            notificationManager.notify(RecordingIntents.SAVED_NOTIFICATION_ID, notification)
        }

        fun cancelOngoing() {
            notificationManager.cancel(RecordingIntents.ONGOING_NOTIFICATION_ID)
        }

        private fun actionBroadcast(
            action: String,
            requestCodeBase: Int = RecordingIntents.ONGOING_NOTIFICATION_ID,
        ): PendingIntent {
            val intent =
                Intent(context, RecordingActionReceiver::class.java).apply {
                    this.action = action
                }
            return PendingIntent.getBroadcast(
                context,
                requestCodeBase + action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun formatDuration(millis: Long): String {
            val totalSeconds = millis / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%02d:%02d".format(minutes, seconds)
        }
    }
