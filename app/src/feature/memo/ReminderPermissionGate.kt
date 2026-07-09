package com.lomo.app.feature.memo

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
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
import com.lomo.app.CapabilityRecoveryDecision
import com.lomo.app.R

@Composable
internal fun rememberReminderInsertGate(onReady: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val notificationDeniedMessage = stringResource(R.string.reminder_permission_denied_notification)
    val exactAlarmDeniedMessage = stringResource(R.string.reminder_permission_denied_exact_alarm)
    val recoveryExecutor = rememberReminderRecoveryExecutor(context)
    val requiredNotificationPermissions = remember { requiredReminderNotificationPermissions() }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val permissionResults =
                requiredNotificationPermissions
                    .singleOrNull()
                    ?.let { permission -> mapOf(permission to granted) }
                    .orEmpty()
            if (
                isReminderNotificationPermissionRequestGranted(
                    requiredPermissions = requiredNotificationPermissions,
                    permissionResults = permissionResults,
                    hasCurrentPermissions = hasReminderNotificationPermission(context),
                )
            ) {
                openReminderDialogIfReady(
                    context = context,
                    deniedMessage = exactAlarmDeniedMessage,
                    recoveryExecutor = recoveryExecutor,
                    onReady = onReady,
                )
            } else {
                recoveryExecutor.execute(reminderNotificationRecoveryAction(CapabilityRecoveryDecision.Denied))
                Toast.makeText(context, notificationDeniedMessage, Toast.LENGTH_LONG).show()
            }
        }
    return remember(notificationPermissionLauncher, context, exactAlarmDeniedMessage, recoveryExecutor) {
        {
            ensureNotificationPermissionThen(
                context = context,
                requestLauncher = notificationPermissionLauncher,
                deniedMessage = exactAlarmDeniedMessage,
                recoveryExecutor = recoveryExecutor,
                onReady = onReady,
            )
        }
    }
}

internal fun ensureNotificationPermissionThen(
    context: Context,
    requestLauncher: ActivityResultLauncher<String>,
    deniedMessage: String,
    recoveryExecutor: ReminderCapabilityRecoveryExecutor,
    onReady: () -> Unit,
) {
    val requiredPermissions = requiredReminderNotificationPermissions()
    if (requiredPermissions.isNotEmpty() && !hasReminderNotificationPermission(context)) {
        requiredPermissions.singleOrNull()?.let(requestLauncher::launch)
    } else {
        openReminderDialogIfReady(
            context = context,
            deniedMessage = deniedMessage,
            recoveryExecutor = recoveryExecutor,
            onReady = onReady,
        )
    }
}

internal fun openReminderDialogIfReady(
    context: Context,
    deniedMessage: String,
    recoveryExecutor: ReminderCapabilityRecoveryExecutor,
    onReady: () -> Unit,
) {
    if (!hasExactAlarmCapability(context)) {
        recoveryExecutor.execute(reminderExactAlarmRecoveryAction())
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

@Composable
private fun rememberReminderRecoveryExecutor(context: Context): ReminderCapabilityRecoveryExecutor =
    remember(context) {
        ReminderCapabilityRecoveryExecutor(
            onOpenAppSettings = { openReminderAppSettings(context) },
            onOpenExactAlarmSettings = { openExactAlarmSettings(context) },
        )
    }

private fun openReminderAppSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = ("package:" + context.packageName).toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    runCatching { context.startActivity(intent) }
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
