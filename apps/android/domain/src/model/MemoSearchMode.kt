package com.lomo.domain.model

/**
 * Dual-mode retrieval owned by the application session.
 *
 * [Fulltext] is the store FTS projection (and still carries Android list filters).
 * [Fuzzy] is pinyin/subsequence ranking; filters are not part of that request yet.
 */
enum class MemoSearchMode {
    Fulltext,
    Fuzzy,
}
