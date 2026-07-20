package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: TestMemoWorkspaceStore (test helper, not production code).
 * - Behavior focus: provides the canonical workspace owner + projector wiring for repository tests.
 * - Observable outcomes: tests construct repository collaborators without deleted MarkdownParser.
 * - TDD proof: Compilation failure after MemoWorkspaceStore became the Rust workspace boundary.
 * - Excludes: storage backend behavior, production Markdown semantics, and Room persistence.
 */

import com.lomo.data.engine.WorkspaceMarkdownOwner
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.testing.fakes.FakeWorkspaceMarkdownOwner
import com.lomo.data.testing.fakes.fakeMarkdownWorkspaceContentProjector
import com.lomo.data.util.MarkdownWorkspaceContentProjector

internal fun testMemoWorkspaceStore(
    markdownStorageDataSource: MarkdownStorageDataSource,
    localFileStateDao: LocalFileStateDao = InMemoryTestWorkspaceLocalFileStateDao(),
    workspaceOwner: WorkspaceMarkdownOwner = FakeWorkspaceMarkdownOwner(),
): MemoWorkspaceStore =
    MemoWorkspaceStore(
        writer =
            testMemoWorkspaceShardWriter(
                markdownStorageDataSource = markdownStorageDataSource,
                localFileStateDao = localFileStateDao,
            ),
        workspaceOwner = workspaceOwner,
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
    workspaceOwner: WorkspaceMarkdownOwner = FakeWorkspaceMarkdownOwner(),
): MemoWorkspaceProjector = MemoWorkspaceProjector(workspaceOwner = workspaceOwner)

internal fun testContentProjector(): MarkdownWorkspaceContentProjector =
    fakeMarkdownWorkspaceContentProjector()

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
