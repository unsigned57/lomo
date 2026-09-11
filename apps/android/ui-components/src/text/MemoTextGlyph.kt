package com.lomo.ui.text

internal data class MemoTextGlyph(
    val start: Int,
    val end: Int,
    val xPx: Float,
    val widthPx: Float,
    val isWhitespace: Boolean,
)
