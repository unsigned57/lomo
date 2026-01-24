package com.lomo.app.repository

import android.content.Context
import com.lomo.app.widget.WidgetUpdater
import com.lomo.domain.repository.WidgetRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AppWidgetRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : WidgetRepository {
        override suspend fun updateAllWidgets() {
            WidgetUpdater.updateAllWidgets(context)
        }
    }
