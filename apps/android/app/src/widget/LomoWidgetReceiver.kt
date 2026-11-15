package com.lomo.app.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/**
 * Broadcast receiver for LomoWidget.
 * Handles widget lifecycle events (add, update, delete).
 */
class LomoWidgetReceiver : AppWidgetProvider() {
    private val dispatcher = LomoProjectionWidgetDispatcher(LomoWidgetReceiver::class.java, LomoWidget())

    override fun onReceive(context: Context, intent: Intent) {
        dispatcher.onReceive(this, context, intent) { super.onReceive(context, intent) }
    }
}
