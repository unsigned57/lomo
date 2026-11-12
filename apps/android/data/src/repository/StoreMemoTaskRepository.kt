package com.lomo.data.repository

import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.engine.withEngineFailureConversion
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.MemoTask
import com.lomo.domain.model.WorkspaceMount
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MemoTaskRepository
import com.lomo.domain.repository.WorkspaceMutationLease
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.nativebridge.SessionTaskItem
import com.lomo.nativebridge.SessionToggleTaskRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

internal class StoreMemoTaskRepository(
    private val session: SessionNativeBridge,
    private val writeLease: WorkspaceMutationLease,
    private val readiness: EngineReadinessRepository,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : MemoTaskRepository {
    /**
     * A task snapshot is only a fact once the mount admits projection reads. While the engine is
     * [EngineReadiness.Opening] — or Ready but still waiting on the first verified projection —
     * "zero tasks" is undefined input, so the call waits for the mount to settle instead of
     * fabricating an empty list that the screen would latch forever. A settled mount that still
     * does not admit reads (no workspace selected, non-admitting recovery, shutdown) legitimately
     * has no readable tasks and resolves to empty.
     */
    override suspend fun listTasks(): List<MemoTask> = withContext(dispatcherProvider.io) {
        val mount =
            readiness.mount.first { candidate ->
                candidate.admitsProjectionReads || candidate.settledWithoutProjectionReads()
            }
        if (!mount.admitsProjectionReads) {
            return@withContext emptyList()
        }
        withEngineFailureConversion {
            session.sessionListTasks().map { item -> item.toDomainTask() }
        }
    }

    // The repository is the command origin for task toggles; operation ids are minted here,
    // once per invocation, never at the engine adapter call boundary.
    private fun newOperationId(): String = UUID.randomUUID().toString()

    override suspend fun toggleTask(task: MemoTask, done: Boolean) {
        writeLease.withWrite { _ ->
            withContext(dispatcherProvider.io) {
                withEngineFailureConversion {
                    session.sessionToggleTask(
                        SessionToggleTaskRequest(
                            operationId = newOperationId(),
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

private fun WorkspaceMount.settledWithoutProjectionReads(): Boolean =
    readiness is EngineReadiness.AwaitingWorkspaceSelection ||
        readiness is EngineReadiness.ReadOnlyRecovery ||
        readiness is EngineReadiness.ShuttingDown

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
