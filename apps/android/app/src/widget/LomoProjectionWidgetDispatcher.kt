package com.lomo.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.glance.EmittableWithChildren
import androidx.glance.GlanceId
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.AppWidgetSession
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.session.runSession
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

internal const val ACTION_WIDGET_PROJECTION_UPDATED =
    "com.lomo.app.action.WIDGET_PROJECTION_UPDATED"
private const val WIDGET_SESSION_TIMEOUT_MS = 8_500L

/**
 * Resolves a valid AppWidget ID from a [GlanceId] without instantiating
 * `GlanceAppWidgetManager`, which opens single-process DataStore across the main/`:widget`
 * process boundary.
 */
internal fun resolveAppWidgetId(glanceId: GlanceId): Int? = when (glanceId) {
    is AppWidgetId -> glanceId.appWidgetId.takeIf { id -> id != AppWidgetManager.INVALID_APPWIDGET_ID }
    else -> null
}

internal fun filterBoundWidgetIds(candidateIds: IntArray, hasBoundInfo: (Int) -> Boolean): List<Int> = candidateIds
    .asSequence()
    .filter { id -> id != AppWidgetManager.INVALID_APPWIDGET_ID && hasBoundInfo(id) }
    .distinct()
    .toList()

private class OneShotAppWidgetSession(
    widget: GlanceAppWidget,
    id: AppWidgetId,
    initialOptions: Bundle?,
    lambdaReceiver: ComponentName,
) : AppWidgetSession(
    widget = widget,
    id = id,
    initialOptions = initialOptions,
    lambdaReceiver = lambdaReceiver,
    shouldPublish = true,
) {
    override suspend fun processEmittableTree(context: Context, root: EmittableWithChildren): Boolean {
        val processed = super.processEmittableTree(context, root)
        if (processed) {
            close()
        }
        return processed
    }

    override suspend fun onCompositionError(context: Context, throwable: Throwable) {
        try {
            super.onCompositionError(context, throwable)
        } finally {
            close()
        }
    }
}

/**
 * Process-local dispatcher shared by `:widget` [AppWidgetProvider] receivers.
 *
 * `GlanceAppWidgetReceiver` enqueues WorkManager `SessionWorker` (which runs in the default process
 * without `:widget`'s in-memory session map) and accesses non-multiprocess DataStore in
 * `GlanceAppWidgetManager`. This dispatcher runs one-shot [AppWidgetSession] compositions directly
 * in `:widget` inside `BroadcastReceiver.goAsync()`, ignores caller intent extras on projection
 * update broadcasts, and skips unbound IDs.
 */
