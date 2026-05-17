package com.lomo.data.repository


import com.lomo.data.local.dao.PendingSyncConflictDao
import com.lomo.data.local.entity.PendingSyncConflictEntity
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSessionKind
import com.lomo.domain.model.SyncConflictSet
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Test Contract:
 * - Unit under test: RoomPendingSyncConflictStore
 * - Behavior focus: DB-backed pending conflict persistence should round-trip backend/session context and clear sessions independently.
 * - Observable outcomes: restored SyncConflictSet payload and backend-scoped clear behavior.
 * - Red phase: Fails before the fix because pending sync conflicts are not persisted in Room at all, so there is no store capable of reconstructing a saved conflict session.
 * - Excludes: Room framework internals, repository orchestration, and UI rendering.
 */
class PendingSyncConflictStoreTest : DataFunSpec() {
    init {
        test("write and read round trips first sync preview conflict set") { `write and read round trips first sync preview conflict set`() }

        test("clear removes only targeted backend session") { `clear removes only targeted backend session`() }
    }


    private fun `write and read round trips first sync preview conflict set`() =
        runTest {
            val dao = FakePendingSyncConflictDao()
            val store = RoomPendingSyncConflictStore(dao)
            val conflictSet =
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
                    sessionKind = SyncConflictSessionKind.INITIAL_SYNC_PREVIEW,
                )

            store.write(conflictSet)

            store.read(SyncBackendType.S3) shouldBe conflictSet
        }

    private fun `clear removes only targeted backend session`() =
        runTest {
            val dao = FakePendingSyncConflictDao()
            val store = RoomPendingSyncConflictStore(dao)
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

            store.read(SyncBackendType.S3).shouldBeNull()
            store.read(SyncBackendType.WEBDAV)?.source shouldBe SyncBackendType.WEBDAV
        }
}

private class FakePendingSyncConflictDao : PendingSyncConflictDao {
    private val entries = linkedMapOf<String, PendingSyncConflictEntity>()

    override suspend fun getByBackend(backend: String): PendingSyncConflictEntity? = entries[backend]

    override suspend fun upsert(entity: PendingSyncConflictEntity) {
        entries[entity.backend] = entity
    }

    override suspend fun deleteByBackend(backend: String) {
        entries.remove(backend)
    }
}
