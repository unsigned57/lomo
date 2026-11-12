package com.lomo.app.util

import android.text.Layout
import com.lomo.ui.text.resolveMemoParagraphLayoutPolicy

internal data class ShareCardParagraphLayoutPolicy(
    val alignment: Layout.Alignment,
    val justificationMode: Int,
    val breakStrategy: Int,
    val hyphenationFrequency: Int,
) {
    companion object {
        /** Titles, tags, quotes, code and placeholders: start-aligned, unjustified, normal hyphenation. */
        val Plain =
            ShareCardParagraphLayoutPolicy(
                alignment = Layout.Alignment.ALIGN_NORMAL,
                justificationMode = Layout.JUSTIFICATION_MODE_NONE,
                breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY,
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL,
            )
    }
}

internal fun resolveShareCardParagraphLayoutPolicy(
    text: String,
    shouldUseCenteredBody: Boolean,
): ShareCardParagraphLayoutPolicy {
    val policy = resolveMemoParagraphLayoutPolicy(text = text, centered = shouldUseCenteredBody)
    return ShareCardParagraphLayoutPolicy(
        alignment = policy.alignment,
        justificationMode = policy.justificationMode,
        breakStrategy = policy.breakStrategy,
        hyphenationFrequency = policy.hyphenationFrequency,
    )
}
