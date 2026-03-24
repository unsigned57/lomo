package com.lomo.data.memo

import kotlin.math.abs

internal object MemoContentHashPolicy {
    private const val HEX_RADIX = 16

    fun hashHex(content: String): String = abs(content.trim().hashCode()).toString(HEX_RADIX)
}
