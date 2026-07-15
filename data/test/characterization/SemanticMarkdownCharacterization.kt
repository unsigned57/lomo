package com.lomo.data.characterization

import com.lomo.data.util.MemoTextProcessor
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * UI-neutral semantic counters for open Markdown fixtures (stage-0 characterization).
 *
 * Counts tags/attachments/links/checkboxes from storage-visible content analysis only —
 * not Compose styles or formal RenderDocument IR.
 */
@Serializable
data class SemanticMarkdownCharacterizationV1(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    val fixture: String,
    @SerialName("tag_count") val tagCount: Int,
    @SerialName("tags") val tags: List<String>,
    @SerialName("attachment_count") val attachmentCount: Int,
    @SerialName("attachments") val attachments: List<String>,
    @SerialName("checkbox_checked") val checkboxChecked: Int,
    @SerialName("checkbox_unchecked") val checkboxUnchecked: Int,
    @SerialName("http_link_count") val httpLinkCount: Int,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

internal object SemanticMarkdownCharacterization {
    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
        }

    private val processor = MemoTextProcessor()

    fun encode(document: SemanticMarkdownCharacterizationV1): String =
        json.encodeToString(SemanticMarkdownCharacterizationV1.serializer(), document) + "\n"

    fun decode(text: String): SemanticMarkdownCharacterizationV1 =
        json.decodeFromString(SemanticMarkdownCharacterizationV1.serializer(), text)

    fun characterize(fixtureFile: Path): SemanticMarkdownCharacterizationV1 {
        val name = fixtureFile.fileName.toString()
        val bytes = Files.readAllBytes(fixtureFile)
        val text =
            try {
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                return SemanticMarkdownCharacterizationV1(
                    fixture = name,
                    tagCount = 0,
                    tags = emptyList(),
                    attachmentCount = 0,
                    attachments = emptyList(),
                    checkboxChecked = 0,
                    checkboxUnchecked = 0,
                    httpLinkCount = 0,
                )
            }
        val tags = processor.extractTags(text).sorted()
        val attachments = processor.extractLocalAttachmentPaths(text).sorted()
        val checked = Regex("""\[x\]""", RegexOption.IGNORE_CASE).findAll(text).count()
        val unchecked = Regex("""\[ \]""").findAll(text).count()
        val httpLinks = Regex("""https?://[^\s)]+""").findAll(text).count()
        return SemanticMarkdownCharacterizationV1(
            fixture = name,
            tagCount = tags.size,
            tags = tags,
            attachmentCount = attachments.size,
            attachments = attachments,
            checkboxChecked = checked,
            checkboxUnchecked = unchecked,
            httpLinkCount = httpLinks,
        )
    }
}
