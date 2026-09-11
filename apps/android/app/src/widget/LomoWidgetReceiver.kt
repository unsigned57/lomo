package com.lomo.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Broadcast receiver for LomoWidget.
 * Handles widget lifecycle events (add, update, delete).
 */
class LomoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LomoWidget()
}
