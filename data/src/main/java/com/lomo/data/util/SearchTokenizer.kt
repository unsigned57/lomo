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
                // CJK Character
                sb.append(c)
                // Append bigram if next char is also CJK or valid
                if (i + 1 < length) {
                    val next = text[i + 1]
                    if (isCJK(next)) {
                        sb.append(next)
                        sb.append(" ")
                    } else if (Character.isLetterOrDigit(next)) {
                        // CJK followed by ASCII, treat as separate tokens usually,
                        // but for "ID号", "ID" "号" is better.
                        // Let's stick to pure CJK bigrams for now.
                        sb.append(" ")
                    } else {
                        sb.append(" ")
                    }
                } else {
                    // Last char unigram? FTS4 by default ignores single chars often,
                    // but for CJK single char is important.
                    // Let's ensure single chars are also indexed if we want?
                    // "你好" -> "你好"
                    // "我" -> "我"
                    // If we just do bigrams: "你好" -> "你好". Search "你" fails?
                    // To support "你", we might need unigrams too.
                    // "你好" -> "你 好 你好"
                    // But that triples the index size.
                    // Usually "Search for '你'" matches "你好" in "LIKE" but FTS matches tokens.
                    // If we query "你*", it matches "你好".
                    // So we rely on query prefix matching if needed.
                    sb.append(" ")
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
