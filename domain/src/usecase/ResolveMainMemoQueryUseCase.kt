package com.lomo.domain.usecase

class ResolveMainMemoQueryUseCase {
    sealed interface ResolvedQuery {
        data class BySearchText(
            val query: String,
        ) : ResolvedQuery

        data object AllMemos : ResolvedQuery
    }

    operator fun invoke(
        query: String,
    ): ResolvedQuery =
        when {
            query.isNotBlank() -> ResolvedQuery.BySearchText(query)
            else -> ResolvedQuery.AllMemos
        }
}
