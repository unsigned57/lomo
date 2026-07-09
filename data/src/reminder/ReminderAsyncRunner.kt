package com.lomo.data.reminder
import android.content.BroadcastReceiver
import com.lomo.data.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
class ReminderAsyncRunner
constructor(
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        fun launch(
            pendingResult: BroadcastReceiver.PendingResult,
            block: suspend CoroutineScope.() -> Unit,
        ): Job =
            scope.launch {
                try {
                    block()
                } finally {
                    pendingResult.finish()
                }
            }
    }
