package com.lomo.data.util

import com.lomo.data.memo.MemoContentHashPolicy
import com.lomo.domain.model.StorageTimestampFormats
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

class MemoTextProcessor
    @Inject
    constructor() {
        /**
         * Finds the start and end line indices of a memo block in a list of lines.
         * @param lines The file content split by lines.
         * @param rawContent The raw content of the memo (expected to start with timestamp).
         * @param timestamp The timestamp of the memo (fallback for finding block).
         * @return Pair of (startIndex, endIndex), or (-1, -1) if not found.
         */
        fun findMemoBlock(
            lines: List<String>,
            rawContent: String,
            timestamp: Long,
            memoId: String? = null,
        ): Pair<Int, Int> =
            memoId
                ?.let { findMemoBlockByMemoId(lines, it) }
                ?: findMemoBlockByRawContent(lines, rawContent)
                ?: findMemoBlockByFirstLine(lines, rawContent)
                ?: findMemoBlockByTimestamp(lines, timestamp)
                ?: MEMO_BLOCK_NOT_FOUND

        /**
         * Replaces an existing memo block with new content. Returns true if successful, false if block
         * not found.
         *
         * @param timestampStr The pre-formatted timestamp string to use (e.g. "HH:mm" or "HH:mm:ss").
         */
        fun replaceMemoBlock(
            lines: MutableList<String>,
            rawContent: String,
            timestamp: Long,
            newRawContent: String,
            timestampStr: String,
            memoId: String? = null,
        ): Boolean {
            val (startIndex, endIndex) = findMemoBlock(lines, rawContent, timestamp, memoId)
            if (startIndex == -1 || endIndex < startIndex) {
                return false
            }

            val contentLines = newRawContent.lines()
            val newMemoLines = mutableListOf<String>()
            if (contentLines.isNotEmpty()) {
                newMemoLines.add("- $timestampStr ${contentLines.first()}")
                for (index in 1 until contentLines.size) {
                    newMemoLines.add(contentLines[index])
                }
            } else {
                newMemoLines.add("- $timestampStr")
            }

            lines.subList(startIndex, endIndex + 1).clear()
            lines.addAll(startIndex, newMemoLines)
            return true
        }

        /** Removes a memo block. Returns true if successful. */
        fun removeMemoBlock(
            lines: MutableList<String>,
            rawContent: String,
            timestamp: Long,
            memoId: String? = null,
        ): Boolean {
            val (startIndex, endIndex) = findMemoBlock(lines, rawContent, timestamp, memoId)
            if (startIndex == -1 || endIndex < startIndex) {
                return false
            }

            lines.subList(startIndex, endIndex + 1).clear()
            return true
        }

        fun extractTags(content: String): List<String> {
            val tags =
                TAG_PATTERN.findAll(content).mapNotNull { match ->
                    match.groupValues
                        .getOrNull(1)
                        ?.trimEnd('/')
                        .takeUnless { it.isNullOrEmpty() }
                }
            return tags.distinct().toList()
        }

        fun extractImages(content: String): List<String> {
            val markdownImages = MD_IMAGE_PATTERN.findAll(content).mapNotNull { it.groupValues.getOrNull(1) }
            val wikiImages = WIKI_IMAGE_PATTERN.findAll(content).mapNotNull { it.groupValues.getOrNull(1) }
            return (markdownImages + wikiImages).toList()
        }

        fun extractAudioLinks(content: String): List<String> =
            AUDIO_LINK_PATTERN
                .findAll(content)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .toList()

        fun extractLocalAttachmentPaths(content: String): List<String> =
            (extractImages(content) + extractAudioLinks(content))
                .map(String::trim)
                .filter { path ->
                    path.isNotEmpty() &&
                        !path.startsWith("http://", ignoreCase = true) &&
                        !path.startsWith("https://", ignoreCase = true)
                }.distinct()

        fun toggleCheckbox(
            content: String,
            lineIndex: Int,
            checked: Boolean,
        ): String {
            require(lineIndex >= 0) { "lineIndex must be non-negative, was: $lineIndex" }

            val pattern = if (checked) "- [ ]" else "- [x]"
            val replacement = if (checked) "- [x]" else "- [ ]"
            val builder = StringBuilder(content.length + CHECKBOX_BUFFER_PADDING)
            var currentIndex = 0
            var hadAnyLine = false
            var toggleState = CheckboxToggleState.NOT_FOUND

            content.lineSequence().forEach { line ->
                if (hadAnyLine) {
                    builder.append('\n')
                }

                val resolvedLine =
                    if (currentIndex == lineIndex) {
                        if (line.contains(pattern)) {
                            toggleState = CheckboxToggleState.REPLACED
                            line.replaceFirst(pattern, replacement)
                        } else {
                            toggleState = CheckboxToggleState.PATTERN_MISSING
                            line
                        }
                    } else {
                        line
                    }
                builder.append(resolvedLine)

                hadAnyLine = true
                currentIndex++
            }

            return when (toggleState) {
                CheckboxToggleState.NOT_FOUND -> {
                    timber.log.Timber.w("toggleCheckbox: lineIndex $lineIndex out of bounds (0..${currentIndex - 1})")
                    content
                }

                CheckboxToggleState.PATTERN_MISSING -> {
                    timber.log.Timber.w("toggleCheckbox: expected pattern '$pattern' not found at line $lineIndex")
                    content
                }

                CheckboxToggleState.REPLACED -> builder.toString()
            }
        }

        private fun findMemoBlockByMemoId(
            lines: List<String>,
            memoId: String,
        ): Pair<Int, Int>? {
            val parsedId = parseMemoId(memoId) ?: return null
            val matches =
                extractMemoBlocks(lines).filter { block ->
                    block.timePart == parsedId.timePart &&
                        contentHash(block.content) == parsedId.contentHash
                }

            return matches.getOrNull(parsedId.collisionIndex)?.let { block ->
                block.startIndex to block.endIndex
            }
        }

        private fun extractMemoBlocks(lines: List<String>): List<MemoBlock> {
            val blocks = mutableListOf<MemoBlock>()
            var index = 0
            while (index < lines.size) {
                val header = StorageTimestampFormats.parseMemoHeaderLine(lines[index])
                if (header == null) {
                    index++
                    continue
                }

                val start = index
                var end = index
                index++
                while (index < lines.size && !isMemoHeaderLine(lines[index])) {
                    end = index
                    index++
                }

                val contentBuilder = StringBuilder(header.contentPart)
                for (lineIndex in (start + 1)..end) {
                    if (lineIndex !in lines.indices) {
                        continue
                    }
                    val line = lines[lineIndex]
                    if (contentBuilder.isEmpty()) {
                        contentBuilder.append(line)
                    } else {
                        contentBuilder.append("\n").append(line)
                    }
                }

                blocks.add(
                    MemoBlock(
                        startIndex = start,
                        endIndex = end,
                        timePart = header.timePart,
                        content = contentBuilder.toString().trim(),
                    ),
                )
            }
            return blocks
        }

        private fun parseMemoId(memoId: String): ParsedMemoId? {
            val parts = memoId.trim().takeIf(String::isNotEmpty)?.split(MEMO_ID_SEPARATOR).orEmpty()
            val tail = parts.lastOrNull()
            val collisionIndex = tail?.takeIf { it.all(Char::isDigit) }?.toIntOrNull() ?: 0
            val coreParts = if (collisionIndex > 0) parts.dropLast(1) else parts
            val resolvedContentHash = coreParts.lastOrNull()?.takeIf(::isValidMemoContentHash)
            val resolvedTimePart = resolvedContentHash?.let { resolveMemoIdTimePart(coreParts.dropLast(1)) }
            val isValid =
                parts.size >= MIN_MEMO_ID_PARTS &&
                    coreParts.size >= MIN_MEMO_ID_PARTS &&
                    resolvedContentHash != null &&
                    resolvedTimePart != null

            return if (isValid) {
                ParsedMemoId(
                    timePart = resolvedTimePart.orEmpty(),
                    contentHash = resolvedContentHash.orEmpty(),
                    collisionIndex = collisionIndex,
                )
            } else {
                null
            }
        }

        private data class ParsedMemoId(
            val timePart: String,
            val contentHash: String,
            val collisionIndex: Int,
        )

        private data class MemoBlock(
            val startIndex: Int,
            val endIndex: Int,
            val timePart: String,
            val content: String,
        )

        private enum class CheckboxToggleState {
            NOT_FOUND,
            PATTERN_MISSING,
            REPLACED,
        }

        companion object {
            private val TAG_PATTERN = Regex("""(?:^|\s)#([\p{L}\p{N}_][\p{L}\p{N}_/]*)""")
            private val MD_IMAGE_PATTERN = Regex("""!\[.*?]\((.*?)\)""")
            private val WIKI_IMAGE_PATTERN = Regex("""!\[\[(.*?)]]""")
            private val AUDIO_LINK_PATTERN =
                Regex(
                    """(?<!!)\[[^\]]*]\((.+?\.(?:m4a|mp3|ogg|wav|aac))\)""",
                    RegexOption.IGNORE_CASE,
                )
        }
    }

