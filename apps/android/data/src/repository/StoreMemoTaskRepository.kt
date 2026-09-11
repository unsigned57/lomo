package com.lomo.data.repository

import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.engine.store.StoreMemoCommit
import com.lomo.data.engine.store.toStoreInvalidationScope
import com.lomo.data.engine.store.toStoreLong
import com.lomo.data.engine.withEngineFailureConversion
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.MemoTask
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MemoTaskRepository
import com.lomo.domain.repository.WorkspaceMutationLease
import com.lomo.nativebridge.SessionTaskItem
import com.lomo.nativebridge.SessionToggleTaskRequest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class StoreMemoTaskRepository(
    private val session: SessionNativeBridge,
    private val invalidation: StoreInvalidationBus,
    private val writeLease: WorkspaceMutationLease,
    private val readiness: EngineReadinessRepository,
) : MemoTaskRepository {
    override suspend fun listTasks(): List<MemoTask> =
        withContext(Dispatchers.IO) {
            if (readiness.readiness.value !is EngineReadiness.Ready) {
                return@withContext emptyList()
            }
            withEngineFailureConversion {
                session.sessionListTasks().map { item -> item.toDomainTask() }
            }
        }

    override suspend fun toggleTask(
        task: MemoTask,
        done: Boolean,
    ) {
        writeLease.withWrite { _ ->
            withContext(Dispatchers.IO) {
                withEngineFailureConversion {
                    val commit =
                        session.sessionToggleTask(
                            SessionToggleTaskRequest(
                                operationId = UUID.randomUUID().toString(),
                                memoId = task.memoId,
                                lineIndex = task.lineIndex.toUInt(),
                                done = done,
                            ),
                        )
                    invalidation.publish(commit.toDomainStoreCommit())
                }
            }
        }
    }
}

private fun SessionTaskItem.toDomainTask(): MemoTask {
    require(lineIndex <= Int.MAX_VALUE.toUInt()) { "task line_index exceeds the Kotlin Int range" }
    return MemoTask(
        memoId = memoId,
        lineIndex = lineIndex.toInt(),
        done = done,
        text = text,
        sourcePath = sourcePath,
    )
}

private fun com.lomo.nativebridge.StoreMemoCommit.toDomainStoreCommit(): StoreMemoCommit =
    StoreMemoCommit(
        operationId = operationId,
        memoId = memoId,
        coreRevision = coreRevision.toStoreLong("core_revision"),
        eventSequence = eventSequence.toStoreLong("event_sequence"),
        contentRevision = contentRevision.toStoreLong("content_revision"),
        fileFingerprint = fileFingerprint,
        scopes = scopes.map(String::toStoreInvalidationScope),
        idempotentReplay = idempotentReplay,
    )
