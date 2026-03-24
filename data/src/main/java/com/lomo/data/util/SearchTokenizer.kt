package com.lomo.data.util

import java.lang.Character.UnicodeBlock

object SearchTokenizer {
    fun tokenize(text: String): String {
        val sb = StringBuilder()
        val length = text.length
        var i = 0
        while (i < length) {
            val c = text[i]
            if (isCJK(c)) {
                // Emit Unigram for partial matching (e.g. searching "你" in "你好")
                sb.append(c).append(" ")

                // Emit Bigram for exact phrase matching (e.g. "你好")
                if (i + 1 < length) {
                    val next = text[i + 1]
                    if (isCJK(next)) {
                        sb.append(c).append(next).append(" ")
                    }
                }
            } else if (Character.isLetterOrDigit(c)) {
                // ASCII/Other
                sb.append(c)
                // Continue until non-letter
                var j = i + 1
                while (j < length && Character.isLetterOrDigit(text[j]) && !isCJK(text[j])) {
                    sb.append(text[j])
                    j++
                }
                sb.append(" ")
                i = j - 1 // Advance
            }
            // Skip other symbols
            i++
        }
        return sb.toString().trim()
    }

    /**
     * Tokenize search input for MATCH queries.
     * For CJK runs with 2+ chars, emit only bigrams to avoid single-char broad matches.
     */
    fun tokenizeQueryTerms(text: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0

        while (i < text.length) {
            i =
                when {
                    isCJK(text[i]) -> collectCjkQueryTerms(text, i, tokens)
                    Character.isLetterOrDigit(text[i]) -> collectWordQueryTerm(text, i, tokens)
                    else -> i + 1
                }
        }

        return tokens.distinct()
    }

    fun containsCjk(text: String): Boolean = text.any(::isCJK)

    private fun collectCjkQueryTerms(
        text: String,
        startIndex: Int,
        tokens: MutableList<String>,
    ): Int {
        val endIndex = findCjkRunEnd(text, startIndex)
        if (endIndex - startIndex == 1) {
            tokens.add(text[startIndex].toString())
        } else {
            for (index in startIndex until endIndex - 1) {
                tokens.add(text.substring(index, index + 2))
            }
        }
        return endIndex
    }

    private fun collectWordQueryTerm(
        text: String,
        startIndex: Int,
        tokens: MutableList<String>,
    ): Int {
        val endIndex = findWordEnd(text, startIndex)
        tokens.add(text.substring(startIndex, endIndex))
        return endIndex
    }

    private fun findCjkRunEnd(
        text: String,
        startIndex: Int,
    ): Int {
        var endIndex = startIndex + 1
        while (endIndex < text.length && isCJK(text[endIndex])) {
            endIndex++
        }
        return endIndex
    }

    private fun findWordEnd(
        text: String,
        startIndex: Int,
    ): Int {
        var endIndex = startIndex + 1
        while (endIndex < text.length && Character.isLetterOrDigit(text[endIndex]) && !isCJK(text[endIndex])) {
            endIndex++
        }
        return endIndex
    }

    private fun isCJK(c: Char): Boolean {
        val block = UnicodeBlock.of(c)
        return block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT ||
            block == UnicodeBlock.HIRAGANA ||
            block == UnicodeBlock.KATAKANA ||
            block == UnicodeBlock.HANGUL_SYLLABLES
    }
}
