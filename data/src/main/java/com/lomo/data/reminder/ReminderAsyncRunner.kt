package com.lomo.data.reminder

import android.content.BroadcastReceiver
import com.lomo.data.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Singleton
class ReminderAsyncRunner
    @Inject
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
