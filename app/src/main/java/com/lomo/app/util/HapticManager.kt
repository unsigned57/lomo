
package com.lomo.app.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * A proxy for [HapticFeedback] that respects the user's haptic setting.
 */
class HapticManager(
    private val hapticFeedback: HapticFeedback,
    private val hapticEnabled: Boolean,
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (hapticEnabled) {
            hapticFeedback.performHapticFeedback(hapticFeedbackType)
        }
    }
}

/**
 * CompositionLocal to provide a [HapticFeedback] instance that respects user preferences.
 */
val LocalMemosHapticFeedback: ProvidableCompositionLocal<HapticFeedback> =
    compositionLocalOf {
        error("No HapticFeedback provided")
    }

@Composable
fun ProvideHapticFeedback(
    hapticEnabled: Boolean,
    content: @Composable (hapticEnabled: Boolean) -> Unit,
) {
    val currentHaptic = LocalHapticFeedback.current
    val hapticManager =
        remember(hapticEnabled, currentHaptic) {
            HapticManager(currentHaptic, hapticEnabled)
        }

    CompositionLocalProvider(
        LocalHapticFeedback provides hapticManager,
        LocalMemosHapticFeedback provides hapticManager,
    ) {
        content(hapticEnabled)
    }
}
