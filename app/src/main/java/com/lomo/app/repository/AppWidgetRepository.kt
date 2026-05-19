package com.lomo.app.repository

import android.content.Context
import com.lomo.app.widget.WidgetUpdater
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

open class AppWidgetRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        open suspend fun updateAllWidgets() {
            WidgetUpdater.updateAllWidgets(context)
        }
    }
