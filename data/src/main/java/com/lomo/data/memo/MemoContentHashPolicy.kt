package com.lomo.data.memo

import kotlin.math.abs

internal object MemoContentHashPolicy {
    fun hashHex(content: String): String = abs(content.trim().hashCode()).toString(16)
}
