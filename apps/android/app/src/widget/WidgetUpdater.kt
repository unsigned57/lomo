package com.lomo.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.withContext

/**
 * Updates Glance widgets from a projection publication. Mutations must not call this directly.
 */
object WidgetUpdater {
    suspend fun updateAllWidgets(
        context: Context,
        dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    ) {
        withContext(dispatcherProvider.io) {
            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(LomoWidget::class.java)
            glanceIds.forEach { glanceId ->
                LomoWidget().update(context, glanceId)
            }
        }
    }
}
