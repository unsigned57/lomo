package com.lomo.domain.usecase

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.domain.model.Memo
import com.lomo.domain.model.TagSelection
import com.lomo.domain.model.TagSelectionMode
import com.lomo.domain.repository.MemoSearchRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: GetMemosByTagPageUseCase.
 * - Owning layer: domain.
 * - Priority tier: P0.
 *
 * - Capability: tag-page navigation requests slash-delimited subtree semantics through a typed
 * domain selection, and invalid tag paths are rejected before repository access.
 *
 * Scenarios:
 * - Given a valid parent tag, when the use case creates Paging, then the repository receives
 * TagSelectionMode.Subtree with the unchanged path.
 * - Given a blank or malformed tag path, when invoked, then construction fails before repository
 * access.
 *
 * Observable outcomes:
 * - Recorded TagSelection or IllegalArgumentException.
 *
 * TDD proof:
 * - RED in the audit because the repository previously accepted a raw String only.
 *
 * Excludes:
 * - Rust SQL matching and Paging loading.
 */
class GetMemosByTagPageUseCaseTest : DomainFunSpec() {
    init {
        test("tag page requests explicit subtree selection") {
            val repository = RecordingMemoSearchRepository()

            GetMemosByTagPageUseCase(repository)("work/project")

            repository.selection?.path?.value shouldBe "work/project"
            repository.selection?.mode shouldBe TagSelectionMode.Subtree
        }

        test("invalid tag path is rejected before repository access") {
            val repository = RecordingMemoSearchRepository()

            shouldThrow<IllegalArgumentException> { GetMemosByTagPageUseCase(repository)("work//project") }

            repository.selection shouldBe null
        }
    }
}

private class RecordingMemoSearchRepository : MemoSearchRepository {
    var selection: TagSelection? = null

    override fun getMemosByTagPagingSource(selection: TagSelection): PagingSource<Int, Memo> {
        this.selection = selection
        return EmptyMemoPagingSource()
    }
}

private class EmptyMemoPagingSource : PagingSource<Int, Memo>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> =
        LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)

    override fun getRefreshKey(state: PagingState<Int, Memo>): Int? = null
}
