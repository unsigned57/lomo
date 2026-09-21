package com.lomo.data.worker

import com.lomo.domain.repository.AuthorizedWorkResume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

class DeferredLockAuthorizedWorkResume(
    private val store: DeferredLockWorkStore,
    private val scheduler: RustSyncScheduler,
    private val scope: CoroutineScope,
) : AuthorizedWorkResume {
    override fun onSessionAllowsBackgroundWork() {
        scope.launch {
            val pending =
                try {
                    store.take()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.e(error, "Deferred lock work could not be resumed")
                    return@launch
                } ?: return@launch
            scheduler.enqueueSaved(pending)
        }
    }
}
