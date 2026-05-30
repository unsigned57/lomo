package com.lomo.data.repository

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.data.local.dao.PendingSyncConflictDao
import com.lomo.data.local.entity.PendingSyncConflictEntity
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.repository.WorkspaceSyncGeneration
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/*
 * Behavior Contract:
 * - Unit under test: RoomPendingSyncConflictStore
 * - Behavior focus: DB-backed pending conflict persistence stores durable descriptors, never full local/remote content snapshots.
 * - Observable outcomes: descriptor metadata, absent raw content in payload JSON, and backend-scoped clear behavior.
 * - TDD proof: Fails before the fix because payload JSON contains full localContent and remoteContent strings.
 * - Excludes: Room framework internals, repository orchestration, and UI rendering.
 */
class PendingSyncConflictStoreTest : DataFunSpec() {
    init {
        test("write stores descriptor without full conflict content") {
            `write stores descriptor without full conflict content`()
        }

        test("clear removes only targeted backend session") { `clear removes only targeted backend session`() }
    }


    private fun `write stores descriptor without full conflict content`() =
        runTest {
            val dao = FakePendingSyncConflictDao()
            val store = RoomPendingSyncConflictStore(dao, ConflictStoreTestGenerationProvider())
            val largeLocal = "local-large-content-".repeat(512)
            val largeRemote = "remote-large-content-".repeat(512)
            val conflict =
                SyncConflictSet(
                    source = SyncBackendType.S3,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "lomo/memo/note.md",
                                localContent = largeLocal,
                                remoteContent = largeRemote,
                                isBinary = false,
                                localLastModified = 10L,
                                remoteLastModified = 20L,
                            ),
                        ),
                    timestamp = 123L,
                )

            store.write(conflict)

            val descriptor = requireNotNull(store.readDescriptor(SyncBackendType.S3))
            descriptor.source shouldBe SyncBackendType.S3
            descriptor.files.single().relativePath shouldBe "lomo/memo/note.md"
            descriptor.files.single().local.lastModified shouldBe 10L
            descriptor.files.single().remote.lastModified shouldBe 20L
            descriptor.files.single().local.size shouldBe largeLocal.toByteArray(Charsets.UTF_8).size.toLong()
            descriptor.files.single().remote.size shouldBe largeRemote.toByteArray(Charsets.UTF_8).size.toLong()
            descriptor.files.single().local.etag shouldBe descriptor.files.single().local.contentHash
            descriptor.files.single().remote.etag shouldBe descriptor.files.single().remote.contentHash
            dao.payloadFor(SyncBackendType.S3).shouldNotContain(largeLocal)
            dao.payloadFor(SyncBackendType.S3).shouldNotContain(largeRemote)
        }

    private fun `clear removes only targeted backend session`() =
        runTest {
            val dao = FakePendingSyncConflictDao()
            val store = RoomPendingSyncConflictStore(dao, ConflictStoreTestGenerationProvider())
            store.write(
                SyncConflictSet(
                    source = SyncBackendType.S3,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "lomo/memo/note.md",
                                localContent = "local",
                                remoteContent = "remote",
                                isBinary = false,
                            ),
                        ),
                    timestamp = 123L,
                ),
            )
            store.write(
                SyncConflictSet(
                    source = SyncBackendType.WEBDAV,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "lomo/memo/other.md",
                                localContent = "left",
                                remoteContent = "right",
                                isBinary = false,
                            ),
                        ),
                    timestamp = 456L,
                ),
            )

            store.clear(SyncBackendType.S3)

            store.readDescriptor(SyncBackendType.S3).shouldBeNull()
            store.readDescriptor(SyncBackendType.WEBDAV)?.source shouldBe SyncBackendType.WEBDAV
        }
}

private class FakePendingSyncConflictDao : PendingSyncConflictDao {
    private val entries = linkedMapOf<Pair<String, String>, PendingSyncConflictEntity>()

    override suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncConflictEntity? = entries[workspaceGeneration to backend]

    override suspend fun upsert(entity: PendingSyncConflictEntity) {
        entries[entity.workspaceGeneration to entity.backend] = entity
    }

    override suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    ) {
        entries.remove(workspaceGeneration to backend)
    }

    fun payloadFor(source: SyncBackendType): String =
        requireNotNull(entries["workspace-test" to source.name]).payloadJson
}

private class ConflictStoreTestGenerationProvider : WorkspaceSyncGenerationProvider {
    override suspend fun activeGeneration(): WorkspaceSyncGeneration = WorkspaceSyncGeneration("workspace-test")
}