private const val CHECKBOX_BUFFER_PADDING = 8
private const val MEMO_ID_SEPARATOR = '_'
private const val MIN_MEMO_ID_PARTS = 3
private val MEMO_BLOCK_NOT_FOUND = -1 to -1
private val MEMO_CONTENT_HASH_REGEX = Regex("^[0-9a-f]+$")

private fun findMemoBlockByRawContent(
    lines: List<String>,
    rawContent: String,
): Pair<Int, Int>? {
    val rawLines = rawContent.lines().map { it.trimEnd() }.toMutableList()
    while (rawLines.isNotEmpty() && rawLines.last().isEmpty()) {
        rawLines.removeAt(rawLines.lastIndex)
    }
    val startIndex =
        if (rawLines.isEmpty() || rawLines.size > lines.size) {
            null
        } else {
            val targetSize = rawLines.size
            (0..(lines.size - targetSize)).firstOrNull { candidateStart ->
                rawLines.indices.all { offset ->
                    lines[candidateStart + offset].trimEnd() == rawLines[offset]
                }
            }
        }
    return startIndex?.let { it to findBlockEndIndex(lines, it) }
}

private fun findMemoBlockByFirstLine(
    lines: List<String>,
    rawContent: String,
): Pair<Int, Int>? {
    val contentStartLine = rawContent.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return null
    val startIndex = lines.indexOfFirst { it.trim() == contentStartLine }
    return startIndex.takeIf { it >= 0 }?.let { it to findBlockEndIndex(lines, it) }
}

