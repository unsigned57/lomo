package com.lomo.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility object for updating LomoWidget when memo data changes.
 * Call this after creating, editing, or deleting memos.
 */
object WidgetUpdater {
    suspend fun updateAllWidgets(context: Context) {
        withContext(Dispatchers.IO) {
            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(LomoWidget::class.java)
            glanceIds.forEach { glanceId ->
                LomoWidget().update(context, glanceId)
            }
        }
    }
}
