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
            private val MEMO_BLOCK_END = Regex("^-\\s\\d{2}:\\d{2}(?::\\d{2})?.*")
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
        ): Pair<Int, Int> {
            val rawLines = rawContent.lines()
            val contentStartLine = rawLines.firstOrNull { it.isNotBlank() } ?: return -1 to -1

            // 1. Try exact content match
            for (i in lines.indices) {
                if (lines[i].trim() == contentStartLine.trim()) {
                    var endIndex = i
                    for (j in (i + 1) until lines.size) {
                        if (lines[j].matches(MEMO_BLOCK_END)) {
                            break
                        }
                        endIndex = j
                    }
                    return i to endIndex
                }
            }

            // 2. Fallback: Timestamp match - Try common formats
            // We try HH:mm:ss first, then HH:mm
            val zone = ZoneId.systemDefault()
            val formats = listOf("HH:mm:ss", "HH:mm")

            for (fmt in formats) {
                val timestampPart =
                    DateTimeFormatter
                        .ofPattern(fmt)
                        .withZone(zone)
                        .format(Instant.ofEpochMilli(timestamp))

                val startIndex = lines.indexOfFirst { it.trim().startsWith("- $timestampPart") }
                if (startIndex != -1) {
                    var endIndex = startIndex
                    for (j in (startIndex + 1) until lines.size) {
                        if (lines[j].matches(MEMO_BLOCK_END)) {
                            endIndex = j - 1
                            break
                        }
                        endIndex = j
                    }
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
        ): Boolean {
            var (startIndex, endIndex) = findMemoBlock(lines, rawContent, timestamp)

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
        ): Boolean {
            val (startIndex, endIndex) = findMemoBlock(lines, rawContent, timestamp)
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
    }
