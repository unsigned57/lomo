package com.lomo.ui.component.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CompletableDeferred

/** Rendering readiness owned by one visible modal sheet composition. */
internal class ModalSheetEntry {
    private val measured = CompletableDeferred<Unit>()
    var canExpand by mutableStateOf(false)
        private set

    fun onContentMeasured(height: Int) {
        require(height >= 0) { "Sheet height must not be negative" }
        if (height > 0) measured.complete(Unit)
    }

    suspend fun awaitContentFrame() {
        measured.await()
        withFrameNanos { }
        canExpand = true
    }
}
