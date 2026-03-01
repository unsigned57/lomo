package com.lomo.domain.usecase

class ResolveMainMemoQueryUseCase {
    sealed interface ResolvedQuery {
        data class ByTag(
            val tag: String,
        ) : ResolvedQuery

        data class BySearchText(
            val query: String,
        ) : ResolvedQuery

        data object AllMemos : ResolvedQuery
    }

    operator fun invoke(
        query: String,
        selectedTag: String?,
    ): ResolvedQuery =
        when {
            !selectedTag.isNullOrBlank() -> ResolvedQuery.ByTag(selectedTag)
            query.isNotBlank() -> ResolvedQuery.BySearchText(query)
            else -> ResolvedQuery.AllMemos
        }
}
