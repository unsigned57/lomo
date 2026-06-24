package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.projectedMemoEntity
import com.lomo.data.testing.projectedTrashMemoEntity
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.MemoIdentityPolicy
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MemoWorkspaceStore
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: own markdown workspace shard projection and memo block surgery for repository workflows.
 *
 * Scenarios:
 * - Given a markdown shard and existing active memo identities, when the shard is projected, then typed projection
 *   changes preserve stable memo ids and local file-state metadata.
 * - Given an active memo block is moved to trash, when the source shard becomes empty, then the source file/state is
 *   deleted and the trash shard/state receives the removed canonical block.
 * - Given a version restore upserts an active block and removes the trash copy, when both shards exist, then only the
 *   workspace store rewrites markdown and local file state follows the changed shards.
 * - Given caller raw content still appears but the parsed source span for the memo identity is missing, when a mutation
 *   runs, then the shard is not rewritten and the missing span is surfaced.
 * - Given an upsert is explicitly creating a new memo, when the target shard is missing, then the new raw block is
 *   written as the shard content.
 * - Given version restore upsert targets an existing shard without the memo source span, when the restore tries to
 *   replace that block, then the shard is not appended and the missing span is surfaced.
 * - Given version restore upsert targets a missing or blank same-shard source, when the restore tries to replace the
 *   existing memo block, then the missing span is surfaced and replacement content is not written.
 * - Given version restore removes a required source block but the file or span is missing, when removal runs, then the
 *   missing span is surfaced instead of being ignored.
 *
 * Observable outcomes:
 * - MemoProjectionChangeSet contents, persisted markdown files, LocalFileStateEntity rows, and mutation result type.
 *
 * TDD proof:
 * - Fails before the same-shard restore fix because a missing or blank upsert target is treated as a successful
 *   replacement write.
 *
 * Excludes:
 * - Room SQL mechanics, sync transport journals, UI rendering, and MarkdownParser internals.
 *
 * Test Change Justification:
 * - Reason category: Memo identity model shifted from content-derived to positional ids.
 * - Old behavior/assertion being replaced: workspace shard projection used content-derived memo ids.
 * - Why old assertion is no longer correct: positional ids replace content hashes for stable memo
 *   identity during shard projection and block surgery.
 * - Coverage preserved by: all shard projection, trash move, version restore, and missing-span
 *   scenarios retained; identity assertions updated to positional id format.
 * - Why this is not fitting the test to the implementation: tests verify observable memo collection
 *   outputs and file-state metadata, not internal identity-derivation mechanics.
 */
