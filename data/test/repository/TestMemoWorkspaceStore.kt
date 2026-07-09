package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: TestMemoWorkspaceStore (test helper, not production code).
 * - Behavior focus: provides the canonical workspace owner to repository test fixtures.
 * - Observable outcomes: tests can construct repository collaborators without reintroducing raw markdown helpers.
 * - TDD proof: Compilation failure after MemoWorkspaceStore became the required markdown workspace boundary.
 * - Excludes: storage backend behavior, parser behavior, and Room persistence.
 */

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.usecase.MemoIdentityPolicy

internal fun testMemoWorkspaceStore(
    markdownStorageDataSource: MarkdownStorageDataSource,
    localFileStateDao: LocalFileStateDao = InMemoryTestWorkspaceLocalFileStateDao(),
    textProcessor: MemoTextProcessor = MemoTextProcessor(),
    memoIdentityPolicy: MemoIdentityPolicy = MemoIdentityPolicy(),
    parser: MarkdownParser = MarkdownParser(textProcessor, memoIdentityPolicy),
): MemoWorkspaceStore =
    MemoWorkspaceStore(
        reader =
            testMemoWorkspaceReader(
                markdownStorageDataSource = markdownStorageDataSource,
                localFileStateDao = localFileStateDao,
            ),
        writer =
            testMemoWorkspaceShardWriter(
                markdownStorageDataSource = markdownStorageDataSource,
                localFileStateDao = localFileStateDao,
            ),
        parser = parser,
    )

internal fun testMemoWorkspaceReader(
    markdownStorageDataSource: MarkdownStorageDataSource,
    localFileStateDao: LocalFileStateDao = InMemoryTestWorkspaceLocalFileStateDao(),
): MemoWorkspaceReader =
    MemoWorkspaceReader(
        markdownStorageDataSource = markdownStorageDataSource,
        fileStateStore =
            MemoWorkspaceFileStateStore(
                localFileStateDao = localFileStateDao,
                markdownStorageDataSource = markdownStorageDataSource,
            ),
    )

internal fun testMemoWorkspaceProjector(
    markdownStorageDataSource: MarkdownStorageDataSource,
    localFileStateDao: LocalFileStateDao = InMemoryTestWorkspaceLocalFileStateDao(),
    textProcessor: MemoTextProcessor = MemoTextProcessor(),
    memoIdentityPolicy: MemoIdentityPolicy = MemoIdentityPolicy(),
    parser: MarkdownParser = MarkdownParser(textProcessor, memoIdentityPolicy),
): MemoWorkspaceProjector =
    MemoWorkspaceProjector(
        reader =
            testMemoWorkspaceReader(
                markdownStorageDataSource = markdownStorageDataSource,
                localFileStateDao = localFileStateDao,
            ),
        parser = parser,
    )

private fun testMemoWorkspaceShardWriter(
    markdownStorageDataSource: MarkdownStorageDataSource,
    localFileStateDao: LocalFileStateDao,
): MemoWorkspaceShardWriter =
    MemoWorkspaceShardWriter(
        markdownStorageDataSource = markdownStorageDataSource,
        fileStateStore =
            MemoWorkspaceFileStateStore(
                localFileStateDao = localFileStateDao,
                markdownStorageDataSource = markdownStorageDataSource,
            ),
    )

private class InMemoryTestWorkspaceLocalFileStateDao : LocalFileStateDao {
    private val states = linkedMapOf<Pair<String, Boolean>, com.lomo.data.local.entity.LocalFileStateEntity>()

    override suspend fun getByFilename(
        filename: String,
        isTrash: Boolean,
    ): com.lomo.data.local.entity.LocalFileStateEntity? = states[filename to isTrash]

    override suspend fun getAll(): List<com.lomo.data.local.entity.LocalFileStateEntity> = states.values.toList()

    override suspend fun getAllByTrashStatus(isTrash: Boolean): List<com.lomo.data.local.entity.LocalFileStateEntity> =
        states.values.filter { state -> state.isTrash == isTrash }

    override suspend fun upsert(entity: com.lomo.data.local.entity.LocalFileStateEntity) {
        states[entity.filename to entity.isTrash] = entity
    }

    override suspend fun upsertAll(entities: List<com.lomo.data.local.entity.LocalFileStateEntity>) {
        entities.forEach { entity -> states[entity.filename to entity.isTrash] = entity }
    }

    override suspend fun deleteByFilename(
        filename: String,
        isTrash: Boolean,
    ) {
        states.remove(filename to isTrash)
    }

    override suspend fun clearAll() {
        states.clear()
    }
}
