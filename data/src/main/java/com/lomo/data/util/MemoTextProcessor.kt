package com.lomo.data.util

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
            if (contentLines.size == 1 && contentLines.first().isEmpty()) {
                newMemoLines.add("- $timestampStr")
            } else if (contentLines.isNotEmpty()) {
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

        fun replaceMemoBlockSafely(
            lines: MutableList<String>,
            rawContent: String,
            newRawContent: String,
            timestampStr: String,
            memoId: String? = null,
        ): Boolean {
            val (startIndex, endIndex) = findDestructiveMemoBlock(lines, rawContent, memoId)
            if (startIndex == -1 || endIndex < startIndex) {
                return false
            }

            val contentLines = newRawContent.lines()
            val newMemoLines = mutableListOf<String>()
            if (contentLines.size == 1 && contentLines.first().isEmpty()) {
                newMemoLines.add("- $timestampStr")
            } else if (contentLines.isNotEmpty()) {
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

        fun removeMemoBlockSafely(
            lines: MutableList<String>,
            rawContent: String,
            memoId: String? = null,
        ): Boolean {
            val (startIndex, endIndex) = findDestructiveMemoBlock(lines, rawContent, memoId)
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

        private enum class CheckboxToggleState {
            NOT_FOUND,
            PATTERN_MISSING,
            REPLACED,
        }

        companion object {
            private val TAG_PATTERN = Regex("""(?:^|\s)#([\p{L}\p{N}\p{So}\p{Sc}_][\p{L}\p{N}\p{So}\p{Sc}_/]*)""")
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
