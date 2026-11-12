package com.lomo.data.engine.lan

import java.io.InputStream

/**
 * Reads exactly [size] bytes. `InputStream.readNBytes` is API 33; minSdk devices must not reach
 * it. A source shorter than its planned size is a failed transfer, never a truncated chunk.
 */
internal fun InputStream.readExactly(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var filled = 0
    while (filled < size) {
        val read = read(bytes, filled, size - filled)
        check(read > 0) { "LAN attachment source ended before its planned size" }
        filled += read
    }
    return bytes
}
