/*
 * Behavior Contract:
 * - Unit under test: ReminderPermissionPolicy helpers.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: derive reminder permission and exact-alarm recovery behavior from the shared
 *   capability gate catalog.
 *
 * Scenarios:
 * - Given pre-Android-13 runtime behavior, when reminder notification permissions are requested, then
 *   no runtime permission is required.
 * - Given Android 13 or newer, when reminder notification permissions are requested, then the shared
 *   Notifications capability supplies POST_NOTIFICATIONS.
 * - Given notification permission denial, when reminder recovery is evaluated, then the shared
 *   Notifications capability supplies the app-settings fallback and retry behavior.
 * - Given exact alarms are unavailable, when reminder recovery is evaluated, then the shared ExactAlarm
 *   capability supplies the exact-alarm settings action.
 * - Given reminder recovery actions, when the executor receives notification or exact-alarm recovery,
 *   then it dispatches to the corresponding settings callback.
 *
 * Observable outcomes:
 * - required permission names, grant aggregation result, recovery action, and retry flags.
 *
 * TDD proof:
 * - RED: fails before implementation because reminder permission gate logic reads Manifest and Settings
 *   constants directly and exposes no catalog-backed reminder permission policy helpers.
 *
 * Excludes:
 * - Compose launcher wiring, Android system permission dialogs, Toast rendering, and localized copy.
 */

package com.lomo.app.feature.memo

import com.lomo.app.CapabilityRecoveryAction
import com.lomo.app.CapabilityRecoveryDecision
import com.lomo.app.CapabilitySettingsEntry
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

class ReminderPermissionPolicyTest : AppFunSpec() {
    init {
        test("given pre Android 13 when notification permissions are requested then no runtime grant is required") {
            requiredReminderNotificationPermissions(sdkInt = 32) shouldBe emptyList()
        }

        test("given Android 13 or newer when notification permissions are requested then catalog supplies POST_NOTIFICATIONS") {
            requiredReminderNotificationPermissions(sdkInt = 33) shouldBe
                listOf(REMINDER_POST_NOTIFICATIONS_PERMISSION)
        }

        test("given notification permission results when evaluated then the catalog aggregation decides readiness") {
            val required = listOf(REMINDER_POST_NOTIFICATIONS_PERMISSION)

            isReminderNotificationPermissionRequestGranted(
                requiredPermissions = required,
                permissionResults = mapOf(REMINDER_POST_NOTIFICATIONS_PERMISSION to true),
                hasCurrentPermissions = false,
            ) shouldBe true

            isReminderNotificationPermissionRequestGranted(
                requiredPermissions = required,
                permissionResults = mapOf(REMINDER_POST_NOTIFICATIONS_PERMISSION to false),
                hasCurrentPermissions = false,
            ) shouldBe false
        }

        test("given notification denial when recovery is evaluated then app settings fallback and retry are catalog backed") {
            reminderNotificationRecoveryAction(CapabilityRecoveryDecision.Denied) shouldBe
                CapabilityRecoveryAction.OpenAppSettings
            reminderNotificationRecoveryAction(CapabilityRecoveryDecision.PermanentlyDenied) shouldBe
                CapabilityRecoveryAction.OpenAppSettings
            canRetryReminderNotificationRecovery() shouldBe true
        }

        test("given exact alarm capability when recovery is evaluated then exact alarm settings action is catalog backed") {
            reminderExactAlarmRecoveryAction() shouldBe
                CapabilityRecoveryAction.OpenSettings(CapabilitySettingsEntry.ExactAlarmSettings)
            canRetryReminderExactAlarmRecovery() shouldBe true
        }

        test("given reminder recovery actions when executor runs then it dispatches to matching settings callback") {
            var appSettingsOpenCount = 0
            var exactAlarmSettingsOpenCount = 0
            val executor =
                ReminderCapabilityRecoveryExecutor(
                    onOpenAppSettings = { appSettingsOpenCount += 1 },
                    onOpenExactAlarmSettings = { exactAlarmSettingsOpenCount += 1 },
                )

            executor.execute(reminderNotificationRecoveryAction(CapabilityRecoveryDecision.Denied)) shouldBe true
            executor.execute(reminderExactAlarmRecoveryAction()) shouldBe true
            executor.execute(CapabilityRecoveryAction.RequestRuntimePermissions) shouldBe false

            appSettingsOpenCount shouldBe 1
            exactAlarmSettingsOpenCount shouldBe 1
        }
    }
}
