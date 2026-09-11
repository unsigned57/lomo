package com.lomo.data.engine

import com.lomo.domain.model.markdown.MarkdownRenderDocument

/**
 * Shared render transport validation helpers (module-internal; used by tree and document mapping).
 */

internal fun <T> T?.required(code: String, field: String): T =
    this ?: failRenderBoundary(code) { "$field is required by render schema v1" }

internal fun validateSpan(
    start: ULong,
    end: ULong,
    sourceLength: ULong,
    code: String,
) {
    requireRenderBoundary(start <= end && end <= sourceLength, code) {
        "render span must be ordered and contained by source bytes"
    }
}

internal fun validateRenderString(value: String) {
    requireRenderBoundary(
        value.encodeToByteArray().size <= MarkdownRenderDocument.MAX_STRING_UTF8_BYTES,
        "render_string_limit_exceeded",
    ) {
        "render string exceeds ${MarkdownRenderDocument.MAX_STRING_UTF8_BYTES} UTF-8 bytes"
    }
}

internal inline fun requireRenderBoundary(
    condition: Boolean,
    code: String,
    message: () -> String,
) {
    if (!condition) throw WorkspaceRenderBoundaryException(code, message())
}

internal inline fun failRenderBoundary(
    code: String,
    message: () -> String,
): Nothing = throw WorkspaceRenderBoundaryException(code, message())
