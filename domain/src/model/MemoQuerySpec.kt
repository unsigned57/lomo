package com.lomo.domain.model

import java.time.LocalDate

data class MemoContentAnalysis(
    val hasTodo: Boolean = false,
    val hasAttachment: Boolean = false,
    val hasUrl: Boolean = false,
    val tags: List<String> = emptyList(),
    val imageUrls: List<String> = emptyList(),
    val audioUrls: List<String> = emptyList(),
) {
    companion object {
        val None = MemoContentAnalysis()
    }
}

data class MemoQuerySpec(
    val queryText: String = "",
    val dateRange: MemoQueryDateRange = MemoQueryDateRange(),
    val criteria: Set<MemoFilterCriterion> = emptySet(),
    val sort: MemoQuerySort = MemoQuerySort(),
) {
    init {
        requireNoContradiction(
            positive = MemoFilterCriterion.HasTodo,
            negative = MemoFilterCriterion.NoTodo,
            name = "todo",
        )
        requireNoContradiction(
            positive = MemoFilterCriterion.HasAttachment,
            negative = MemoFilterCriterion.NoAttachment,
            name = "attachment",
        )
        requireNoContradiction(
            positive = MemoFilterCriterion.HasUrl,
            negative = MemoFilterCriterion.NoUrl,
            name = "url",
        )
    }

    val normalizedQueryText: String = queryText.trim()

    val isAllMemosQuery: Boolean
        get() = normalizedQueryText.isEmpty() && criteria.isEmpty()

    val hasContentFilters: Boolean
        get() = criteria.isNotEmpty()

    fun matches(analysis: MemoContentAnalysis): Boolean =
        criteria.all { criterion -> criterion.matches(analysis) }

    private fun requireNoContradiction(
        positive: MemoFilterCriterion,
        negative: MemoFilterCriterion,
        name: String,
    ) {
        require(!(positive in criteria && negative in criteria)) {
            "MemoQuerySpec cannot require both positive and negative $name criteria"
        }
    }

    companion object {
        fun fromFilter(
            queryText: String = "",
            filter: MemoListFilter,
        ): MemoQuerySpec =
            MemoQuerySpec(
                queryText = queryText,
                dateRange = MemoQueryDateRange.from(filter.startDate, filter.endDate),
                criteria = filter.toCriteria(),
                sort =
                    MemoQuerySort(
                        option = filter.sortOption,
                        ascending = filter.sortAscending,
                    ),
            )
    }
}

data class MemoQueryDateRange(
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
) {
    companion object {
        fun from(
            startDate: LocalDate?,
            endDate: LocalDate?,
        ): MemoQueryDateRange {
            val normalizedRange = listOfNotNull(startDate, endDate).sorted()
            return MemoQueryDateRange(
                startDate = normalizedRange.firstOrNull() ?: startDate,
                endDate = normalizedRange.lastOrNull() ?: endDate,
            )
        }
    }
}

data class MemoQuerySort(
    val option: MemoSortOption = MemoSortOption.CREATED_TIME,
    val ascending: Boolean = false,
)

sealed interface MemoFilterCriterion {
    fun matches(analysis: MemoContentAnalysis): Boolean

    data object HasTodo : MemoFilterCriterion {
        override fun matches(analysis: MemoContentAnalysis): Boolean = analysis.hasTodo
    }

    data object NoTodo : MemoFilterCriterion {
        override fun matches(analysis: MemoContentAnalysis): Boolean = !analysis.hasTodo
    }

    data object HasAttachment : MemoFilterCriterion {
        override fun matches(analysis: MemoContentAnalysis): Boolean = analysis.hasAttachment
    }

    data object NoAttachment : MemoFilterCriterion {
        override fun matches(analysis: MemoContentAnalysis): Boolean = !analysis.hasAttachment
    }

    data object HasUrl : MemoFilterCriterion {
        override fun matches(analysis: MemoContentAnalysis): Boolean = analysis.hasUrl
    }

    data object NoUrl : MemoFilterCriterion {
        override fun matches(analysis: MemoContentAnalysis): Boolean = !analysis.hasUrl
    }
}

private fun MemoListFilter.toCriteria(): Set<MemoFilterCriterion> =
    buildSet {
        hasTodo?.let { hasTodo ->
            add(if (hasTodo) MemoFilterCriterion.HasTodo else MemoFilterCriterion.NoTodo)
        }
        hasAttachment?.let { hasAttachment ->
            add(
                if (hasAttachment) {
                    MemoFilterCriterion.HasAttachment
                } else {
                    MemoFilterCriterion.NoAttachment
                },
            )
        }
        hasUrl?.let { hasUrl ->
            add(if (hasUrl) MemoFilterCriterion.HasUrl else MemoFilterCriterion.NoUrl)
        }
    }