class MemoWorkspaceStoreTest : DataFunSpec() {
    init {
        beforeTest {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT+8"))
        }

        test("given main shard when projected then stable ids and metadata are owned by workspace store") {
            runTest {
                val storage = InMemoryWorkspaceMarkdownStorage()
                val fileStateDao = InMemoryWorkspaceLocalFileStateDao()
                val projector = memoWorkspaceProjector(storage = storage, fileStateDao = fileStateDao)
                val metadata =
                    FileMetadataWithId(
                        filename = "2026_05_19.md",
                        lastModified = 42L,
                        documentId = "main-doc",
                        uriString = "content://lomo/main-doc",
                    )
                storage.documentContents[MemoDirectoryType.MAIN to "main-doc"] = "- 09:00 edited"
                val existingMemo = memo(id = "stable-id", content = "before", rawContent = "- 09:00 before")

                val changeSet =
                    projector.projectShard(
                        directory = MemoDirectoryType.MAIN,
                        metadata = metadata,
                        existingActiveMemos = listOf(projectedMemoEntity(existingMemo)),
                    )

                changeSet shouldBe
                    MemoProjectionChangeSet.Active(
                        memos =
                            listOf(
                                projectedMemoEntity(
                                    existingMemo.copy(
                                        content = "edited",
                                        rawContent = "- 09:00 edited",
                                    ),
                                ).copy(updatedAt = 42L),
                            ),
                        metadata =
                            LocalFileStateEntity(
                                filename = "2026_05_19.md",
                                isTrash = false,
                                safUri = "content://lomo/main-doc",
                                lastKnownModifiedTime = 42L,
                            ),
                        dateKey = "2026_05_19",
                    )
            }
        }

        test("given active memo block when moved to trash then source shard deletion and trash append are canonical") {
            runTest {
                val storage = InMemoryWorkspaceMarkdownStorage()
                val fileStateDao = InMemoryWorkspaceLocalFileStateDao()
                val store = memoWorkspaceStore(storage = storage, fileStateDao = fileStateDao)
                val sourceMemo = memo(rawContent = "- 09:00 active body", content = "active body")
                val filename = "${sourceMemo.dateKey}.md"
                storage.mainFiles[filename] = sourceMemo.rawContent
                storage.metadata[MemoDirectoryType.TRASH to filename] = FileMetadata(filename, 99L)

                val moved = store.moveActiveMemoBlockToTrash(sourceMemo)

                moved shouldBe MemoWorkspaceBlockMutationResult.Applied
                assertSoftly {
                    storage.mainFiles.containsKey(filename) shouldBe false
                    storage.trashFiles[filename] shouldBe "\n${sourceMemo.rawContent}\n"
                    fileStateDao.getByFilename(filename, false) shouldBe null
                    fileStateDao.getByFilename(filename, true) shouldBe
                        LocalFileStateEntity(
                            filename = filename,
                            isTrash = true,
                            lastKnownModifiedTime = 99L,
                        )
                }
            }
        }

        test("given restore upsert when trash copy exists then workspace store rewrites both shards and states") {
            runTest {
                val storage = InMemoryWorkspaceMarkdownStorage()
                val fileStateDao = InMemoryWorkspaceLocalFileStateDao()
                val store = memoWorkspaceStore(storage = storage, fileStateDao = fileStateDao)
                val currentMemo = memo(rawContent = "- 09:00 current", content = "current")
                val filename = "${currentMemo.dateKey}.md"
                storage.mainFiles[filename] = "- 10:00 sibling\n${currentMemo.rawContent}"
                storage.trashFiles[filename] = currentMemo.rawContent
                storage.metadata[MemoDirectoryType.MAIN to filename] = FileMetadata(filename, 101L)

                store.upsertMemoBlock(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    currentMemo = currentMemo.copy(isDeleted = false),
                    replacementRawContent = "- 09:00 restored",
                    intent = MemoWorkspaceBlockUpsertIntent.ReplaceExistingMemo,
                )
                store.removeMemoBlock(
                    directory = MemoDirectoryType.TRASH,
                    filename = filename,
                    memo = currentMemo.copy(isDeleted = true),
                )

                assertSoftly {
                    storage.mainFiles[filename] shouldBe "- 10:00 sibling\n- 09:00 restored"
                    storage.trashFiles.containsKey(filename) shouldBe false
                    fileStateDao.getByFilename(filename, false) shouldBe
                        LocalFileStateEntity(
                            filename = filename,
                            isTrash = false,
                            lastKnownModifiedTime = 101L,
                        )
                    fileStateDao.getByFilename(filename, true) shouldBe null
                }
            }
        }

        test("given stale raw content match when updating active memo then missing parsed span is surfaced") {
            runTest {
                val storage = InMemoryWorkspaceMarkdownStorage()
                val fileStateDao = InMemoryWorkspaceLocalFileStateDao()
                val store = memoWorkspaceStore(storage = storage, fileStateDao = fileStateDao)
                val staleMemo = memo(id = "stale-id-not-produced", rawContent = "- 09:00 stale body", content = "stale body")
                val filename = "${staleMemo.dateKey}.md"
                storage.mainFiles[filename] = staleMemo.rawContent

                val result =
                    store.updateActiveMemoBlock(
                        memo = staleMemo,
                        newContent = "replacement",
                        timestampText = "09:00",
                    )

                result shouldBe
                    MemoWorkspaceBlockMutationResult.MissingSourceSpan(
                        directory = MemoDirectoryType.MAIN,
                        filename = filename,
                        memoId = "stale-id-not-produced",
                    )
                storage.mainFiles[filename] shouldBe staleMemo.rawContent
            }
        }

        test("given create upsert target is missing when creating new memo then replacement content is written") {
            runTest {
                val storage = InMemoryWorkspaceMarkdownStorage()
                val fileStateDao = InMemoryWorkspaceLocalFileStateDao()
                val store = memoWorkspaceStore(storage = storage, fileStateDao = fileStateDao)
                val newMemo = memo(id = "new-memo", rawContent = "- 09:00 created", content = "created")
                val filename = "${newMemo.dateKey}.md"

                val result =
                    store.upsertMemoBlock(
                        directory = MemoDirectoryType.MAIN,
                        filename = filename,
                        currentMemo = newMemo.copy(isDeleted = false),
                        replacementRawContent = newMemo.rawContent,
                        intent = MemoWorkspaceBlockUpsertIntent.CreateNewMemo,
                    )

                result shouldBe MemoWorkspaceBlockMutationResult.Applied
                storage.mainFiles[filename] shouldBe newMemo.rawContent
            }
        }

        test("given restore upsert target without source span when applied then missing span is surfaced and no append occurs") {
            runTest {
                val storage = InMemoryWorkspaceMarkdownStorage()
                val fileStateDao = InMemoryWorkspaceLocalFileStateDao()
                val store = memoWorkspaceStore(storage = storage, fileStateDao = fileStateDao)
                val currentMemo = memo(id = "missing-source-span", rawContent = "- 09:00 current", content = "current")
                val filename = "${currentMemo.dateKey}.md"
                storage.mainFiles[filename] = "- 10:00 sibling"

                val result =
                    store.upsertMemoBlock(
                        directory = MemoDirectoryType.MAIN,
                        filename = filename,
                        currentMemo = currentMemo.copy(isDeleted = false),
                        replacementRawContent = "- 09:00 restored",
                        intent = MemoWorkspaceBlockUpsertIntent.ReplaceExistingMemo,
                    )

                result shouldBe
                    MemoWorkspaceBlockMutationResult.MissingSourceSpan(
                        directory = MemoDirectoryType.MAIN,
                        filename = filename,
                        memoId = "missing-source-span",
                    )
                storage.mainFiles[filename] shouldBe "- 10:00 sibling"
            }
        }

        test("given restore upsert target is missing when replacing same shard then missing span is surfaced and no file is created") {
            runTest {
                val storage = InMemoryWorkspaceMarkdownStorage()
                val fileStateDao = InMemoryWorkspaceLocalFileStateDao()
                val store = memoWorkspaceStore(storage = storage, fileStateDao = fileStateDao)
                val currentMemo = memo(id = "missing-same-shard", rawContent = "- 09:00 current", content = "current")
                val filename = "${currentMemo.dateKey}.md"

                val result =
                    store.upsertMemoBlock(
                        directory = MemoDirectoryType.MAIN,
                        filename = filename,
                        currentMemo = currentMemo.copy(isDeleted = false),
                        replacementRawContent = "- 09:00 restored",
                        intent = MemoWorkspaceBlockUpsertIntent.ReplaceExistingMemo,
                    )

                result shouldBe
                    MemoWorkspaceBlockMutationResult.MissingSourceSpan(
                        directory = MemoDirectoryType.MAIN,
                        filename = filename,
                        memoId = "missing-same-shard",
                    )
                storage.mainFiles.containsKey(filename) shouldBe false
            }
        }

        test("given restore upsert target is blank when replacing same shard then missing span is surfaced and blank file is preserved") {
            runTest {
                val storage = InMemoryWorkspaceMarkdownStorage()
                val fileStateDao = InMemoryWorkspaceLocalFileStateDao()
                val store = memoWorkspaceStore(storage = storage, fileStateDao = fileStateDao)
                val currentMemo = memo(id = "blank-same-shard", rawContent = "- 09:00 current", content = "current")
                val filename = "${currentMemo.dateKey}.md"
                storage.mainFiles[filename] = " \n"

                val result =
                    store.upsertMemoBlock(
                        directory = MemoDirectoryType.MAIN,
                        filename = filename,
                        currentMemo = currentMemo.copy(isDeleted = false),
                        replacementRawContent = "- 09:00 restored",
                        intent = MemoWorkspaceBlockUpsertIntent.ReplaceExistingMemo,
                    )

                result shouldBe
                    MemoWorkspaceBlockMutationResult.MissingSourceSpan(
                        directory = MemoDirectoryType.MAIN,
                        filename = filename,
                        memoId = "blank-same-shard",
                    )
                storage.mainFiles[filename] shouldBe " \n"
            }
        }

        test("given restore removes required source block when file is missing then missing span is surfaced") {
            runTest {
                val storage = InMemoryWorkspaceMarkdownStorage()
                val fileStateDao = InMemoryWorkspaceLocalFileStateDao()
                val store = memoWorkspaceStore(storage = storage, fileStateDao = fileStateDao)
                val currentMemo = memo()
                val filename = "${currentMemo.dateKey}.md"

                val result =
                    store.removeMemoBlock(
                        directory = MemoDirectoryType.TRASH,
                        filename = filename,
                        memo = currentMemo.copy(isDeleted = true),
                    )

                result shouldBe
                    MemoWorkspaceBlockRemoval.MissingSourceSpan(
                        directory = MemoDirectoryType.TRASH,
                        filename = filename,
                        memoId = currentMemo.id,
                    )
            }
        }
    }
}

