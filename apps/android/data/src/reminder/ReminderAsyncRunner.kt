package com.lomo.data.reminder

import android.content.BroadcastReceiver
import com.lomo.data.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ReminderAsyncRunner(
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun launch(
        pendingResult: BroadcastReceiver.PendingResult,
        onResult: (ReminderReceiverWorkResult) -> Unit = {},
        block: suspend CoroutineScope.() -> Unit,
    ): Job =
        scope.launch {
            var result: ReminderReceiverWorkResult = ReminderReceiverWorkResult.Completed
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                result = ReminderReceiverWorkResult.Failed(error)
            } finally {
                pendingResult.finish()
                onResult(
                    if (coroutineContext[Job]?.isCancelled == true) {
                        ReminderReceiverWorkResult.Cancelled
                    } else {
                        result
                    },
                )
            }
        }
}
