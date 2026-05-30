package com.lomo.app.feature.memo

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.lomo.app.CapabilityGateId
import com.lomo.app.CapabilityGatePolicies
import com.lomo.app.CapabilityPermissionNames
import com.lomo.app.CapabilityRecoveryAction
import com.lomo.app.CapabilityRecoveryDecision
import com.lomo.app.CapabilitySettingsEntry
import com.lomo.app.CapabilityRuntimePermissionPlan

internal const val REMINDER_POST_NOTIFICATIONS_PERMISSION = CapabilityPermissionNames.PostNotifications

internal fun requiredReminderNotificationPermissions(
    sdkInt: Int = Build.VERSION.SDK_INT,
): List<String> =
    reminderNotificationPermissionPlan()
        .requiredPermissions(
            sdkInt = sdkInt,
            isPermissionRecognized = { true },
        )

internal fun hasReminderNotificationPermission(
    context: Context,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Boolean =
    requiredReminderNotificationPermissions(sdkInt).all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

internal fun isReminderNotificationPermissionRequestGranted(
    requiredPermissions: List<String>,
    permissionResults: Map<String, Boolean>,
    hasCurrentPermissions: Boolean,
): Boolean =
    reminderNotificationPermissionPlan()
        .isGrantSatisfied(
            requiredPermissions = requiredPermissions,
            permissionResults = permissionResults,
            hasCurrentPermissions = hasCurrentPermissions,
        )

internal fun reminderNotificationRecoveryAction(
    decision: CapabilityRecoveryDecision,
): CapabilityRecoveryAction? =
    CapabilityGatePolicies.requirePolicy(CapabilityGateId.Notifications)
        .recoveryPlan
        .fallbackAction(decision)

internal fun canRetryReminderNotificationRecovery(): Boolean =
    CapabilityGatePolicies.requirePolicy(CapabilityGateId.Notifications)
        .recoveryPlan
        .canRetryAfterRecovery

internal fun reminderExactAlarmRecoveryAction(): CapabilityRecoveryAction =
    CapabilityGatePolicies.requirePolicy(CapabilityGateId.ExactAlarm)
        .recoveryPlan
        .primaryRequestAction

internal fun canRetryReminderExactAlarmRecovery(): Boolean =
    CapabilityGatePolicies.requirePolicy(CapabilityGateId.ExactAlarm)
        .recoveryPlan
        .canRetryAfterRecovery

internal class ReminderCapabilityRecoveryExecutor(
    private val onOpenAppSettings: () -> Unit,
    private val onOpenExactAlarmSettings: () -> Unit,
) {
    fun execute(action: CapabilityRecoveryAction?): Boolean =
        when (action) {
            CapabilityRecoveryAction.OpenAppSettings -> {
                onOpenAppSettings()
                true
            }
            CapabilityRecoveryAction.OpenSettings(CapabilitySettingsEntry.ExactAlarmSettings) -> {
                onOpenExactAlarmSettings()
                true
            }
            CapabilityRecoveryAction.RequestRuntimePermissions,
            is CapabilityRecoveryAction.OpenSettings,
            CapabilityRecoveryAction.SelectSafTree,
            CapabilityRecoveryAction.SelectSafDocument,
            null,
            -> false
        }
}

private fun reminderNotificationPermissionPlan(): CapabilityRuntimePermissionPlan =
    requireNotNull(
        CapabilityGatePolicies.requirePolicy(CapabilityGateId.Notifications)
            .recoveryPlan
            .runtimePermissionPlan,
    ) {
        "Notifications capability must define a runtime permission plan."
    }
