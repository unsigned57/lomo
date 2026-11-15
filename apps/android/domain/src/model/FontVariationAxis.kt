package com.lomo.domain.model

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

private const val NORMAL_FONT_WEIGHT = 400
private const val TAG_LENGTH = 4
private const val ASCII_PRINTABLE_MIN = 0x20
private const val ASCII_PRINTABLE_MAX = 0x7E
private const val HIDDEN_AXIS_FLAG = 0x0001
private const val SFNT_TRUE_TYPE = 0x00010000
private const val SFNT_OTTO = 0x4F54544F
private const val SFNT_TRUE = 0x74727565
private const val TTC_TAG = 0x74746366
private const val TTC_HEADER_MIN_BYTES = 16L
private const val SFNT_HEADER_BYTES = 12
private const val TABLE_RECORD_BYTES = 16
private const val MAX_TABLE_COUNT = 512
private const val FVAR_HEADER_BYTES = 16
private const val FVAR_AXIS_RECORD_MIN_BYTES = 20
private const val FVAR_SUPPORTED_MAJOR_VERSION = 1
private const val MAX_AXIS_COUNT = 64
private const val MAX_NAME_TABLE_BYTES = 2 * 1024 * 1024
private const val NAME_HEADER_BYTES = 6
private const val NAME_RECORD_BYTES = 12
private const val PLATFORM_UNICODE = 0
private const val PLATFORM_MACINTOSH = 1
private const val PLATFORM_WINDOWS = 3
private const val LANG_WINDOWS_EN_US = 0x0409
private const val ENCODING_UNICODE_BMP = 1
private const val ENCODING_UNICODE_FULL = 10
private const val SCORE_WIN_EN_US = 4
private const val SCORE_WIN_OTHER = 3
private const val SCORE_UNICODE = 2
private const val SCORE_MAC = 1
private const val INVALID_NAME_SCORE = -1
private const val TTC_NUM_FONTS_OFFSET = 8L
private const val TTC_FACE_OFFSET = 12L
private const val SFNT_NUM_TABLES_OFFSET = 4
private const val TABLE_REC_OFFSET_OFFSET = 8
private const val TABLE_REC_LENGTH_OFFSET = 12
private const val FVAR_OFFSET_AXES_ARRAY = 4
private const val FVAR_OFFSET_AXIS_COUNT = 8
private const val FVAR_OFFSET_AXIS_SIZE = 10
private const val AXIS_OFFSET_MIN = 4
private const val AXIS_OFFSET_DEFAULT = 8
private const val AXIS_OFFSET_MAX = 12
private const val AXIS_OFFSET_FLAGS = 16
private const val AXIS_OFFSET_NAME_ID = 18
private const val NAME_COUNT_OFFSET = 2
private const val NAME_STRING_OFFSET = 4
private const val NAME_REC_ENCODING_OFFSET = 2
private const val NAME_REC_LANG_OFFSET = 4
private const val NAME_REC_ID_OFFSET = 6
private const val NAME_REC_LEN_OFFSET = 8
private const val NAME_REC_OFFSET_OFFSET = 10
private const val INT32_BYTES = 4
private const val BYTE_OFFSET_1 = 1
private const val BYTE_OFFSET_2 = 2
private const val BYTE_OFFSET_3 = 3
private const val SHIFT_8 = 8
private const val SHIFT_16 = 16
private const val SHIFT_24 = 24
private const val BYTE_MASK = 0xFF
private const val UINT32_MASK = 0xFFFFFFFFL
private const val FIXED_16_16_SCALE = 65536.0
private const val AXIS_FLOAT_EPSILON = 1e-4f

/**
 * OpenType `fvar` variable font axis declaration.
 *
 * @property tag Four-character OpenType axis tag (for example `"wght"`, `"wdth"`, `"opsz"`).
 * @property name Human-readable axis name resolved from the font `name` table or standard fallback.
 * @property minValue Minimum valid coordinate value.
 * @property defaultValue Font-declared default coordinate value.
 * @property maxValue Maximum valid coordinate value.
 * @property hidden Whether OpenType `HIDDEN_AXIS` (`0x0001`) is flagged.
 */
