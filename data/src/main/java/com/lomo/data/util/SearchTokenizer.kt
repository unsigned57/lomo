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
        val length = text.length
        var i = 0

        while (i < length) {
            val c = text[i]
            if (isCJK(c)) {
                var j = i + 1
                while (j < length && isCJK(text[j])) {
                    j++
                }

                val runLength = j - i
                if (runLength == 1) {
                    tokens.add(c.toString())
                } else {
                    var k = i
                    while (k < j - 1) {
                        tokens.add(text.substring(k, k + 2))
                        k++
                    }
                }
                i = j
                continue
            }

            if (Character.isLetterOrDigit(c)) {
                var j = i + 1
                while (j < length && Character.isLetterOrDigit(text[j]) && !isCJK(text[j])) {
                    j++
                }
                tokens.add(text.substring(i, j))
                i = j
                continue
            }

            i++
        }

        return tokens.distinct()
    }

    fun containsCjk(text: String): Boolean = text.any(::isCJK)

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