private fun findMemoBlockByTimestamp(
    lines: List<String>,
    timestamp: Long,
): Pair<Int, Int>? {
    val zone = ZoneId.systemDefault()
    return StorageTimestampFormats.supportedPatterns
        .asSequence()
        .map { format ->
            StorageTimestampFormats
                .formatter(format)
                .withZone(zone)
                .format(Instant.ofEpochMilli(timestamp))
        }.mapNotNull { timestampPart ->
            lines.indexOfFirst { line ->
                val trimmed = line.trim()
                trimmed == "- $timestampPart" || trimmed.startsWith("- $timestampPart ")
            }.takeIf { it >= 0 }?.let { it to findBlockEndIndex(lines, it) }
        }.firstOrNull()
}

private fun resolveMemoIdTimePart(dateAndTimeParts: List<String>): String? {
    for (start in 1 until dateAndTimeParts.size) {
        val candidate =
            dateAndTimeParts
                .subList(start, dateAndTimeParts.size)
                .joinToString(MEMO_ID_SEPARATOR.toString())
        if (StorageTimestampFormats.parseOrNull(candidate) != null) {
            return candidate
        }
    }
    return null
}

private fun isValidMemoContentHash(contentHash: String): Boolean = MEMO_CONTENT_HASH_REGEX.matches(contentHash)

private fun findBlockEndIndex(
    lines: List<String>,
    startIndex: Int,
): Int {
    var endIndex = startIndex
    for (index in (startIndex + 1) until lines.size) {
        if (isMemoHeaderLine(lines[index])) {
            break
        }
        endIndex = index
    }
    return endIndex
}

private fun isMemoHeaderLine(line: String): Boolean = StorageTimestampFormats.parseMemoHeaderLine(line) != null

private fun contentHash(content: String): String = MemoContentHashPolicy.hashHex(content)