data class FontVariationAxis(
    val tag: String,
    val name: String,
    val minValue: Float,
    val defaultValue: Float,
    val maxValue: Float,
    val hidden: Boolean = false,
) {
    init {
        require(isValidTag(tag)) { "Invalid OpenType axis tag: '$tag'" }
        require(minValue.isFinite() && defaultValue.isFinite() && maxValue.isFinite()) {
            "Axis bounds must be finite for '$tag'"
        }
        require(minValue <= defaultValue && defaultValue <= maxValue) {
            "Axis bounds must satisfy min <= default <= max for '$tag': [$minValue, $defaultValue, $maxValue]"
        }
    }

    fun clamp(value: Float): Float = value.coerceIn(minValue, maxValue)

    fun isDefaultValue(value: Float): Boolean = kotlin.math.abs(clamp(value) - defaultValue) < AXIS_FLOAT_EPSILON

    companion object {
        fun isValidTag(tag: String): Boolean = tag.length == TAG_LENGTH &&
            tag.all { ch ->
                ch.code in ASCII_PRINTABLE_MIN..ASCII_PRINTABLE_MAX &&
                    ch !in "?=&#/\\"
            }
    }
}

/**
 * Pure Kotlin parser for OpenType `fvar` and `name` tables in `.ttf` / `.otf` / `.ttc` files.
 */
