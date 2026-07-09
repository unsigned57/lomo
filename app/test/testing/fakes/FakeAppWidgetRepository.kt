package com.lomo.app.testing.fakes

import android.content.Context
import com.lomo.app.repository.AppWidgetRepository
import io.mockk.mockk

class FakeAppWidgetRepository(context: Context = mockk()) : AppWidgetRepository(context) {
    var updateAllWidgetsCalled = false
        private set

    override suspend fun updateAllWidgets() {
        updateAllWidgetsCalled = true
    }

    fun reset() {
        updateAllWidgetsCalled = false
    }
}
