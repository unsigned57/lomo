package com.lomo.app.util

import android.text.Layout
import com.lomo.ui.text.resolveMemoParagraphLayoutPolicy

internal data class ShareCardParagraphLayoutPolicy(
    val alignment: Layout.Alignment,
    val justificationMode: Int,
    val breakStrategy: Int,
    val hyphenationFrequency: Int,
)

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