object OpenTypeVariableFontParser {
    fun parse(file: File): List<FontVariationAxis> {
        if (!file.isFile) return emptyList()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                parseReader(RandomAccessFileReader(raf))
            }
        } catch (_: IOException) {
            // behavior-contract: silent-result-ok: an unreadable or corrupt font file exposes no
            // variable axes and lets validation/loading report the unusable state explicitly.
            emptyList()
        }
    }

    fun parse(bytes: ByteArray): List<FontVariationAxis> = parseReader(ByteArrayFontReader(bytes))

    private fun parseReader(reader: FontByteReader): List<FontVariationAxis> {
        val length = reader.length()
        if (length < SFNT_HEADER_BYTES) return emptyList()
        val firstTag = reader.readInt32(0L) ?: return emptyList()
        val sfntOffset =
            if (firstTag == TTC_TAG) {
                resolveFirstTtcFaceOffset(reader, length) ?: return emptyList()
            } else {
                0L
            }

        val tables = readTableDirectory(reader, length, sfntOffset) ?: return emptyList()
        val fvarEntry = tables["fvar"] ?: return emptyList()
        val axisEntries = readFvarAxes(reader, length, fvarEntry)
        if (axisEntries.isEmpty()) return emptyList()

        val nameIds = axisEntries.map { it.axisNameId }.toSet()
        val axisNames = tables["name"]?.let { readNames(reader, length, it, nameIds) }.orEmpty()

        val seenTags = HashSet<String>(axisEntries.size)
        return buildList {
            for (raw in axisEntries) {
                if (!seenTags.add(raw.tag)) continue
                val label = axisNames[raw.axisNameId]?.takeIf { it.isNotBlank() } ?: fallbackAxisLabel(raw.tag)
                add(
                    FontVariationAxis(
                        tag = raw.tag,
                        name = label,
                        minValue = raw.minValue,
                        defaultValue = raw.defaultValue,
                        maxValue = raw.maxValue,
                        hidden = raw.hidden,
                    ),
                )
            }
        }
    }

    private fun resolveFirstTtcFaceOffset(reader: FontByteReader, length: Long): Long? {
        if (length < TTC_HEADER_MIN_BYTES) return null
        val numFonts = reader.readUInt32(TTC_NUM_FONTS_OFFSET) ?: return null
        if (numFonts == 0L) return null
        val faceOffset = reader.readUInt32(TTC_FACE_OFFSET) ?: return null
        return faceOffset.takeIf { it + SFNT_HEADER_BYTES <= length }
    }

    private fun readTableDirectory(
        reader: FontByteReader,
        fileLength: Long,
        sfntOffset: Long,
    ): Map<String, TableEntry>? {
        val sfntHeader = reader.readSlice(sfntOffset, SFNT_HEADER_BYTES) ?: return null
        val sfntVersion = sfntHeader.readInt32(0)
        if (sfntVersion != SFNT_TRUE_TYPE && sfntVersion != SFNT_OTTO && sfntVersion != SFNT_TRUE) {
            return null
        }
        val numTables = sfntHeader.readUInt16(SFNT_NUM_TABLES_OFFSET)
        if (numTables !in 1..MAX_TABLE_COUNT) return null
        val dirBytesSize = numTables * TABLE_RECORD_BYTES
        val dirOffset = sfntOffset + SFNT_HEADER_BYTES
        if (dirOffset + dirBytesSize > fileLength) return null

        val dirBytes = reader.readSlice(dirOffset, dirBytesSize) ?: return null
        val tables = HashMap<String, TableEntry>(numTables)
        for (i in 0 until numTables) {
            val base = i * TABLE_RECORD_BYTES
            val tag = dirBytes.readAscii(base, TAG_LENGTH)
            val offset = dirBytes.readUInt32(base + TABLE_REC_OFFSET_OFFSET)
            val tableLength = dirBytes.readUInt32(base + TABLE_REC_LENGTH_OFFSET)
            if (offset + tableLength <= fileLength) {
                tables[tag] = TableEntry(offset = offset, length = tableLength)
            }
        }
        return tables
    }

    private fun readFvarAxes(reader: FontByteReader, fileLength: Long, fvar: TableEntry): List<RawFvarAxis> {
        if (fvar.length < FVAR_HEADER_BYTES || fvar.offset + fvar.length > fileLength) {
            return emptyList()
        }
        val header = reader.readSlice(fvar.offset, FVAR_HEADER_BYTES) ?: return emptyList()
        val majorVersion = header.readUInt16(0)
        val axesArrayOffset = header.readUInt16(FVAR_OFFSET_AXES_ARRAY)
        val axisCount = header.readUInt16(FVAR_OFFSET_AXIS_COUNT)
        val axisSize = header.readUInt16(FVAR_OFFSET_AXIS_SIZE)
        if (majorVersion != FVAR_SUPPORTED_MAJOR_VERSION || axesArrayOffset < FVAR_HEADER_BYTES) {
            return emptyList()
        }
        if (axisCount !in 1..MAX_AXIS_COUNT || axisSize < FVAR_AXIS_RECORD_MIN_BYTES) {
            return emptyList()
        }
        val totalAxesBytes = axisCount.toLong() * axisSize.toLong()
        if (axesArrayOffset.toLong() + totalAxesBytes > fvar.length) return emptyList()

        val payload = reader.readSlice(fvar.offset + axesArrayOffset, totalAxesBytes.toInt()) ?: return emptyList()
        return buildList {
            for (i in 0 until axisCount) {
                val base = i * axisSize
                val tag = payload.readAscii(base, TAG_LENGTH)
                val minValue = payload.readFixed1616(base + AXIS_OFFSET_MIN)
                val defaultValue = payload.readFixed1616(base + AXIS_OFFSET_DEFAULT)
                val maxValue = payload.readFixed1616(base + AXIS_OFFSET_MAX)
                val flags = payload.readUInt16(base + AXIS_OFFSET_FLAGS)
                val axisNameId = payload.readUInt16(base + AXIS_OFFSET_NAME_ID)
                val boundsFinite = minValue.isFinite() && defaultValue.isFinite() && maxValue.isFinite()
                val boundsOrdered = boundsFinite && minValue <= defaultValue && defaultValue <= maxValue
                if (FontVariationAxis.isValidTag(tag) && boundsOrdered) {
                    add(
                        RawFvarAxis(
                            tag = tag,
                            minValue = minValue,
                            defaultValue = defaultValue,
                            maxValue = maxValue,
                            hidden = (flags and HIDDEN_AXIS_FLAG) != 0,
                            axisNameId = axisNameId,
                        ),
                    )
                }
            }
        }
    }

    private fun readNames(
        reader: FontByteReader,
        fileLength: Long,
        nameTable: TableEntry,
        targetNameIds: Set<Int>,
    ): Map<Int, String> {
        val validRange =
            nameTable.length >= NAME_HEADER_BYTES &&
                nameTable.length <= MAX_NAME_TABLE_BYTES &&
                nameTable.offset + nameTable.length <= fileLength
        if (targetNameIds.isEmpty() || !validRange) return emptyMap()

        val bytes = reader.readSlice(nameTable.offset, nameTable.length.toInt()) ?: return emptyMap()
        val count = bytes.readUInt16(NAME_COUNT_OFFSET)
        val stringOffset = bytes.readUInt16(NAME_STRING_OFFSET)
        val recordsEnd = NAME_HEADER_BYTES + count * NAME_RECORD_BYTES
        if (recordsEnd > bytes.size || stringOffset > bytes.size) return emptyMap()

        val scanner = NameTableScanner(bytes, stringOffset, targetNameIds)
        for (i in 0 until count) {
            scanner.inspectRecord(NAME_HEADER_BYTES + i * NAME_RECORD_BYTES)
        }
        return scanner.resolved
    }

    private fun nameRecordPriority(platformId: Int, encodingId: Int, languageId: Int): Int? = when (platformId) {
        PLATFORM_WINDOWS -> {
            val isUnicodeEncoding = encodingId == ENCODING_UNICODE_BMP || encodingId == ENCODING_UNICODE_FULL
            when {
                isUnicodeEncoding && languageId == LANG_WINDOWS_EN_US -> SCORE_WIN_EN_US
                isUnicodeEncoding -> SCORE_WIN_OTHER
                else -> null
            }
        }

        PLATFORM_UNICODE -> SCORE_UNICODE

        PLATFORM_MACINTOSH -> if (encodingId == 0) SCORE_MAC else null

        else -> null
    }

    private fun decodeNameString(bytes: ByteArray, offset: Int, length: Int, platformId: Int): String? =
        when (platformId) {
            PLATFORM_WINDOWS, PLATFORM_UNICODE -> String(bytes, offset, length, StandardCharsets.UTF_16BE)
            PLATFORM_MACINTOSH -> String(bytes, offset, length, StandardCharsets.ISO_8859_1)
            else -> null
        }

    private fun fallbackAxisLabel(tag: String): String = when (tag) {
        "wght" -> "Weight"
        "wdth" -> "Width"
        "opsz" -> "Optical Size"
        "slnt" -> "Slant"
        "ital" -> "Italic"
        "GRAD" -> "Grade"
        else -> tag.trim().ifEmpty { tag }
    }

    private class NameTableScanner(
        private val bytes: ByteArray,
        private val stringOffset: Int,
        private val targetNameIds: Set<Int>,
    ) {
        private val bestScores = HashMap<Int, Int>()
        val resolved = HashMap<Int, String>()

        fun inspectRecord(recordBase: Int) {
            val nameId = bytes.readUInt16(recordBase + NAME_REC_ID_OFFSET)
            val priority =
                if (nameId in targetNameIds) {
                    nameRecordPriority(
                        platformId = bytes.readUInt16(recordBase),
                        encodingId = bytes.readUInt16(recordBase + NAME_REC_ENCODING_OFFSET),
                        languageId = bytes.readUInt16(recordBase + NAME_REC_LANG_OFFSET),
                    )
                } else {
                    null
                }
            val strLen = bytes.readUInt16(recordBase + NAME_REC_LEN_OFFSET)
            val strOffset = stringOffset + bytes.readUInt16(recordBase + NAME_REC_OFFSET_OFFSET)
            val validSlice = strLen > 0 && strOffset + strLen <= bytes.size
            if (priority != null && validSlice && priority > (bestScores[nameId] ?: INVALID_NAME_SCORE)) {
                val platformId = bytes.readUInt16(recordBase)
                val decoded = decodeNameString(bytes, strOffset, strLen, platformId)?.trim().orEmpty()
                if (decoded.isNotEmpty()) {
                    bestScores[nameId] = priority
                    resolved[nameId] = decoded
                }
            }
        }
    }

    private data class TableEntry(val offset: Long, val length: Long)

    private data class RawFvarAxis(
        val tag: String,
        val minValue: Float,
        val defaultValue: Float,
        val maxValue: Float,
        val hidden: Boolean,
        val axisNameId: Int,
    )

    private interface FontByteReader {
        fun length(): Long

        fun readSlice(offset: Long, count: Int): ByteArray?

        fun readInt32(offset: Long): Int? = readSlice(offset, INT32_BYTES)?.readInt32(0)

        fun readUInt32(offset: Long): Long? = readSlice(offset, INT32_BYTES)?.readUInt32(0)
    }

    private class ByteArrayFontReader(private val data: ByteArray) : FontByteReader {
        override fun length(): Long = data.size.toLong()

        override fun readSlice(offset: Long, count: Int): ByteArray? {
            if (offset < 0L || count < 0 || offset + count > data.size.toLong()) return null
            val start = offset.toInt()
            return data.copyOfRange(start, start + count)
        }
    }

    private class RandomAccessFileReader(private val file: RandomAccessFile) : FontByteReader {
        private val cachedLength: Long = file.length()

        override fun length(): Long = cachedLength

        override fun readSlice(offset: Long, count: Int): ByteArray? {
            if (offset < 0L || count < 0 || offset + count > cachedLength) return null
            val buf = ByteArray(count)
            file.seek(offset)
            file.readFully(buf)
            return buf
        }
    }
}

