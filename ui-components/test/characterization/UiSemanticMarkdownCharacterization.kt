package com.lomo.ui.characterization

import com.lomo.ui.component.markdown.MarkdownSemanticBlock
import com.lomo.ui.component.markdown.MarkdownSemanticInline
import com.lomo.ui.component.markdown.parseMarkdownSemanticDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Characterization of the real UI semantic parser (`parseMarkdownSemanticDocument`).
 *
 * Locks block-kind counts and selected plain-text fingerprints for open Markdown fixtures —
 * not Compose trees or RenderDocument IR.
 */
@Serializable
data class UiSemanticMarkdownCharacterizationV1(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    val fixture: String,
    @SerialName("block_count") val blockCount: Int,
    @SerialName("block_kinds") val blockKinds: List<String>,
    @SerialName("heading_count") val headingCount: Int,
    @SerialName("list_count") val listCount: Int,
    @SerialName("table_count") val tableCount: Int,
    @SerialName("code_block_count") val codeBlockCount: Int,
    @SerialName("quote_count") val quoteCount: Int,
    @SerialName("link_count") val linkCount: Int,
    @SerialName("image_count") val imageCount: Int,
    @SerialName("task_checked") val taskChecked: Int,
    @SerialName("task_unchecked") val taskUnchecked: Int,
    @SerialName("plain_text_fingerprint") val plainTextFingerprint: String,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

internal object UiSemanticMarkdownCharacterization {
    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
        }

    fun encode(document: UiSemanticMarkdownCharacterizationV1): String =
        json.encodeToString(UiSemanticMarkdownCharacterizationV1.serializer(), document) + "\n"

    fun decode(text: String): UiSemanticMarkdownCharacterizationV1 =
        json.decodeFromString(UiSemanticMarkdownCharacterizationV1.serializer(), text)

    fun characterize(fixtureFile: Path): UiSemanticMarkdownCharacterizationV1 {
        val name = fixtureFile.fileName.toString()
        val text = Files.readString(fixtureFile)
        val document = parseMarkdownSemanticDocument(text)
        val kinds = document.blocks.map { it.kindName() }
        var linkCount = 0
        var imageCount = 0
        var taskChecked = 0
        var taskUnchecked = 0
        for (block in document.blocks) {
            when (block) {
                is MarkdownSemanticBlock.ListBlock -> {
                    for (item in block.items) {
                        when (item.checked) {
                            true -> taskChecked++
                            false -> taskUnchecked++
                            null -> Unit
                        }
                        countInlines(item.blocks.flatMap { it.collectInlines() }) { link, image ->
                            linkCount += link
                            imageCount += image
                        }
                    }
                }
                else ->
                    countInlines(block.collectInlines()) { link, image ->
                        linkCount += link
                        imageCount += image
                    }
            }
        }
        val plain = document.blocks.joinToString("\n") { it.plainText }
        return UiSemanticMarkdownCharacterizationV1(
            fixture = name,
            blockCount = document.blocks.size,
            blockKinds = kinds,
            headingCount = kinds.count { it == "heading" },
            listCount = kinds.count { it == "list" },
            tableCount = kinds.count { it == "table" },
            codeBlockCount = kinds.count { it == "code_block" },
            quoteCount = kinds.count { it == "quote" },
            linkCount = linkCount,
            imageCount = imageCount,
            taskChecked = taskChecked,
            taskUnchecked = taskUnchecked,
            plainTextFingerprint = sha256Hex(plain),
        )
    }

    private fun MarkdownSemanticBlock.kindName(): String =
        when (this) {
            is MarkdownSemanticBlock.Paragraph -> "paragraph"
            is MarkdownSemanticBlock.Heading -> "heading"
            is MarkdownSemanticBlock.BlockQuote -> "quote"
            is MarkdownSemanticBlock.ListBlock -> "list"
            is MarkdownSemanticBlock.CodeBlock -> "code_block"
            is MarkdownSemanticBlock.ThematicBreak -> "thematic_break"
            is MarkdownSemanticBlock.Table -> "table"
            is MarkdownSemanticBlock.HtmlBlock -> "html"
        }

    private fun MarkdownSemanticBlock.collectInlines(): List<MarkdownSemanticInline> =
        when (this) {
            is MarkdownSemanticBlock.Paragraph -> inlines
            is MarkdownSemanticBlock.Heading -> inlines
            is MarkdownSemanticBlock.BlockQuote -> blocks.flatMap { it.collectInlines() }
            is MarkdownSemanticBlock.ListBlock -> items.flatMap { item -> item.blocks.flatMap { it.collectInlines() } }
            is MarkdownSemanticBlock.Table ->
                (header + rows.flatten()).flatMap { cell -> cell.inlines }
            is MarkdownSemanticBlock.CodeBlock,
            is MarkdownSemanticBlock.ThematicBreak,
            is MarkdownSemanticBlock.HtmlBlock,
            -> emptyList()
        }

    private fun countInlines(
        inlines: List<MarkdownSemanticInline>,
        sink: (links: Int, images: Int) -> Unit,
    ) {
        var links = 0
        var images = 0
        fun walk(list: List<MarkdownSemanticInline>) {
            for (inline in list) {
                when (inline) {
                    is MarkdownSemanticInline.Link -> {
                        links++
                        walk(inline.inlines)
                    }
                    is MarkdownSemanticInline.Image -> images++
                    is MarkdownSemanticInline.Strong -> walk(inline.inlines)
                    is MarkdownSemanticInline.Emphasis -> walk(inline.inlines)
                    is MarkdownSemanticInline.Strikethrough -> walk(inline.inlines)
                    is MarkdownSemanticInline.Highlight -> walk(inline.inlines)
                    is MarkdownSemanticInline.Text,
                    is MarkdownSemanticInline.Code,
                    is MarkdownSemanticInline.SoftBreak,
                    is MarkdownSemanticInline.HardBreak,
                    is MarkdownSemanticInline.HtmlInline,
                    -> Unit
                }
            }
        }
        walk(inlines)
        sink(links, images)
    }

    private fun sha256Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }
}
