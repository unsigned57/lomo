package com.lomo.app.startup

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.lomo.app.R
import com.lomo.app.TrustedLaunchIntents


class AndroidDynamicShortcutPublisher(
    private val context: Context,
    private val trustedLaunchIntents: TrustedLaunchIntents,
) : DynamicShortcutPublisher {
        override fun publishExternalEntryShortcuts() {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return
            val managedShortcutIds = setOf(NEW_MEMO_SHORTCUT_ID, START_RECORDING_SHORTCUT_ID)
            shortcutManager.dynamicShortcuts =
                shortcutManager.dynamicShortcuts
                    .filterNot { shortcut -> shortcut.id in managedShortcutIds } +
                    listOf(newMemoShortcut(), recordingShortcut())
        }

        private fun newMemoShortcut(): ShortcutInfo =
            ShortcutInfo
                .Builder(context, NEW_MEMO_SHORTCUT_ID)
                .setShortLabel(context.getString(R.string.shortcut_new_memo_short_label))
                .setLongLabel(context.getString(R.string.shortcut_new_memo_long_label))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_new_memo))
                .setIntent(trustedLaunchIntents.trustedShortcutCreateMemoIntent())
                .build()

        private fun recordingShortcut(): ShortcutInfo =
            ShortcutInfo
                .Builder(context, START_RECORDING_SHORTCUT_ID)
                .setShortLabel(context.getString(R.string.shortcut_start_recording_short_label))
                .setLongLabel(context.getString(R.string.shortcut_start_recording_long_label))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_recording))
                .setIntent(trustedLaunchIntents.trustedShortcutStartRecordingIntent())
                .build()

        private companion object {
            const val NEW_MEMO_SHORTCUT_ID = "new_memo"
            const val START_RECORDING_SHORTCUT_ID = "start_recording"
        }
    }