private fun ByteArray.readUInt16(index: Int): Int =
    ((this[index].toInt() and BYTE_MASK) shl SHIFT_8) or (this[index + BYTE_OFFSET_1].toInt() and BYTE_MASK)

private fun ByteArray.readInt32(index: Int): Int = ((this[index].toInt() and BYTE_MASK) shl SHIFT_24) or
    ((this[index + BYTE_OFFSET_1].toInt() and BYTE_MASK) shl SHIFT_16) or
    ((this[index + BYTE_OFFSET_2].toInt() and BYTE_MASK) shl SHIFT_8) or
    (this[index + BYTE_OFFSET_3].toInt() and BYTE_MASK)

private fun ByteArray.readUInt32(index: Int): Long = readInt32(index).toLong() and UINT32_MASK

private fun ByteArray.readFixed1616(index: Int): Float = (readInt32(index).toDouble() / FIXED_16_16_SCALE).toFloat()

private fun ByteArray.readAscii(index: Int, count: Int): String = String(this, index, count, StandardCharsets.US_ASCII)

/**
 * Pure domain solver mapping declared [FontVariationAxis] metadata + user overrides into exact
 * OpenType axis coordinate maps for Compose font families, Memo text painting, and share cards.
 */
object CustomFontVariationResolver {
    val StandardWeightSlots: List<Int> = listOf(100, 200, 300, 350, 400, 500, 600, 700, 800, 900)
    val PreviewWeightSlots: List<Int> = listOf(400, 600, 700)

