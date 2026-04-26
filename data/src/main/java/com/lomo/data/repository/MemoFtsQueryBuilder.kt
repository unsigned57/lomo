package com.lomo.data.repository

import com.lomo.data.util.SearchTokenizer

internal object MemoFtsQueryBuilder {
    fun buildMatchQuery(input: String): String? =
        SearchTokenizer
            .tokenizeQueryTerms(input.trim())
            .take(MAX_SEARCH_TOKENS)
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString(" ") { token ->
                when {
                    token.length == 1 && SearchTokenizer.containsCjk(token) -> "$token*"
                    token in LITERAL_FTS_OPERATOR_TOKENS -> "$token*"
                    else ->
                        buildString(token.length + PREFIX_TOKEN_ESCAPE_OVERHEAD) {
                            append('"')
                            append(token.replace("\"", "\"\""))
                            append("\"*")
                        }
                }
            }
            ?.takeIf(String::isNotBlank)

    private const val MAX_SEARCH_TOKENS = 5
    private const val PREFIX_TOKEN_ESCAPE_OVERHEAD = 3
    private val LITERAL_FTS_OPERATOR_TOKENS = setOf("and", "or", "not")
}
