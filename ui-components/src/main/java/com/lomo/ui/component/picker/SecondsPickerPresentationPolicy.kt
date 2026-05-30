package com.lomo.ui.component.picker

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

internal enum class SecondsPickerChangeOrigin {
    UserScroll,
    ExternalValueSync,
}

internal enum class SecondsPickerScrollBehavior {
    Animated,
    Immediate,
}

internal data class SecondsPickerSelectionEffect(
    val emitValueChange: Boolean,
    val emitHaptic: Boolean,
)

internal data class SecondsPickerAccessibilityProgressAction(
    val handled: Boolean,
    val targetSecond: Int,
    val emitValueChange: Boolean,
)

internal object SecondsPickerPresentationPolicy {
    private const val MINIMUM_DISPLAY_DIGITS = 2

    fun displayText(
        second: Int,
        locale: Locale = Locale.ROOT,
    ): String =
        NumberFormat
            .getIntegerInstance(locale)
            .apply {
                minimumIntegerDigits = MINIMUM_DISPLAY_DIGITS
                isGroupingUsed = false
            }.format(SecondsWheelMath.clamp(second))

    fun scrollBehavior(reduceMotion: Boolean): SecondsPickerScrollBehavior =
        if (reduceMotion) {
            SecondsPickerScrollBehavior.Immediate
        } else {
            SecondsPickerScrollBehavior.Animated
        }

    fun selectionEffect(
        origin: SecondsPickerChangeOrigin,
        previousCenteredSecond: Int,
        centeredSecond: Int,
        externalValue: Int,
    ): SecondsPickerSelectionEffect {
        val clampedCenteredSecond = SecondsWheelMath.clamp(centeredSecond)
        val changedCenter =
            clampedCenteredSecond != SecondsWheelMath.clamp(previousCenteredSecond)
        val userScrollChangedExternalValue =
            origin == SecondsPickerChangeOrigin.UserScroll &&
                clampedCenteredSecond != SecondsWheelMath.clamp(externalValue)
        val shouldEmit = changedCenter && userScrollChangedExternalValue

        return SecondsPickerSelectionEffect(
            emitValueChange = shouldEmit,
            emitHaptic = shouldEmit,
        )
    }

    fun accessibilityProgressAction(
        requestedProgress: Float,
        currentValue: Int,
    ): SecondsPickerAccessibilityProgressAction {
        val targetSecond = SecondsWheelMath.clamp(requestedProgress.roundToInt())
        return SecondsPickerAccessibilityProgressAction(
            handled = true,
            targetSecond = targetSecond,
            emitValueChange = targetSecond != SecondsWheelMath.clamp(currentValue),
        )
    }
}
