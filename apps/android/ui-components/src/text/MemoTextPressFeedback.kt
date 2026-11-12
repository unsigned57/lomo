package com.lomo.ui.text

import androidx.compose.foundation.gestures.PressGestureScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The enclosing surface owns the indication, including presses consumed by canvas text. */
internal class MemoTextPressFeedback(val interactionSource: MutableInteractionSource) {
    var coordinates: LayoutCoordinates? = null
}

internal val LocalMemoTextPressFeedback = staticCompositionLocalOf<MemoTextPressFeedback?> { null }

internal class MemoTextPressTarget {
    var coordinates: LayoutCoordinates? = null

    suspend fun press(scope: PressGestureScope, host: MemoTextPressFeedback, position: Offset) {
        val surface = checkNotNull(host.coordinates) { "Memo press surface must be placed before input" }
        val paragraph = checkNotNull(coordinates) { "Memo paragraph must be placed before input" }
        val press = PressInteraction.Press(surface.localPositionOf(paragraph, position))
        host.interactionSource.emit(press)
        var released = false
        try {
            released = scope.tryAwaitRelease()
        } finally {
            // Expansion can dispose a pressed paragraph. Cleanup completes before cancellation propagates.
            withContext(NonCancellable) {
                host.interactionSource.emit(
                    if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                )
            }
        }
    }
}
