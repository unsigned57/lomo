package com.lomo.data.source

import android.content.ContentResolver
import android.net.Uri
import timber.log.Timber
import java.io.IOException

/**
 * Validates that an imported image URI actually delivers image bytes, not arbitrary content
 * masquerading as an image via a malicious ContentProvider. Rejecting here prevents the
 * workspace (and any downstream sync) from propagating poisoned content.
 */
internal object ImageMagicByteValidator {
    private const val HEADER_SIZE = 12

    fun requireSupportedImage(
        contentResolver: ContentResolver,
        uri: Uri,
    ) {
        val header =
            contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(HEADER_SIZE)
                var total = 0
                while (total < HEADER_SIZE) {
                    val read = stream.read(buffer, total, HEADER_SIZE - total)
                    if (read <= 0) break
                    total += read
                }
                buffer.copyOf(total)
            } ?: throw IOException("Cannot open source image URI for validation")
        if (!looksLikeSupportedImage(header)) {
            Timber.w("Rejected non-image content for uri=%s (size=%d)", uri, header.size)
            throw IOException("Rejected non-image content")
        }
    }

    internal fun looksLikeSupportedImage(header: ByteArray): Boolean =
        ImageSignature.entries.any { signature -> signature.matches(header) }
}

/**
 * Magic-byte signatures for the image formats the workspace is allowed to import.
 * Each entry is either a contiguous prefix, or a "prefix + offset-tagged marker" pair
 * (used by container formats like WebP and ISO-BMFF where the identifier sits after
 * a fixed-size header). Keeping them as data lets the detector stay a short `any` call.
 */
private enum class ImageSignature(
    private val prefix: ByteArray,
    private val markerOffset: Int = 0,
    private val marker: ByteArray = EMPTY,
) {
    // JPEG: FF D8 FF
    JPEG(prefix = byteArrayOf(BYTE_FF, BYTE_D8, BYTE_FF)),

    // PNG: 89 50 4E 47 0D 0A 1A 0A
    PNG(prefix = byteArrayOf(BYTE_89, BYTE_50, BYTE_4E, BYTE_47, BYTE_0D, BYTE_0A, BYTE_1A, BYTE_0A)),

    // GIF87a / GIF89a: 47 49 46 38
    GIF(prefix = byteArrayOf(BYTE_47, BYTE_49, BYTE_46, BYTE_38)),

    // WebP: "RIFF" header followed by "WEBP" at offset 8.
    WEBP(
        prefix = byteArrayOf(BYTE_52, BYTE_49, BYTE_46, BYTE_46),
        markerOffset = WEBP_MARKER_OFFSET,
        marker = byteArrayOf(BYTE_57, BYTE_45, BYTE_42, BYTE_50),
    ),

    // BMP: 42 4D
    BMP(prefix = byteArrayOf(BYTE_42, BYTE_4D)),

    // HEIC/HEIF/AVIF and other ISO-BMFF: "ftyp" tag at byte offset 4.
    ISO_BMFF(
        prefix = EMPTY,
        markerOffset = ISO_BMFF_MARKER_OFFSET,
        marker = byteArrayOf(BYTE_66, BYTE_74, BYTE_79, BYTE_70),
    ),
    ;

    fun matches(header: ByteArray): Boolean {
        if (!header.hasPrefix(prefix)) return false
        if (marker.isEmpty()) return true
        return header.hasMarkerAt(markerOffset, marker)
    }
}

private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
    if (prefix.isEmpty()) return true
    if (size < prefix.size) return false
    for (index in prefix.indices) {
        if (this[index] != prefix[index]) return false
    }
    return true
}

private fun ByteArray.hasMarkerAt(
    offset: Int,
    marker: ByteArray,
): Boolean {
    if (size < offset + marker.size) return false
    for (index in marker.indices) {
        if (this[offset + index] != marker[index]) return false
    }
    return true
}

private const val WEBP_MARKER_OFFSET = 8
private const val ISO_BMFF_MARKER_OFFSET = 4

private val EMPTY = ByteArray(0)

private const val BYTE_0A: Byte = 0x0A
private const val BYTE_0D: Byte = 0x0D
private const val BYTE_1A: Byte = 0x1A
private const val BYTE_38: Byte = 0x38
private const val BYTE_42: Byte = 0x42
private const val BYTE_45: Byte = 0x45
private const val BYTE_46: Byte = 0x46
private const val BYTE_47: Byte = 0x47
private const val BYTE_49: Byte = 0x49
private const val BYTE_4D: Byte = 0x4D
private const val BYTE_4E: Byte = 0x4E
private const val BYTE_50: Byte = 0x50
private const val BYTE_52: Byte = 0x52
private const val BYTE_57: Byte = 0x57
private const val BYTE_66: Byte = 0x66
private const val BYTE_70: Byte = 0x70
private const val BYTE_74: Byte = 0x74
private const val BYTE_79: Byte = 0x79
private const val BYTE_89: Byte = 0x89.toByte()
private const val BYTE_D8: Byte = 0xD8.toByte()
private const val BYTE_FF: Byte = 0xFF.toByte()