internal class LomoProjectionWidgetDispatcher(
    private val receiverClass: Class<out AppWidgetProvider>,
    private val glanceAppWidget: GlanceAppWidget,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) {
    fun onReceive(receiver: BroadcastReceiver, context: Context, intent: Intent, fallbackOnReceive: () -> Unit) {
        when (intent.action) {
            ACTION_WIDGET_PROJECTION_UPDATED -> {
                refreshBoundWidgets(receiver = receiver, context = context)
            }

            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            AppWidgetManager.ACTION_APPWIDGET_RESTORED,
            -> {
                val appWidgetIds =
                    intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: return
                composeWidgets(
                    receiver = receiver,
                    context = context,
                    candidateIds = appWidgetIds,
                    optionsOverride = null,
                )
            }

            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED -> {
                val appWidgetId =
                    intent.getIntExtra(
                        AppWidgetManager.EXTRA_APPWIDGET_ID,
                        AppWidgetManager.INVALID_APPWIDGET_ID,
                    )
                if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
                val options =
                    intent.getBundleExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS) ?: Bundle.EMPTY
                composeWidgets(
                    receiver = receiver,
                    context = context,
                    candidateIds = intArrayOf(appWidgetId),
                    optionsOverride = options,
                )
            }

            AppWidgetManager.ACTION_APPWIDGET_DELETED -> {
                val singleId =
                    intent.getIntExtra(
                        AppWidgetManager.EXTRA_APPWIDGET_ID,
                        AppWidgetManager.INVALID_APPWIDGET_ID,
                    )
                val candidateIds =
                    if (singleId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        intArrayOf(singleId)
                    } else {
                        intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: return
                    }
                deleteWidgets(receiver = receiver, context = context, appWidgetIds = candidateIds)
            }

            else -> {
                fallbackOnReceive()
            }
        }
    }

    private fun refreshBoundWidgets(receiver: BroadcastReceiver, context: Context) {
        val appContext = context.applicationContext ?: context
        val appWidgetManager = AppWidgetManager.getInstance(appContext)
        val boundIds = appWidgetManager.getAppWidgetIds(ComponentName(appContext, receiverClass))
        composeWidgets(
            receiver = receiver,
            context = appContext,
            candidateIds = boundIds,
            optionsOverride = null,
            appWidgetManager = appWidgetManager,
        )
    }

    private fun composeWidgets(
        receiver: BroadcastReceiver,
        context: Context,
        candidateIds: IntArray,
        optionsOverride: Bundle?,
        appWidgetManager: AppWidgetManager =
            AppWidgetManager.getInstance(context.applicationContext ?: context),
    ) {
        val targetIds =
            filterBoundWidgetIds(candidateIds) { id -> appWidgetManager.getAppWidgetInfo(id) != null }
        if (targetIds.isEmpty()) return

        val appContext = context.applicationContext ?: context
        val receiverComponent = ComponentName(appContext, receiverClass)
        launchReceiverTask(
            receiver = receiver,
            operationName = "batch composing widgets",
            logTimeout = true,
        ) {
            targetIds.forEach { appWidgetId ->
                launch {
                    composeSingleWidget(
                        appContext = appContext,
                        appWidgetManager = appWidgetManager,
                        appWidgetId = appWidgetId,
                        optionsOverride = optionsOverride,
                        receiverComponent = receiverComponent,
                    )
                }
            }
        }
    }

    private fun deleteWidgets(receiver: BroadcastReceiver, context: Context, appWidgetIds: IntArray) {
        val validIds =
            appWidgetIds.filter { id -> id != AppWidgetManager.INVALID_APPWIDGET_ID }.distinct()
        if (validIds.isEmpty()) return

        val appContext = context.applicationContext ?: context
        launchReceiverTask(receiver = receiver, operationName = "deleting widget state") {
            validIds.forEach { appWidgetId ->
                glanceAppWidget.onDelete(appContext, AppWidgetId(appWidgetId))
            }
        }
    }

    private fun launchReceiverTask(
        receiver: BroadcastReceiver,
        operationName: String,
        logTimeout: Boolean = false,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val pendingResult = receiver.goAsync() ?: return
        // behavior-contract: unmanaged-scope-ok: receiver-owned short job cancelled on finish
        val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)
        scope.launch {
            try {
                val completed =
                    withTimeoutOrNull(WIDGET_SESSION_TIMEOUT_MS) {
                        coroutineScope { block() }
                    }
                if (completed == null && logTimeout) {
                    Timber.w("Timed out %s for %s", operationName, receiverClass.simpleName)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Timber.e(failure, "Failed %s for %s", operationName, receiverClass.simpleName)
            } finally {
                scope.cancel()
                pendingResult.finish()
            }
        }
    }

    private suspend fun composeSingleWidget(
        appContext: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        optionsOverride: Bundle?,
        receiverComponent: ComponentName,
    ) {
        try {
            val options = optionsOverride ?: appWidgetManager.getAppWidgetOptions(appWidgetId)
            OneShotAppWidgetSession(
                widget = glanceAppWidget,
                id = AppWidgetId(appWidgetId),
                initialOptions = options,
                lambdaReceiver = receiverComponent,
            ).runSession(appContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Timber.e(
                failure,
                "Failed composing %s for appWidgetId=%d",
                receiverClass.simpleName,
                appWidgetId,
            )
        }
    }
}
