package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.math.roundToInt

/*
 * Behavior Contract:
 * - Unit under test: OpenTypeVariableFontParser and CustomFontVariationResolver.
 * - Owning layer: domain.
 * - Priority tier: P0.
 * - Capability: extract valid OpenType fvar variable axes with localized/fallback display names,
 *   ignore corrupted/hidden/non-variable tables, and deterministically resolve variation axis
 *   values across Compose weights and italic styles.
 *
 * Scenarios:
 * - Given an SFNT binary with fvar and name tables (or a TTC collection wrapper), when parsed,
 *   then visible axes keep exact min/default/max bounds, order, and resolved names.
 * - Given a hidden axis (flag 0x0001), malformed fvar bounds, or a static SFNT without fvar,
 *   when parsed, then hidden flags are marked, malformed entries are dropped safely, and static
 *   fonts yield an empty axis list.
 * - Given user axis overrides and target Compose weights/italic states, when variation settings are
 *   resolved, then wght shifts monotonically around user baseline within [min, max], italic maps
 *   through ital/slnt, and out-of-range/unknown/non-finite overrides are rejected or clamped.
 *
 * Observable outcomes: List<FontVariationAxis>, sanitized override maps, resolved axis value maps.
 *
 * TDD proof: Fails compilation before OpenTypeVariableFontParser / FontVariationAxis /
 * CustomFontVariationResolver are implemented.
 *
 * Excludes: Android Typeface rasterization and Compose UI rendering.
 */
class OpenTypeVariableFontParserTest : DomainFunSpec() {
    init {
        test("given sfnt with fvar and name tables when parsed then axes and bounds match spec") {
            val fontBytes =
                buildSyntheticVariableFont(
                    axes =
                    listOf(
                        SyntheticAxis("wght", 100f, 400f, 900f, flags = 0, nameId = 256, label = "Weight"),
                        SyntheticAxis("wdth", 75f, 100f, 125f, flags = 0, nameId = 257, label = "Width"),
                        SyntheticAxis("opsz", 8f, 14f, 144f, flags = 1, nameId = 258, label = "Optical Size"),
                    ),
                )

            val parsed = OpenTypeVariableFontParser.parse(fontBytes)

            parsed shouldContainExactly
                listOf(
                    FontVariationAxis(
                        tag = "wght",
                        name = "Weight",
                        minValue = 100f,
                        defaultValue = 400f,
                        maxValue = 900f,
                        hidden = false,
                    ),
                    FontVariationAxis(
                        tag = "wdth",
                        name = "Width",
                        minValue = 75f,
                        defaultValue = 100f,
                        maxValue = 125f,
                        hidden = false,
                    ),
                    FontVariationAxis(
                        tag = "opsz",
                        name = "Optical Size",
                        minValue = 8f,
                        defaultValue = 14f,
                        maxValue = 144f,
                        hidden = true,
                    ),
                )
        }

        test("given ttc collection font when parsed from file then first face axes are extracted") {
            val sfnt =
                buildSyntheticVariableFont(
                    axes = listOf(SyntheticAxis("wght", 200f, 400f, 700f, flags = 0, nameId = 256, label = "Weight")),
                )
            val ttc = wrapInTrueTypeCollection(sfnt)
            val tempFile = Files.createTempFile("lomo-var-ttc", ".ttf").toFile()
            try {
                tempFile.writeBytes(ttc)

                val parsed = OpenTypeVariableFontParser.parse(tempFile)

                parsed shouldContainExactly
                    listOf(
                        FontVariationAxis(
                            tag = "wght",
                            name = "Weight",
                            minValue = 200f,
                            defaultValue = 400f,
                            maxValue = 700f,
                            hidden = false,
                        ),
                    )
            } finally {
                tempFile.delete()
            }
        }

        test("given static font or malformed data when parsed then returns empty list safely") {
            val staticFont = buildSyntheticVariableFont(axes = emptyList())
            val invalidBoundsFont =
                buildSyntheticVariableFont(
                    axes = listOf(SyntheticAxis("wght", 900f, 400f, 100f, flags = 0, nameId = 256, label = "Bad")),
                )

            OpenTypeVariableFontParser.parse(staticFont).shouldBeEmpty()
            OpenTypeVariableFontParser.parse(invalidBoundsFont).shouldBeEmpty()
            OpenTypeVariableFontParser.parse(byteArrayOf(1, 2, 3)).shouldBeEmpty()
        }

        test("given wght and slnt axes when resolving variation settings then weight shifts monotonically and clamps") {
            val axes =
                listOf(
                    FontVariationAxis("wght", "Weight", 200f, 400f, 700f),
                    FontVariationAxis("wdth", "Width", 75f, 100f, 125f),
                    FontVariationAxis("slnt", "Slant", -12f, 0f, 0f),
                )
            val overrides = mapOf("wght" to 500f, "wdth" to 200f, "xxxx" to 10f, "GRAD" to Float.NaN)

            val clean = CustomFontVariationResolver.sanitizeOverrides(axes, overrides)
            clean shouldBe mapOf("wght" to 500f, "wdth" to 125f)

            val normalBody =
                CustomFontVariationResolver.resolveVariationSettings(
                    axes = axes,
                    axisOverrides = clean,
                    targetWeight = 400,
                    isItalic = false,
                )
            val boldHeading =
                CustomFontVariationResolver.resolveVariationSettings(
                    axes = axes,
                    axisOverrides = clean,
                    targetWeight = 700,
                    isItalic = false,
                )
            val italicBody =
                CustomFontVariationResolver.resolveVariationSettings(
                    axes = axes,
                    axisOverrides = clean,
                    targetWeight = 400,
                    isItalic = true,
                )

            normalBody shouldBe mapOf("wght" to 500f, "wdth" to 125f, "slnt" to 0f)
            boldHeading shouldBe mapOf("wght" to 700f, "wdth" to 125f, "slnt" to 0f)
            italicBody shouldBe mapOf("wght" to 500f, "wdth" to 125f, "slnt" to -12f)
        }
    }

