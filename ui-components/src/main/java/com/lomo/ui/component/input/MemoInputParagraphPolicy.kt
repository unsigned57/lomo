package com.lomo.ui.component.input

import android.text.Layout
import android.view.View
import android.view.Gravity
import com.lomo.ui.text.MemoParagraphLayoutPolicy

internal fun resolveMemoInputParagraphLayoutPolicy(
    unusedText: CharSequence,
): MemoParagraphLayoutPolicy {
    unusedText.length
    return MemoParagraphLayoutPolicy(
        alignment = Layout.Alignment.ALIGN_NORMAL,
        gravity = Gravity.START or Gravity.TOP,
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START,
        justificationMode = Layout.JUSTIFICATION_MODE_NONE,
        breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY,
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE,
        shouldUseStrictCjkJustification = false,
    )
}
