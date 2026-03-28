package com.lomo.ui.text

import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.view.Gravity

data class MemoParagraphLayoutPolicy(
    val alignment: Layout.Alignment,
    val gravity: Int,
    val textAlignment: Int,
    val justificationMode: Int,
    val breakStrategy: Int,
    val hyphenationFrequency: Int,
    val shouldUseStrictCjkJustification: Boolean,
)

fun resolveMemoParagraphLayoutPolicy(
    text: CharSequence,
    centered: Boolean = false,
    sdkInt: Int = effectiveMemoParagraphSdkInt(),
): MemoParagraphLayoutPolicy {
    if (centered) {
        return MemoParagraphLayoutPolicy(
            alignment = Layout.Alignment.ALIGN_CENTER,
            gravity = Gravity.CENTER_HORIZONTAL,
            textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER,
            justificationMode = Layout.JUSTIFICATION_MODE_NONE,
            breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY,
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE,
            shouldUseStrictCjkJustification = false,
        )
    }

    val shouldUseStrictCjkJustification =
        text.shouldUsePlatformCjkJustification() &&
            sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    return MemoParagraphLayoutPolicy(
        alignment = Layout.Alignment.ALIGN_NORMAL,
        gravity = Gravity.START,
        textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START,
        justificationMode =
            if (shouldUseStrictCjkJustification) {
                Layout.JUSTIFICATION_MODE_INTER_CHARACTER
            } else {
                Layout.JUSTIFICATION_MODE_NONE
            },
        breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY,
        hyphenationFrequency =
            if (shouldUseStrictCjkJustification) {
                Layout.HYPHENATION_FREQUENCY_NONE
            } else {
                Layout.HYPHENATION_FREQUENCY_NORMAL
            },
        shouldUseStrictCjkJustification = shouldUseStrictCjkJustification,
    )
}

internal fun effectiveMemoParagraphSdkInt(): Int =
    Build.VERSION.SDK_INT.takeIf { it > 0 } ?: Build.VERSION_CODES.VANILLA_ICE_CREAM
