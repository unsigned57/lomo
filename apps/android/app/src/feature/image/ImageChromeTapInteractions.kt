package com.lomo.app.feature.image

import android.os.SystemClock
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.flow.collect

/** Telephoto's release/cancel stream distinguishes taps from transforms without waiting for
 * double-click recognition. It remains the only owner of pinch, pan and double-click zoom.
 */
@Composable
internal fun rememberImageChromeTapInteractions(onTap: () -> Unit): MutableInteractionSource {
    val interactions = remember { MutableInteractionSource() }
    val currentOnTap by rememberUpdatedState(onTap)
    val configuration = LocalViewConfiguration.current
    val doubleTapSlop = with(LocalDensity.current) { configuration.minimumTouchTargetSize.width.toPx() }
    LaunchedEffect(interactions, configuration, doubleTapSlop) {
        collectImageChromeTaps(
            interactions = interactions,
            doubleTapTimeoutMillis = configuration.doubleTapTimeoutMillis,
            longPressTimeoutMillis = configuration.longPressTimeoutMillis,
            doubleTapSlop = doubleTapSlop,
            nowMillis = SystemClock::uptimeMillis,
            onTap = { currentOnTap() },
        )
    }
    return interactions
}

private data class CompletedImageTap(val releasedAtMillis: Long, val position: Offset)

internal suspend fun collectImageChromeTaps(
    interactions: InteractionSource,
    doubleTapTimeoutMillis: Long,
    longPressTimeoutMillis: Long,
    doubleTapSlop: Float,
    nowMillis: () -> Long,
    onTap: () -> Unit,
) {
    var pressStartedAt: Long? = null
    var previousTap: CompletedImageTap? = null
    interactions.interactions.collect { interaction ->
        when (interaction) {
            is PressInteraction.Press -> pressStartedAt = nowMillis()

            is PressInteraction.Cancel -> {
                pressStartedAt = null
                previousTap = null
            }

            is PressInteraction.Release -> {
                val startedAt = pressStartedAt
                pressStartedAt = null
                if (startedAt != null && nowMillis() - startedAt < longPressTimeoutMillis) {
                    val now = nowMillis()
                    val previous = previousTap
                    val isSecondTap = previous != null && now - previous.releasedAtMillis <= doubleTapTimeoutMillis &&
                        (previous.position - interaction.press.pressPosition).getDistance() <= doubleTapSlop
                    if (isSecondTap) {
                        previousTap = null
                    } else {
                        previousTap = CompletedImageTap(now, interaction.press.pressPosition)
                        onTap()
                    }
                }
            }
        }
    }
}