private fun memoWorkspaceStore(
    storage: InMemoryWorkspaceMarkdownStorage,
    fileStateDao: InMemoryWorkspaceLocalFileStateDao,
): MemoWorkspaceStore {
    val textProcessor = MemoTextProcessor()
    val identityPolicy = MemoIdentityPolicy()
    return testMemoWorkspaceStore(
        markdownStorageDataSource = storage,
        localFileStateDao = fileStateDao,
        textProcessor = textProcessor,
        memoIdentityPolicy = identityPolicy,
        parser = MarkdownParser(textProcessor, identityPolicy),
    )
}

private fun memoWorkspaceProjector(
    storage: InMemoryWorkspaceMarkdownStorage,
    fileStateDao: InMemoryWorkspaceLocalFileStateDao,
): MemoWorkspaceProjector {
    val textProcessor = MemoTextProcessor()
    val identityPolicy = MemoIdentityPolicy()
    return testMemoWorkspaceProjector(
        markdownStorageDataSource = storage,
        localFileStateDao = fileStateDao,
        textProcessor = textProcessor,
        memoIdentityPolicy = identityPolicy,
        parser = MarkdownParser(textProcessor, identityPolicy),
    )
}

private fun memo(
    id: String? = null,
    content: String = "body",
    rawContent: String = "- 09:00 body",
    deleted: Boolean = false,
): Memo {
    val parsedId =
        MarkdownParser(MemoTextProcessor(), MemoIdentityPolicy())
            .parseContent(rawContent, "2026_05_19")
            .single()
            .id
    return Memo(
        id = id ?: parsedId,
        timestamp = 1_779_152_400_000L,
        updatedAt = 1_779_152_400_000L,
        content = content,
        rawContent = rawContent,
        dateKey = "2026_05_19",
        isDeleted = deleted,
    )
}

