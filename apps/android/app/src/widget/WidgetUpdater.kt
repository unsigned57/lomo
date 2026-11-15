package com.lomo.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.withContext

private val LOMO_WIDGET_RECEIVERS: List<Class<out AppWidgetProvider>> =
    listOf(
        LomoWidgetReceiver::class.java,
        LomoTasksWidgetReceiver::class.java,
        LomoInsightsWidgetReceiver::class.java,
        LomoReviewWidgetReceiver::class.java,
    )

internal fun buildWidgetProjectionUpdateIntent(context: Context, receiverClass: Class<out AppWidgetProvider>): Intent =
    Intent(ACTION_WIDGET_PROJECTION_UPDATED).apply {
        `package` = context.packageName
        component = ComponentName(context, receiverClass)
    }

/**
 * Updates bound home-screen widgets from a projection publication across the `:widget` process
 * boundary. Mutations must route through [com.lomo.app.repository.AppWidgetRepository].
 */
object WidgetUpdater {
    suspend fun updateAllWidgets(
        context: Context,
        dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    ) {
        withContext(dispatcherProvider.io) {
            val appContext = context.applicationContext ?: context
            val manager = AppWidgetManager.getInstance(appContext)
            LOMO_WIDGET_RECEIVERS.forEach { receiverClass ->
                val boundIds = manager.getAppWidgetIds(ComponentName(appContext, receiverClass))
                if (boundIds.isNotEmpty()) {
                    appContext.sendBroadcast(
                        buildWidgetProjectionUpdateIntent(appContext, receiverClass),
                    )
                }
            }
        }
    }

    /** Refreshes bound review widgets in `:widget` after advancing a widget review cursor. */
    suspend fun updateReviewWidget(
        context: Context,
        appWidgetId: Int,
        dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    ) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        withContext(dispatcherProvider.io) {
            val appContext = context.applicationContext ?: context
            val manager = AppWidgetManager.getInstance(appContext)
            val boundIds =
                manager.getAppWidgetIds(
                    ComponentName(appContext, LomoReviewWidgetReceiver::class.java),
                )
            if (appWidgetId in boundIds) {
                appContext.sendBroadcast(
                    buildWidgetProjectionUpdateIntent(appContext, LomoReviewWidgetReceiver::class.java),
                )
            }
        }
    }
}
