package com.lomo.domain.model

/**
 * Dual-mode Tantivy retrieval owned by the application session.
 *
 * [Fulltext] searches Chinese word segmentation (Charabia), CJK bigrams, and lowercase tokens
 * while preserving list filters and sort order.
 * [Fuzzy] expands [Fulltext] with edit-distance typo tolerance, bounded within-term subsequence
 * matching, and heteronym pinyin (full syllables and initials) alongside the same filters and sort.
 */
enum class MemoSearchMode {
    Fulltext,
    Fuzzy,
}