    fun sanitizeOverrides(axes: List<FontVariationAxis>, rawOverrides: Map<String, Float>): Map<String, Float> {
        if (axes.isEmpty() || rawOverrides.isEmpty()) return emptyMap()
        val axesByTag = axes.associateBy { it.tag }
        return rawOverrides.entries
            .asSequence()
            .filter { (tag, value) -> tag in axesByTag && value.isFinite() }
            .sortedBy { it.key }
            .associate { (tag, value) -> tag to axesByTag.getValue(tag).clamp(value) }
    }

    fun resolveVariationSettings(
        axes: List<FontVariationAxis>,
        axisOverrides: Map<String, Float>,
        targetWeight: Int,
        isItalic: Boolean,
    ): Map<String, Float> {
        if (axes.isEmpty()) return emptyMap()
        val clean = sanitizeOverrides(axes, axisOverrides)
        val hasItalAxis = axes.any { it.tag == "ital" }
        val result = LinkedHashMap<String, Float>(axes.size)
        for (axis in axes) {
            val value =
                when (axis.tag) {
                    "wght" -> {
                        val baseWeight = clean["wght"] ?: axis.defaultValue
                        val delta = (targetWeight - NORMAL_FONT_WEIGHT).toFloat()
                        axis.clamp(baseWeight + delta)
                    }

                    "ital" -> clean["ital"] ?: if (isItalic) axis.maxValue else axis.minValue

                    "slnt" -> {
                        if (isItalic && !hasItalAxis) {
                            if (axis.minValue < 0f) axis.minValue else axis.maxValue
                        } else {
                            clean["slnt"] ?: axis.defaultValue
                        }
                    }

                    else -> clean[axis.tag] ?: axis.defaultValue
                }
            result[axis.tag] = value
        }
        return result
    }

    fun supportsItalicAxis(axes: List<FontVariationAxis>): Boolean = axes.any { it.tag == "ital" || it.tag == "slnt" }
}
