package com.lomo.app.repository

import android.content.Context
import com.lomo.app.widget.WidgetUpdater


open class AppWidgetRepository(
    private val context: Context,
) {
        open suspend fun updateAllWidgets() {
            WidgetUpdater.updateAllWidgets(context)
        }
    }
