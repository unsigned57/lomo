package com.lomo.data.memo

import com.lomo.data.repository.toVersionHash

internal object MemoContentHashPolicy {
    fun hashHex(content: String): String = content.trim().toVersionHash()
}
