package com.lomo.data.resources

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

interface DataAndroidResources {
    @get:StringRes val appUpdateMissingApk: Int
    @get:StringRes val appUpdateEnableInstalls: Int
    @get:StringRes val appUpdateHttpFailed: Int
    @get:StringRes val appUpdateEmptyFile: Int
    @get:StringRes val appUpdateNoInstaller: Int
    @get:StringRes val appUpdateInvalidApk: Int
    @get:StringRes val appUpdateMissingApkMetadata: Int
    @get:StringRes val appUpdateApkMetadataMismatch: Int
    @get:StringRes val appUpdateInstallNotCompleted: Int

    @get:StringRes val reminderChannelName: Int
    @get:StringRes val reminderChannelDescription: Int
    @get:StringRes val reminderNotificationTitle: Int
    @get:StringRes val reminderNotificationDefaultBody: Int
    @get:StringRes val reminderActionOpen: Int
    @get:StringRes val reminderActionSnooze: Int
    @get:StringRes val reminderActionDone: Int
    @get:DrawableRes val reminderSmallIcon: Int

    @get:StringRes val recordingChannelName: Int
    @get:StringRes val recordingChannelDescription: Int
    @get:StringRes val recordingNotificationTitle: Int
    @get:StringRes val recordingNotificationStop: Int
    @get:StringRes val recordingNotificationCancel: Int
    @get:StringRes val recordingSavedNotificationTitle: Int
    @get:StringRes val recordingSavedNotificationText: Int
    @get:StringRes val recordingSavedNotificationAction: Int
    @get:DrawableRes val recordingSmallIcon: Int

    fun getString(@StringRes id: Int): String

    fun getString(
        @StringRes id: Int,
        vararg formatArgs: Any,
    ): String
}

internal class ApkDataAndroidResources(
    private val context: Context,
) : DataAndroidResources {
    @StringRes override val appUpdateMissingApk: Int = stringResource("app_update_missing_apk")
    @StringRes override val appUpdateEnableInstalls: Int = stringResource("app_update_enable_installs")
    @StringRes override val appUpdateHttpFailed: Int = stringResource("app_update_http_failed")
    @StringRes override val appUpdateEmptyFile: Int = stringResource("app_update_empty_file")
    @StringRes override val appUpdateNoInstaller: Int = stringResource("app_update_no_installer")
    @StringRes override val appUpdateInvalidApk: Int = stringResource("app_update_invalid_apk")
    @StringRes override val appUpdateMissingApkMetadata: Int = stringResource("app_update_missing_apk_metadata")
    @StringRes override val appUpdateApkMetadataMismatch: Int = stringResource("app_update_apk_metadata_mismatch")
    @StringRes override val appUpdateInstallNotCompleted: Int = stringResource("app_update_install_not_completed")

    @StringRes override val reminderChannelName: Int = stringResource("reminder_channel_name")
    @StringRes override val reminderChannelDescription: Int = stringResource("reminder_channel_description")
    @StringRes override val reminderNotificationTitle: Int = stringResource("reminder_notification_title")
    @StringRes override val reminderNotificationDefaultBody: Int = stringResource("reminder_notification_default_body")
    @StringRes override val reminderActionOpen: Int = stringResource("reminder_action_open")
    @StringRes override val reminderActionSnooze: Int = stringResource("reminder_action_snooze")
    @StringRes override val reminderActionDone: Int = stringResource("reminder_action_done")
    @DrawableRes override val reminderSmallIcon: Int = drawableResource("ic_lomo_reminder_status")

    @StringRes override val recordingChannelName: Int = stringResource("recording_channel_name")
    @StringRes override val recordingChannelDescription: Int = stringResource("recording_channel_description")
    @StringRes override val recordingNotificationTitle: Int = stringResource("recording_notification_title")
    @StringRes override val recordingNotificationStop: Int = stringResource("recording_notification_stop")
    @StringRes override val recordingNotificationCancel: Int = stringResource("recording_notification_cancel")
    @StringRes override val recordingSavedNotificationTitle: Int = stringResource("recording_saved_notification_title")
    @StringRes override val recordingSavedNotificationText: Int = stringResource("recording_saved_notification_text")
    @StringRes override val recordingSavedNotificationAction: Int = stringResource("recording_saved_notification_action")
    @DrawableRes override val recordingSmallIcon: Int = drawableResource("ic_mic")

    override fun getString(@StringRes id: Int): String = context.getString(id)

    override fun getString(
        @StringRes id: Int,
        vararg formatArgs: Any,
    ): String = context.getString(id, *formatArgs)

    @StringRes
    private fun stringResource(name: String): Int = resource(name = name, type = "string")

    @DrawableRes
    private fun drawableResource(name: String): Int = resource(name = name, type = "drawable")

    private fun resource(
        name: String,
        type: String,
    ): Int {
        val id = context.resources.getIdentifier(name, type, context.packageName)
        require(id != 0) {
            "Missing required $type resource ${context.packageName}:$name"
        }
        return id
    }
}