private class InMemoryWorkspaceMarkdownStorage : MarkdownStorageDataSource {
    val mainFiles = linkedMapOf<String, String>()
    val trashFiles = linkedMapOf<String, String>()
    val documentContents = linkedMapOf<Pair<MemoDirectoryType, String>, String?>()
    val metadata = linkedMapOf<Pair<MemoDirectoryType, String>, FileMetadata>()

    override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> = metadata.values.toList()

    override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> =
        metadata
            .filterKeys { key -> key.first == directory }
            .map { (key, value) ->
                FileMetadataWithId(
                    filename = value.filename,
                    lastModified = value.lastModified,
                    documentId = key.second,
                )
            }

    override fun streamMetadataWithIdsIn(directory: MemoDirectoryType): Flow<FileMetadataWithId> =
        flow {
            listMetadataWithIdsIn(directory).forEach { metadata -> emit(metadata) }
        }

    override suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String? = documentContents[directory to documentId]

    override suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String? =
        when (directory) {
            MemoDirectoryType.MAIN -> mainFiles[filename]
            MemoDirectoryType.TRASH -> trashFiles[filename]
        }

    override suspend fun fingerprintFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String? = readFileIn(directory, filename)?.toByteArray(Charsets.UTF_8)?.md5Hex()

    override suspend fun readFile(uri: Uri): String? = null

    override suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean,
        uri: Uri?,
    ): String? {
        val files = filesFor(directory)
        val existing = files[filename]
        files[filename] =
            if (append && existing != null) {
                existing + content
            } else {
                content
            }
        return null
    }

    override suspend fun deleteFileIn(
        directory: MemoDirectoryType,
        filename: String,
        uri: Uri?,
    ) {
        filesFor(directory).remove(filename)
    }

    override suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): FileMetadata? = metadata[directory to filename]

    private fun filesFor(directory: MemoDirectoryType): MutableMap<String, String> =
        when (directory) {
            MemoDirectoryType.MAIN -> mainFiles
            MemoDirectoryType.TRASH -> trashFiles
        }
}

private class InMemoryWorkspaceLocalFileStateDao : LocalFileStateDao {
    private val states = linkedMapOf<Pair<String, Boolean>, LocalFileStateEntity>()

    override suspend fun getByFilename(
        filename: String,
        isTrash: Boolean,
    ): LocalFileStateEntity? = states[filename to isTrash]

    override suspend fun getAll(): List<LocalFileStateEntity> = states.values.toList()

    override suspend fun getAllByTrashStatus(isTrash: Boolean): List<LocalFileStateEntity> =
        states.values.filter { state -> state.isTrash == isTrash }

    override suspend fun upsert(entity: LocalFileStateEntity) {
        states[entity.filename to entity.isTrash] = entity
    }

    override suspend fun upsertAll(entities: List<LocalFileStateEntity>) {
        entities.forEach { entity -> upsert(entity) }
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