    private data class SyntheticAxis(
        val tag: String,
        val min: Float,
        val default: Float,
        val max: Float,
        val flags: Int,
        val nameId: Int,
        val label: String,
    )

    private fun buildSyntheticVariableFont(axes: List<SyntheticAxis>): ByteArray {
        val tables = mutableListOf<Pair<String, ByteArray>>()
        if (axes.isNotEmpty()) {
            tables += "fvar" to buildFvarTable(axes)
            tables += "name" to buildNameTable(axes.map { it.nameId to it.label })
        } else {
            tables += "head" to ByteArray(54)
        }

        val numTables = tables.size
        val headerSize = 12 + numTables * 16
        var offset = headerSize
        val dir = ByteArrayOutputStream()
        val headerOut = DataOutputStream(dir)
        headerOut.writeInt(0x00010000)
        headerOut.writeShort(numTables)
        headerOut.writeShort(16)
        headerOut.writeShort(0)
        headerOut.writeShort(0)

        for ((tag, payload) in tables) {
            val paddedLen = (payload.size + 3) and 3.inv()
            headerOut.write(tag.toByteArray(StandardCharsets.US_ASCII))
            headerOut.writeInt(0)
            headerOut.writeInt(offset)
            headerOut.writeInt(payload.size)
            offset += paddedLen
        }
        for ((_, payload) in tables) {
            headerOut.write(payload)
            val pad = ((payload.size + 3) and 3.inv()) - payload.size
            repeat(pad) { headerOut.writeByte(0) }
        }
        return dir.toByteArray()
    }

    private fun buildFvarTable(axes: List<SyntheticAxis>): ByteArray {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.writeShort(1) // majorVersion
        out.writeShort(0) // minorVersion
        out.writeShort(16) // axesArrayOffset
        out.writeShort(2) // reserved
        out.writeShort(axes.size)
        out.writeShort(20) // axisSize
        out.writeShort(0) // instanceCount
        out.writeShort(4 + axes.size * 4) // instanceSize
        for (axis in axes) {
            out.write(axis.tag.toByteArray(StandardCharsets.US_ASCII))
            out.writeInt(toFixed1616(axis.min))
            out.writeInt(toFixed1616(axis.default))
            out.writeInt(toFixed1616(axis.max))
            out.writeShort(axis.flags)
            out.writeShort(axis.nameId)
        }
        return baos.toByteArray()
    }

    private fun buildNameTable(entries: List<Pair<Int, String>>): ByteArray {
        val stringBuf = ByteArrayOutputStream()
        val records = ByteArrayOutputStream()
        val recOut = DataOutputStream(records)
        for ((nameId, text) in entries) {
            val encoded = text.toByteArray(StandardCharsets.UTF_16BE)
            recOut.writeShort(3) // platformID: Windows
            recOut.writeShort(1) // encodingID: Unicode BMP
            recOut.writeShort(0x0409) // en-US
            recOut.writeShort(nameId)
            recOut.writeShort(encoded.size)
            recOut.writeShort(stringBuf.size())
            stringBuf.write(encoded)
        }
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.writeShort(0) // format
        out.writeShort(entries.size)
        out.writeShort(6 + entries.size * 12) // storageOffset
        out.write(records.toByteArray())
        out.write(stringBuf.toByteArray())
        return baos.toByteArray()
    }

    private fun wrapInTrueTypeCollection(sfnt: ByteArray): ByteArray {
        val ttcHeaderSize = 12 + 4
        val sfntOffset = ttcHeaderSize
        val patchedSfnt = sfnt.copyOf()
        // Shift table offsets in patchedSfnt directory by sfntOffset
        val numTables = ((patchedSfnt[4].toInt() and 0xFF) shl 8) or (patchedSfnt[5].toInt() and 0xFF)
        for (i in 0 until numTables) {
            val pos = 12 + i * 16 + 8
            val oldOffset =
                ((patchedSfnt[pos].toInt() and 0xFF) shl 24) or
                    ((patchedSfnt[pos + 1].toInt() and 0xFF) shl 16) or
                    ((patchedSfnt[pos + 2].toInt() and 0xFF) shl 8) or
                    (patchedSfnt[pos + 3].toInt() and 0xFF)
            val newOffset = oldOffset + sfntOffset
            patchedSfnt[pos] = ((newOffset ushr 24) and 0xFF).toByte()
            patchedSfnt[pos + 1] = ((newOffset ushr 16) and 0xFF).toByte()
            patchedSfnt[pos + 2] = ((newOffset ushr 8) and 0xFF).toByte()
            patchedSfnt[pos + 3] = (newOffset and 0xFF).toByte()
        }
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.write("ttcf".toByteArray(StandardCharsets.US_ASCII))
        out.writeInt(0x00010000)
        out.writeInt(1)
        out.writeInt(sfntOffset)
        out.write(patchedSfnt)
        return baos.toByteArray()
    }

    private fun toFixed1616(value: Float): Int = (value * 65536f).roundToInt()
}
