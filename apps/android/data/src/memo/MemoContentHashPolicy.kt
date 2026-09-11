package com.lomo.data.memo

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object MemoContentHashPolicy {
    fun hashHex(content: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(content.trim().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
