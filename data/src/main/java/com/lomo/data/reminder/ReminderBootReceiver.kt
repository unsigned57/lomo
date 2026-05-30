package com.lomo.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.domain.repository.ReminderCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ReminderBootReceiver : BroadcastReceiver() {
    @Inject lateinit var asyncRunner: ReminderAsyncRunner

    @Inject lateinit var reminderCoordinator: ReminderCoordinator

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS) return
        val pendingResult = goAsync()

        asyncRunner.launch(pendingResult) {
            reminderCoordinator.rebuildAll()
        }
    }

    private companion object {
        val BOOT_ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
            )
    }
}
