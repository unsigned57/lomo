package com.lomo.data.characterization

import com.lomo.data.parser.MarkdownParser
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.usecase.MemoIdentityPolicy
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * UI-neutral storage characterization for open Markdown fixtures.
 *
 * Intentionally omits Compose/Room/AST types and absolute epoch timestamps.
 */
@Serializable
data class StorageMarkdownCharacterizationV1(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    val fixture: String,
    @SerialName("filename_stem") val filenameStem: String,
    @SerialName("byte_length") val byteLength: Long,
    val outcome: String,
    @SerialName("error_class") val errorClass: String? = null,
    val memos: List<StorageMemoCharacterizationV1> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

@Serializable
data class StorageMemoCharacterizationV1(
    val id: String,
    val content: String,
    val tags: List<String>,
    val attachments: List<String>,
    @SerialName("start_line") val startLine: Int,
    @SerialName("end_line") val endLine: Int,
)

internal object StorageMarkdownCharacterization {
    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            ignoreUnknownKeys = false
        }

    private val parser = MarkdownParser(MemoTextProcessor(), MemoIdentityPolicy())

    /**
     * Stable date stems so characterization does not depend on host clock.
     * Thino-style uses compact yyyyMMdd; Lomo samples use dashed dates.
     */
    val filenameStemByFixture: Map<String, String> =
        mapOf(
            "lomo-basic.md" to "2024-06-01",
            "thino-basic.md" to "20240602",
            "plain.md" to "plain-note",
            "empty.md" to "2024-06-03",
            "bom-newline.md" to "2024-06-04",
            "long-line.md" to "2024-06-05",
            "duplicate-timestamps.md" to "2024-06-06",
            "dst-edge.md" to "2024-03-10",
            "cjk-emoji.md" to "2024-06-07",
            "gfm-extensions.md" to "2024-06-08",
            "invalid-utf8.bin" to "2024-06-09",
        )

    fun encode(document: StorageMarkdownCharacterizationV1): String =
        json.encodeToString(StorageMarkdownCharacterizationV1.serializer(), document) + "\n"

    fun decode(text: String): StorageMarkdownCharacterizationV1 =
        json.decodeFromString(StorageMarkdownCharacterizationV1.serializer(), text)

    fun characterize(fixtureFile: Path): StorageMarkdownCharacterizationV1 {
        val fixtureName = fixtureFile.fileName.toString()
        val filenameStem =
            filenameStemByFixture[fixtureName]
                ?: error("missing filename stem mapping for $fixtureName")
        val bytes = Files.readAllBytes(fixtureFile)
        return try {
            val text = decodeStrictUtf8(bytes)
            val document = parser.parseDocument(text, filenameStem, fallbackTimestampMillis = 0L)
            StorageMarkdownCharacterizationV1(
                fixture = fixtureName,
                filenameStem = filenameStem,
                byteLength = bytes.size.toLong(),
                outcome = "ok",
                errorClass = null,
                memos =
                    document.blocks.map { block ->
                        StorageMemoCharacterizationV1(
                            id = block.memo.id,
                            content = block.memo.content,
                            tags = block.memo.tags,
                            attachments = block.memo.imageUrls,
                            startLine = block.span.startLine,
                            endLine = block.span.endLine,
                        )
                    },
            )
        } catch (error: CharacterCodingException) {
            StorageMarkdownCharacterizationV1(
                fixture = fixtureName,
                filenameStem = filenameStem,
                byteLength = bytes.size.toLong(),
                outcome = "error",
                errorClass = "utf8_decode",
            )
        } catch (error: Exception) {
            StorageMarkdownCharacterizationV1(
                fixture = fixtureName,
                filenameStem = filenameStem,
                byteLength = bytes.size.toLong(),
                outcome = "error",
                errorClass = error::class.simpleName ?: "unknown",
            )
        }
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String {
        val decoder: CharsetDecoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}
