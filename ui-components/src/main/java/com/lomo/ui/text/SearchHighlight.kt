package com.lomo.ui.text

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import androidx.compose.runtime.compositionLocalOf

val LocalSearchHighlightQuery = compositionLocalOf { "" }

internal fun CharSequence.applySearchHighlight(
    query: String,
    highlightColor: Int,
): CharSequence {
    if (query.isBlank()) return this

    val text = this.toString()
    val spannable =
        if (this is SpannableString) {
            this
        } else {
            SpannableString(this)
        }

    var startIndex = 0
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()

    while (startIndex < lowerText.length) {
        val foundIndex = lowerText.indexOf(lowerQuery, startIndex)
        if (foundIndex < 0) break
        spannable.setSpan(
            BackgroundColorSpan(highlightColor),
            foundIndex,
            foundIndex + lowerQuery.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        startIndex = foundIndex + lowerQuery.length
    }

    return spannable
}
