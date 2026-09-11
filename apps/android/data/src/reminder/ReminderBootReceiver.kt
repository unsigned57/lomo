package com.lomo.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lomo.domain.repository.ReminderCoordinator
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ReminderBootReceiver : BroadcastReceiver(), KoinComponent {
    private val asyncRunner: ReminderAsyncRunner by inject()
    private val reminderCoordinator: ReminderCoordinator by inject()

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
