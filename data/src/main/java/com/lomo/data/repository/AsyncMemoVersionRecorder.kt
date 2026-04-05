package com.lomo.data.repository

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface MemoVersionRecorder {
    suspend fun enqueueLocalRevision(
        memo: Memo,
        lifecycleState: MemoRevisionLifecycleState,
        origin: MemoRevisionOrigin,
    )
}

@Singleton
class AsyncMemoVersionRecorder
    private constructor(
        private val memoVersionJournal: MemoVersionJournal,
        private val scope: CoroutineScope,
    ) : MemoVersionRecorder {
        @Inject
        constructor(
            memoVersionJournal: MemoVersionJournal,
        ) : this(
            memoVersionJournal = memoVersionJournal,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

        private val pendingMutex = Mutex()
        private val pendingRequests = linkedMapOf<String, PendingMemoVersionRecordRequest>()
        private var drainJob: Job? = null

        override suspend fun enqueueLocalRevision(
            memo: Memo,
            lifecycleState: MemoRevisionLifecycleState,
            origin: MemoRevisionOrigin,
        ) {
            pendingMutex.withLock {
                val request = PendingMemoVersionRecordRequest.create(memo, lifecycleState, origin)
                pendingRequests[request.requestKey] = request
                if (drainJob?.isActive != true) {
                    drainJob = scope.launch { drainPendingRequests() }
                }
            }
        }

        private suspend fun drainPendingRequests() {
            while (true) {
                val nextRequest =
                    pendingMutex.withLock {
                        pendingRequests.entries.firstOrNull()?.let { (requestKey, request) ->
                            pendingRequests.remove(requestKey)
                            request
                        }
                    } ?: break
                runCatching {
                    memoVersionJournal.appendLocalRevision(
                        memo = nextRequest.memo,
                        lifecycleState = nextRequest.lifecycleState,
                        origin = nextRequest.origin,
                    )
                }.onFailure { throwable ->
                    Timber.w(
                        throwable,
                        "Background memo version append failed for memoId=%s origin=%s",
                        nextRequest.memo.id,
                        nextRequest.origin,
                    )
                }
            }
            pendingMutex.withLock {
                drainJob = null
                if (pendingRequests.isNotEmpty()) {
                    drainJob = scope.launch { drainPendingRequests() }
                }
            }
        }
    }

private data class PendingMemoVersionRecordRequest(
    val requestKey: String,
    val memo: Memo,
    val lifecycleState: MemoRevisionLifecycleState,
    val origin: MemoRevisionOrigin,
) {
    companion object {
        fun create(
            memo: Memo,
            lifecycleState: MemoRevisionLifecycleState,
            origin: MemoRevisionOrigin,
        ): PendingMemoVersionRecordRequest =
            PendingMemoVersionRecordRequest(
                requestKey = requestKeyFor(memo = memo, lifecycleState = lifecycleState, origin = origin),
                memo = memo,
                lifecycleState = lifecycleState,
                origin = origin,
            )

        private fun requestKeyFor(
            memo: Memo,
            lifecycleState: MemoRevisionLifecycleState,
            origin: MemoRevisionOrigin,
        ): String =
            if (origin == MemoRevisionOrigin.LOCAL_EDIT && lifecycleState == MemoRevisionLifecycleState.ACTIVE) {
                "memo-edit:${memo.id}"
            } else {
                UUID.randomUUID().toString()
            }
    }
}
