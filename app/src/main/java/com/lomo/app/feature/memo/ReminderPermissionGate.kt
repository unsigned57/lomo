package com.lomo.app.feature.memo

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.lomo.app.R

@Composable
internal fun rememberReminderInsertGate(onReady: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val notificationDeniedMessage = stringResource(R.string.reminder_permission_denied_notification)
    val exactAlarmDeniedMessage = stringResource(R.string.reminder_permission_denied_exact_alarm)
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                openReminderDialogIfReady(
                    context = context,
                    deniedMessage = exactAlarmDeniedMessage,
                    onReady = onReady,
                )
            } else {
                Toast.makeText(context, notificationDeniedMessage, Toast.LENGTH_LONG).show()
            }
        }
    return remember(notificationPermissionLauncher, context, exactAlarmDeniedMessage) {
        {
            ensureNotificationPermissionThen(
                context = context,
                requestLauncher = notificationPermissionLauncher,
                deniedMessage = exactAlarmDeniedMessage,
                onReady = onReady,
            )
        }
    }
}

internal fun ensureNotificationPermissionThen(
    context: Context,
    requestLauncher: ActivityResultLauncher<String>,
    deniedMessage: String,
    onReady: () -> Unit,
) {
    val needsRuntimeNotificationGrant =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    if (needsRuntimeNotificationGrant) {
        requestLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        openReminderDialogIfReady(context = context, deniedMessage = deniedMessage, onReady = onReady)
    }
}

internal fun openReminderDialogIfReady(
    context: Context,
    deniedMessage: String,
    onReady: () -> Unit,
) {
    if (!hasExactAlarmCapability(context)) {
        openExactAlarmSettings(context)
        Toast.makeText(context, deniedMessage, Toast.LENGTH_LONG).show()
        return
    }
    onReady()
}

private fun hasExactAlarmCapability(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return true
    return alarmManager.canScheduleExactAlarms()
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = ("package:" + context.packageName).toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    runCatching { context.startActivity(intent) }
}
