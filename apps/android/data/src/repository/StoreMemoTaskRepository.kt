package com.lomo.data.repository

import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.engine.withEngineFailureConversion
import com.lomo.domain.model.MemoTask
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MemoTaskRepository
import com.lomo.domain.repository.WorkspaceMutationLease
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.nativebridge.SessionTaskItem
import com.lomo.nativebridge.SessionToggleTaskRequest
import java.util.UUID
import kotlinx.coroutines.withContext

internal class StoreMemoTaskRepository(
    private val session: SessionNativeBridge,
    private val writeLease: WorkspaceMutationLease,
    private val readiness: EngineReadinessRepository,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : MemoTaskRepository {
    override suspend fun listTasks(): List<MemoTask> =
        withContext(dispatcherProvider.io) {
            if (!readiness.mount.value.admitsProjectionReads) {
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
            withContext(dispatcherProvider.io) {
                withEngineFailureConversion {
                    session.sessionToggleTask(
                        SessionToggleTaskRequest(
                            operationId = UUID.randomUUID().toString(),
                            memoId = task.memoId,
                            lineIndex = task.lineIndex.toUInt(),
                            done = done,
                        ),
                    )
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
