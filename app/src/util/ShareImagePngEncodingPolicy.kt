package com.lomo.app.util

internal fun requireSuccessfulPngEncode(encoded: Boolean) {
    check(encoded) { "Failed to encode share image as PNG" }
}
