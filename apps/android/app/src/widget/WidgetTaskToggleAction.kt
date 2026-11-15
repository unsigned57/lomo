package com.lomo.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.MemoTask
import com.lomo.domain.model.SecuritySessionState
import com.lomo.domain.model.WorkspaceMount
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MemoTaskRepository
import com.lomo.domain.repository.SecuritySessionPolicy
import com.lomo.domain.repository.WorkspaceMutationLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import timber.log.Timber

internal val WIDGET_TASK_MEMO_ID_KEY = ActionParameters.Key<String>("widget_task_memo_id")
internal val WIDGET_TASK_LINE_INDEX_KEY = ActionParameters.Key<Int>("widget_task_line_index")
internal val WIDGET_TASK_TARGET_DONE_KEY = ActionParameters.Key<Boolean>("widget_task_target_done")
internal const val WIDGET_WRITE_SETTLE_TIMEOUT_MS: Long = 5_000L

internal sealed interface WidgetTaskToggleOutcome {
    data object Toggled : WidgetTaskToggleOutcome

    data class OpenMemoFallback(val memoId: String) : WidgetTaskToggleOutcome
}

internal suspend fun executeWidgetTaskToggle(
    memoId: String,
    lineIndex: Int,
    targetDone: Boolean,
    isLocked: suspend () -> Boolean,
    admitsWrites: () -> Boolean,
    listTasks: suspend () -> List<MemoTask>,
    toggleTask: suspend (MemoTask, Boolean) -> Unit,
    ensureMountReady: suspend () -> Unit = {},
): WidgetTaskToggleOutcome {
    if (isLocked()) {
        return WidgetTaskToggleOutcome.OpenMemoFallback(memoId)
    }
    if (!admitsWrites()) {
        ensureMountReady()
    }
    if (!admitsWrites()) {
        return WidgetTaskToggleOutcome.OpenMemoFallback(memoId)
    }
    val liveTask =
        listTasks().firstOrNull { task ->
            task.memoId == memoId && task.lineIndex == lineIndex
        } ?: return WidgetTaskToggleOutcome.OpenMemoFallback(memoId)
    if (liveTask.done != targetDone) {
        toggleTask(liveTask, targetDone)
    }
    return WidgetTaskToggleOutcome.Toggled
}

internal suspend fun awaitWidgetMountWritable(
    engineReadiness: EngineReadinessRepository,
    writeLease: WorkspaceMutationLease,
    timeoutMs: Long = WIDGET_WRITE_SETTLE_TIMEOUT_MS,
) {
    val current = engineReadiness.mount.value
    if ((current.admitsProjectionWrites && writeLease.isWritable()) || current.settledWithoutProjectionWrites()) {
        return
    }
    withTimeoutOrNull(timeoutMs) {
        engineReadiness.requestEngineStart()
        combine(engineReadiness.mount, writeLease.isWritableFlow()) { mount, writable ->
            (mount.admitsProjectionWrites && writable) || mount.settledWithoutProjectionWrites()
        }.first { it }
    }
}

private fun WorkspaceMount.settledWithoutProjectionWrites(): Boolean =
    readiness is EngineReadiness.AwaitingWorkspaceSelection ||
        readiness is EngineReadiness.ReadOnlyRecovery ||
        readiness is EngineReadiness.ShuttingDown

/**
 * Interactive task checkbox handler for [LomoTasksWidget].
 *
 * Glance runs [ActionCallback] in the default application process where Koin and the Rust workspace
 * kernel live. When woken cold in the background without an Activity, this awaits verified write
 * readiness before verifying the target line, while locked sessions, non-writable mounts, or
 * shifted line indices degrade to opening the owning memo so the wrong Markdown line is never
 * mutated.
 */
class ToggleWidgetTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val memoId = parameters[WIDGET_TASK_MEMO_ID_KEY]?.takeIf { it.isNotBlank() } ?: return
        val lineIndex = parameters[WIDGET_TASK_LINE_INDEX_KEY]
        val targetDone = parameters[WIDGET_TASK_TARGET_DONE_KEY]
        if (lineIndex == null || lineIndex < 0 || targetDone == null) {
            context.startActivity(WidgetLaunchActivity.createIntent(context, WidgetLaunchTarget.OpenMemo(memoId)))
            return
        }

        val koin = GlobalContext.getOrNull()
        val securitySession = koin?.getOrNull<SecuritySessionPolicy>()
        val engineReadiness = koin?.getOrNull<EngineReadinessRepository>()
        val writeLease = koin?.getOrNull<WorkspaceMutationLease>()
        val taskRepository = koin?.getOrNull<MemoTaskRepository>()
        if (securitySession == null || engineReadiness == null || writeLease == null || taskRepository == null) {
            context.startActivity(WidgetLaunchActivity.createIntent(context, WidgetLaunchTarget.OpenMemo(memoId)))
            return
        }

        val outcome =
            try {
                executeWidgetTaskToggle(
                    memoId = memoId,
                    lineIndex = lineIndex,
                    targetDone = targetDone,
                    isLocked = { securitySession.current() != SecuritySessionState.LockOff },
                    ensureMountReady = {
                        awaitWidgetMountWritable(
                            engineReadiness = engineReadiness,
                            writeLease = writeLease,
                        )
                    },
                    admitsWrites = {
                        engineReadiness.mount.value.admitsProjectionWrites && writeLease.isWritable()
                    },
                    listTasks = { taskRepository.listTasks() },
                    toggleTask = { task, done -> taskRepository.toggleTask(task, done) },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Timber.w(failure, "Failed to toggle widget task for memo %s", memoId)
                WidgetTaskToggleOutcome.OpenMemoFallback(memoId)
            }

        when (outcome) {
            WidgetTaskToggleOutcome.Toggled -> {
                koin.getOrNull<WidgetProjectionBinder>()?.refreshNow()
            }

            is WidgetTaskToggleOutcome.OpenMemoFallback -> {
                context.startActivity(
                    WidgetLaunchActivity.createIntent(
                        context = context,
                        target = WidgetLaunchTarget.OpenMemo(outcome.memoId),
                    ),
                )
            }
        }
    }
}
