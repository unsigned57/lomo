package com.lomo.data.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class MemoTextProcessor
    @Inject
    constructor() {
        companion object {
            // Permissive regex: Matches HH:mm OR HH:mm:ss
            private val MEMO_BLOCK_END = Regex("^\\s*-\\s+\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s|$).*")
            private val MEMO_BLOCK_HEADER = Regex("^\\s*-\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?)(?:\\s+(.*))?$")
            private val MEMO_ID_PATTERN = Regex("^(.*)_(\\d{1,2}:\\d{2}(?::\\d{2})?)_([0-9a-f]+)(?:_(\\d+))?$")
            private val TAG_PATTERN =
                java.util.regex.Pattern
                    .compile("(?:^|\\s)#([\\p{L}\\p{N}_][\\p{L}\\p{N}_/]*)")
            private val MD_IMAGE_PATTERN =
                java.util.regex.Pattern
                    .compile("!\\[.*?\\]\\((.*?)\\)")
            private val WIKI_IMAGE_PATTERN =
                java.util.regex.Pattern
                    .compile("!\\[\\[(.*?)\\]\\]")
        }

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
        ): Pair<Int, Int> {
            memoId?.let { id ->
                findMemoBlockByMemoId(lines, id)?.let { return it }
            }

            // 1. Try exact raw block match first.
            val rawLines = rawContent.lines().map { it.trimEnd() }.toMutableList()
            while (rawLines.isNotEmpty() && rawLines.last().isEmpty()) {
                rawLines.removeAt(rawLines.lastIndex)
            }
            if (rawLines.isNotEmpty()) {
                val targetSize = rawLines.size
                for (i in 0..(lines.size - targetSize).coerceAtLeast(0)) {
                    var matched = true
                    for (k in rawLines.indices) {
                        if (lines[i + k].trimEnd() != rawLines[k]) {
                            matched = false
                            break
                        }
                    }
                    if (matched) return i to findBlockEndIndex(lines, i)
                }
            }

            // 2. Fallback: first line match.
            val contentStartLine = rawContent.lines().firstOrNull { it.isNotBlank() } ?: return -1 to -1
            val cleanContentStart = contentStartLine.trim()
            for (i in lines.indices) {
                if (lines[i].trim() == cleanContentStart) {
                    var endIndex = i
                    endIndex = findBlockEndIndex(lines, i)
                    return i to endIndex
                }
            }

            // 3. Last fallback: timestamp-only match.
            // We try HH:mm:ss first, then HH:mm
            val zone = ZoneId.systemDefault()
            val formats = listOf("HH:mm:ss", "HH:mm")

            for (fmt in formats) {
                val timestampPart =
                    DateTimeFormatter
                        .ofPattern(fmt)
                        .withZone(zone)
                        .format(Instant.ofEpochMilli(timestamp))

                val startIndex =
                    lines.indexOfFirst { line ->
                        val trimmed = line.trim()
                        trimmed == "- $timestampPart" || trimmed.startsWith("- $timestampPart ")
                    }
                if (startIndex != -1) {
                    val endIndex = findBlockEndIndex(lines, startIndex)
                    return startIndex to endIndex
                }
            }

            return -1 to -1
        }

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
            timestampStr: String, // New argument
            memoId: String? = null,
        ): Boolean {
            var (startIndex, endIndex) = findMemoBlock(lines, rawContent, timestamp, memoId)

            if (startIndex != -1 && endIndex >= startIndex) {
                val contentLines = newRawContent.lines()
                val newMemoLines = mutableListOf<String>()

                if (contentLines.isNotEmpty()) {
                    newMemoLines.add("- $timestampStr ${contentLines.first()}")
                    for (i in 1 until contentLines.size) {
                        newMemoLines.add(contentLines[i])
                    }
                } else {
                    newMemoLines.add("- $timestampStr")
                }

                val linesToRemove = (endIndex - startIndex) + 1
                for (k in 0 until linesToRemove) {
                    if (startIndex < lines.size) lines.removeAt(startIndex)
                }
                lines.addAll(startIndex, newMemoLines)
                return true
            }
            return false
        }

        /** Removes a memo block. Returns true if successful. */
        fun removeMemoBlock(
            lines: MutableList<String>,
            rawContent: String,
            timestamp: Long,
            memoId: String? = null,
        ): Boolean {
            val (startIndex, endIndex) = findMemoBlock(lines, rawContent, timestamp, memoId)
            if (startIndex != -1 && endIndex >= startIndex) {
                val linesToRemove = (endIndex - startIndex) + 1
                for (k in 0 until linesToRemove) {
                    if (startIndex < lines.size) lines.removeAt(startIndex)
                }
                return true
            }
            return false
        }

        fun extractTags(content: String): List<String> {
            val matcher = TAG_PATTERN.matcher(content)
            val tags = mutableListOf<String>()
            while (matcher.find()) {
                matcher.group(1)?.let { tag ->
                    val cleanTag = tag.trimEnd('/')
                    if (cleanTag.isNotEmpty()) tags.add(cleanTag)
                }
            }
            return tags.distinct()
        }

        fun extractImages(content: String): List<String> {
            val images = mutableListOf<String>()
            val mdMatcher = MD_IMAGE_PATTERN.matcher(content)
            while (mdMatcher.find()) {
                mdMatcher.group(1)?.let { images.add(it) }
            }
            val wikiMatcher = WIKI_IMAGE_PATTERN.matcher(content)
            while (wikiMatcher.find()) {
                wikiMatcher.group(1)?.let { images.add(it) }
            }
            return images
        }

        fun toggleCheckbox(
            content: String,
            lineIndex: Int,
            checked: Boolean,
        ): String {
            require(lineIndex >= 0) { "lineIndex must be non-negative, was: $lineIndex" }

            val lines = content.lines().toMutableList()
            if (lineIndex !in lines.indices) {
                // P1-004 Fix: Log warning for debugging edge cases
                timber.log.Timber.w("toggleCheckbox: lineIndex $lineIndex out of bounds (0..${lines.lastIndex})")
                return content
            }

            val line = lines[lineIndex]
            val pattern = if (checked) "- [ ]" else "- [x]"

            if (!line.contains(pattern)) {
                // P1-004 Fix: Log warning for debugging when pattern not found
                timber.log.Timber.w("toggleCheckbox: expected pattern '$pattern' not found at line $lineIndex")
                return content
            }

            val replacement = if (checked) "- [x]" else "- [ ]"
            lines[lineIndex] = line.replaceFirst(pattern, replacement)
            return lines.joinToString("\n")
        }

        /**
         * Strips Markdown syntax for simple plain text display (e.g. in Widgets).
         */
        fun stripMarkdown(content: String): String {
            var str = content
            // Headers
            str = str.replace(Regex("(?m)^#{1,6}\\s+"), "")
            // Bold/Italic (excluding list markers)
            str = str.replace(Regex("(\\*\\*|__)"), "")
            // We carefully remove single * or _ ensuring we don't break list bullets if they used *

            // Checkboxes
            str = str.replace(Regex("(?m)^\\s*[-*+]\\s*\\[ \\]"), "☐")
            str = str.replace(Regex("(?m)^\\s*[-*+]\\s*\\[x\\]"), "☑")

            // Images ![]()
            str = str.replace(Regex("!\\[.*?\\]\\(.*?\\)"), "[Image]")
            // Wiki Images ![[...]]
            str = str.replace(Regex("!\\[\\[(.*?)\\]\\]"), "[Image: $1]")

            // Links [text](url) -> text
            str = str.replace(Regex("(?<!!)\\[(.*?)\\]\\(.*?\\)"), "$1")

            // Lists: Replace leading dash/star with bullet for cleaner look if not already replaced
            str = str.replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")

            return str.trim()
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
                val headerMatch = MEMO_BLOCK_HEADER.matchEntire(lines[index])
                if (headerMatch == null) {
                    index++
                    continue
                }

                val start = index
                var end = index
                index++
                while (index < lines.size && !MEMO_BLOCK_END.matches(lines[index])) {
                    end = index
                    index++
                }

                val firstLineContent = headerMatch.groupValues.getOrNull(2).orEmpty()
                val contentBuilder = StringBuilder(firstLineContent)
                for (lineIndex in (start + 1)..end) {
                    if (lineIndex !in lines.indices) continue
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
                        timePart = headerMatch.groupValues[1],
                        content = contentBuilder.toString().trim(),
                    ),
                )
            }
            return blocks
        }

        private fun parseMemoId(memoId: String): ParsedMemoId? {
            val match = MEMO_ID_PATTERN.matchEntire(memoId) ?: return null
            val collisionIndex =
                match.groupValues
                    .getOrNull(4)
                    ?.takeIf { it.isNotEmpty() }
                    ?.toIntOrNull() ?: 0

            return ParsedMemoId(
                timePart = match.groupValues[2],
                contentHash = match.groupValues[3],
                collisionIndex = collisionIndex,
            )
        }

        private fun findBlockEndIndex(
            lines: List<String>,
            startIndex: Int,
        ): Int {
            var endIndex = startIndex
            for (j in (startIndex + 1) until lines.size) {
                if (lines[j].matches(MEMO_BLOCK_END)) {
                    break
                }
                endIndex = j
            }
            return endIndex
        }

        private fun contentHash(content: String): String =
            content.trim().hashCode().let {
                kotlin.math.abs(it).toString(16)
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
    }
