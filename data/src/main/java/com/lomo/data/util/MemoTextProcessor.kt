package com.lomo.data.util

import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.data.memo.MemoContentHashPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class MemoTextProcessor
    @Inject
    constructor() {
        companion object {
            private val TAG_PATTERN = Regex("(?:^|\\s)#([\\p{L}\\p{N}_][\\p{L}\\p{N}_/]*)")
            private val MD_IMAGE_PATTERN = Regex("!\\[.*?\\]\\((.*?)\\)")
            private val WIKI_IMAGE_PATTERN = Regex("!\\[\\[(.*?)\\]\\]")
            private val AUDIO_LINK_PATTERN =
                Regex(
                    "(?<!!)\\[[^\\]]*\\]\\((.+?\\.(?:m4a|mp3|ogg|wav|aac))\\)",
                    RegexOption.IGNORE_CASE,
                )
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
            val zone = ZoneId.systemDefault()
            val formats = StorageTimestampFormats.supportedPatterns

            for (fmt in formats) {
                val timestampPart =
                    StorageTimestampFormats
                        .formatter(fmt)
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

                lines.subList(startIndex, endIndex + 1).clear()
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
                lines.subList(startIndex, endIndex + 1).clear()
                return true
            }
            return false
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
                .mapNotNull {
                    it.groupValues.getOrNull(1)
                }.toList()

        fun extractLocalAttachmentPaths(content: String): List<String> =
            (extractImages(content) + extractAudioLinks(content))
                .map { it.trim() }
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

            val builder = StringBuilder(content.length + 8)
            var currentIndex = 0
            var hadAnyLine = false
            var targetFound = false
            var targetReplaced = false

            content.lineSequence().forEach { line ->
                if (hadAnyLine) builder.append('\n')

                if (currentIndex == lineIndex) {
                    targetFound = true
                    if (line.contains(pattern)) {
                        builder.append(line.replaceFirst(pattern, replacement))
                        targetReplaced = true
                    } else {
                        builder.append(line)
                    }
                } else {
                    builder.append(line)
                }

                hadAnyLine = true
                currentIndex++
            }

            if (!targetFound) {
                timber.log.Timber.w("toggleCheckbox: lineIndex $lineIndex out of bounds (0..${currentIndex - 1})")
                return content
            }

            if (!targetReplaced) {
                timber.log.Timber.w("toggleCheckbox: expected pattern '$pattern' not found at line $lineIndex")
                return content
            }

            return builder.toString()
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
                        timePart = header.timePart,
                        content = contentBuilder.toString().trim(),
                    ),
                )
            }
            return blocks
        }

        private fun parseMemoId(memoId: String): ParsedMemoId? {
            val trimmed = memoId.trim()
            if (trimmed.isEmpty()) return null

            val parts = trimmed.split('_')
            if (parts.size < 3) return null

            val tail = parts.last()
            val collisionIndex = if (tail.all(Char::isDigit)) tail.toIntOrNull() ?: return null else 0
            val coreParts = if (collisionIndex > 0) parts.dropLast(1) else parts
            if (coreParts.size < 3) return null

            val contentHash = coreParts.last()
            if (!contentHash.matches(Regex("^[0-9a-f]+$"))) return null

            // ID schema: {date}_{time}_{contentHash}[_{collision}]
            // Date may contain underscores, and time may also contain underscores depending on selected format.
            // We find the split point by validating time suffix candidates.
            val dateAndTimeParts = coreParts.dropLast(1)
            var timePart: String? = null
            for (start in 1 until dateAndTimeParts.size) {
                val candidate = dateAndTimeParts.subList(start, dateAndTimeParts.size).joinToString("_")
                if (StorageTimestampFormats.parseOrNull(candidate) != null) {
                    timePart = candidate
                    break
                }
            }
            val resolvedTimePart = timePart ?: return null

            return ParsedMemoId(
                timePart = resolvedTimePart,
                contentHash = contentHash,
                collisionIndex = collisionIndex,
            )
        }

        private fun findBlockEndIndex(
            lines: List<String>,
            startIndex: Int,
        ): Int {
            var endIndex = startIndex
            for (j in (startIndex + 1) until lines.size) {
                if (isMemoHeaderLine(lines[j])) {
                    break
                }
                endIndex = j
            }
            return endIndex
        }

        private fun isMemoHeaderLine(line: String): Boolean =
            StorageTimestampFormats.parseMemoHeaderLine(line) != null

        private fun contentHash(content: String): String = MemoContentHashPolicy.hashHex(content)

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
