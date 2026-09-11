package com.lomo.data.testing

import androidx.annotation.StringRes
import com.lomo.data.resources.DataAndroidResources

class FakeDataAndroidResources : DataAndroidResources {
    override val appUpdateMissingApk: Int = 1
    override val appUpdateEnableInstalls: Int = 2
    override val appUpdateHttpFailed: Int = 3
    override val appUpdateEmptyFile: Int = 4
    override val appUpdateNoInstaller: Int = 5
    override val appUpdateInvalidApk: Int = 6
    override val appUpdateMissingApkMetadata: Int = 7
    override val appUpdateApkMetadataMismatch: Int = 8
    override val appUpdateInstallNotCompleted: Int = 9

    override val reminderChannelName: Int = 10
    override val reminderChannelDescription: Int = 11
    override val reminderNotificationTitle: Int = 12
    override val reminderNotificationDefaultBody: Int = 13
    override val reminderActionOpen: Int = 14
    override val reminderActionSnooze: Int = 15
    override val reminderActionDone: Int = 16
    override val reminderSmallIcon: Int = 17

    override val recordingChannelName: Int = 18
    override val recordingChannelDescription: Int = 19
    override val recordingNotificationTitle: Int = 20
    override val recordingNotificationStop: Int = 21
    override val recordingNotificationCancel: Int = 22
    override val recordingSavedNotificationTitle: Int = 23
    override val recordingSavedNotificationText: Int = 24
    override val recordingSavedNotificationAction: Int = 25
    override val recordingSmallIcon: Int = 26

    private val strings =
        mapOf(
            appUpdateMissingApk to "Missing APK",
            appUpdateEnableInstalls to "Enable installs",
            appUpdateHttpFailed to "Update download failed: HTTP %1\$d",
            appUpdateEmptyFile to "Empty APK",
            appUpdateNoInstaller to "No installer",
            appUpdateInvalidApk to "Invalid APK",
            appUpdateMissingApkMetadata to "Missing APK metadata",
            appUpdateApkMetadataMismatch to "APK metadata mismatch",
            appUpdateInstallNotCompleted to "Install not completed",
            reminderChannelName to "Reminders",
            reminderChannelDescription to "Memo reminder alerts",
            reminderNotificationTitle to "Memo reminder",
            reminderNotificationDefaultBody to "It's time.",
            reminderActionOpen to "Open",
            reminderActionSnooze to "Snooze",
            reminderActionDone to "Done",
            recordingChannelName to "Recording",
            recordingChannelDescription to "Used while Lomo is recording audio",
            recordingNotificationTitle to "Recording",
            recordingNotificationStop to "Stop",
            recordingNotificationCancel to "Cancel",
            recordingSavedNotificationTitle to "Recording saved",
            recordingSavedNotificationText to "Tap to edit",
            recordingSavedNotificationAction to "Edit",
        )

    override fun getString(@StringRes id: Int): String =
        requireNotNull(strings[id]) {
            "Missing fake string for resource id $id"
        }

    override fun getString(
        @StringRes id: Int,
        vararg formatArgs: Any,
    ): String = getString(id).format(*formatArgs)
}
