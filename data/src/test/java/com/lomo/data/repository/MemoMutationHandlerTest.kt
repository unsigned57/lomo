package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.MemoIdentityPolicy
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/*
 * Test Contract:
 * - Unit under test: MemoMutationHandler
 * - Behavior focus: metadata persistence, updatedAt refresh, storage format caching, and unsafe rewrite rejection.
 * - Observable outcomes: thrown unsafe mutation exception, LocalFileState writes, persisted updatedAt, and cached flow collection counts.
 * - Red phase: Fails before the fix because updateMemo silently no-ops when a destructive-safe rewrite cannot locate the target memo block.
 * - Excludes: Room transaction internals, UI rendering, and retired legacy-capture storage mechanics.
 */
class MemoMutationHandlerTest {
    @MockK(relaxed = true)
    private lateinit var fileDataSource: FileDataSource

    @MockK(relaxed = true)
    private lateinit var dao: TestMemoDaoSuite

    @MockK(relaxed = true)
    private lateinit var localFileStateDao: LocalFileStateDao

    @MockK(relaxed = true)
    private lateinit var savePlanFactory: MemoSavePlanFactory

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var trashMutationHandler: MemoTrashMutationHandler

    @MockK(relaxed = true)
    private lateinit var memoVersionJournal: MemoVersionJournal

    private lateinit var handler: MemoMutationHandler

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        every { dataStore.storageFilenameFormat } returns flowOf("yyyy_MM_dd")
        every { dataStore.storageTimestampFormat } returns flowOf("HH:mm:ss")
        handler =
            MemoMutationHandler(
                markdownStorageDataSource = fileDataSource,
                daoBundle = testMemoMutationDaoBundle(dao),
                localFileStateDao = localFileStateDao,
                savePlanFactory = savePlanFactory,
                textProcessor = MemoTextProcessor(),
                dataStore = dataStore,
                trashMutationHandler = trashMutationHandler,
                memoIdentityPolicy = MemoIdentityPolicy(),
                memoVersionJournal = memoVersionJournal,
            )
    }

    @Test
    fun `flushSavedMemoToFile stores file metadata modified time in local file state`() =
        runTest {
            val filename = "2024_01_15.md"
            val savePlan =
                MemoSavePlan(
                    filename = filename,
                    dateKey = "2024_01_15",
                    timestamp = 1_700_000_000_000L,
                    rawContent = "- 10:00 test",
                    memo =
                        Memo(
                            id = "memo_1",
                            timestamp = 1_700_000_000_000L,
                            content = "test",
                            rawContent = "- 10:00 test",
                            dateKey = "2024_01_15",
                        ),
                )
            coEvery { localFileStateDao.getByFilename(filename, false) } returns null
            coEvery {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = any(),
                    append = true,
                    uri = null,
                )
            } returns null
            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename)
            } returns FileMetadata(filename, 456_789L)

            handler.flushSavedMemoToFile(savePlan)

            val captured = slot<LocalFileStateEntity>()
            coVerify(exactly = 1) {
                localFileStateDao.upsert(capture(captured))
            }
            assertEquals(456_789L, captured.captured.lastKnownModifiedTime)
        }

    @Test
    fun `flushMemoFileOutbox create stores metadata modified time in local file state`() =
        runTest {
            val filename = "2024_01_16.md"
            val outbox =
                MemoFileOutboxEntity(
                    operation = MemoFileOutboxOp.CREATE,
                    memoId = "memo_2",
                    memoDate = "2024_01_16",
                    memoTimestamp = 1_700_100_000_000L,
                    memoRawContent = "- 11:00 from outbox",
                    newContent = "from outbox",
                    createRawContent = "- 11:00 from outbox",
                )

            coEvery { localFileStateDao.getByFilename(filename, false) } returns null
            coEvery {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = any(),
                    append = true,
                    uri = null,
                )
            } returns "content://saved/memo_2"
            coEvery {
                fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename)
            } returns FileMetadata(filename, 987_654L)

            val result = handler.flushMemoFileOutbox(outbox)

            assertTrue(result)
            val captured = slot<LocalFileStateEntity>()
            coVerify(exactly = 1) {
                localFileStateDao.upsert(capture(captured))
            }
            assertEquals(987_654L, captured.captured.lastKnownModifiedTime)
            assertEquals("content://saved/memo_2", captured.captured.safUri)
        }

    @Test
    fun `updateMemoInDb refreshes updatedAt`() =
        runTest {
            val sourceMemo =
                Memo(
                    id = "memo_update",
                    timestamp = 1_700_000_000_000L,
                    updatedAt = 1_700_000_000_000L,
                    content = "before",
                    rawContent = "- 10:00 before",
                    dateKey = "2024_01_15",
                )
            every { dataStore.storageFilenameFormat } returns flowOf("yyyy_MM_dd")
            every { dataStore.storageTimestampFormat } returns flowOf("HH:mm")
            coEvery { dao.getMemo(sourceMemo.id) } returns
                com.lomo.data.local.entity.MemoEntity
                    .fromDomain(sourceMemo)

            val persistedMemo = slot<com.lomo.data.local.entity.MemoEntity>()
            coEvery {
                dao.insertMemo(capture(persistedMemo))
            } just runs
            coEvery { dao.insertMemoFileOutbox(any()) } returns 1L

            val outboxId = handler.updateMemoInDb(sourceMemo, "after")

            assertEquals(1L, outboxId)
            assertTrue(persistedMemo.captured.updatedAt > sourceMemo.updatedAt)
        }

    @Test(expected = UnsafeWorkspaceMutationException::class)
    fun `updateMemo throws when safe rewrite cannot locate target memo block`() =
        runTest {
            val sourceMemo =
                Memo(
                    id = "2026_03_26_10:00_deadbeef",
                    timestamp = 1_711_418_400_000L,
                    updatedAt = 1_711_418_400_000L,
                    content = "before",
                    rawContent = "- 10:00 before",
                    dateKey = "2026_03_26",
                )
            coEvery { dao.getMemo(sourceMemo.id) } returns com.lomo.data.local.entity.MemoEntity.fromDomain(sourceMemo)
            coEvery { localFileStateDao.getByFilename("${sourceMemo.dateKey}.md", false) } returns null
            coEvery { fileDataSource.readFileIn(MemoDirectoryType.MAIN, "${sourceMemo.dateKey}.md") } returns "- 10:00 another memo"

            handler.updateMemo(sourceMemo, "after")
        }

    @Test
    fun `saveMemoInDb reuses cached storage formats across saves`() =
        runTest {
            val filenameCollections = AtomicInteger(0)
            val timestampCollections = AtomicInteger(0)
            every { dataStore.storageFilenameFormat } returns
                flow {
                    check(filenameCollections.incrementAndGet() == 1)
                    emit("yyyy_MM_dd")
                }
            every { dataStore.storageTimestampFormat } returns
                flow {
                    check(timestampCollections.incrementAndGet() == 1)
                    emit("HH:mm")
                }

            val localHandler =
                MemoMutationHandler(
                    markdownStorageDataSource = fileDataSource,
                    daoBundle = testMemoMutationDaoBundle(dao),
                    localFileStateDao = localFileStateDao,
                    savePlanFactory = savePlanFactory,
                    textProcessor = MemoTextProcessor(),
                    dataStore = dataStore,
                    trashMutationHandler = trashMutationHandler,
                    memoIdentityPolicy = MemoIdentityPolicy(),
                    memoVersionJournal = memoVersionJournal,
                )

            withTimeout(2_000) {
                while (filenameCollections.get() != 1 || timestampCollections.get() != 1) {
                    delay(10)
                }
            }

            coEvery { dao.countMemoIdCollisions(any(), any()) } returns 0
            coEvery { dao.countMemosByIdGlob(any()) } returns 0
            coEvery { dao.insertMemoFileOutbox(any()) } returnsMany listOf(1L, 2L)
            coEvery {
                savePlanFactory.create(
                    content = any(),
                    timestamp = any(),
                    filenameFormat = any(),
                    timestampFormat = any(),
                    existingFileContent = any(),
                    precomputedSameTimestampCount = any(),
                    precomputedCollisionCount = any(),
                )
            } answers {
                val timestamp = secondArg<Long>()
                MemoSavePlan(
                    filename = "2024_01_15.md",
                    dateKey = "2024_01_15",
                    timestamp = timestamp,
                    rawContent = "- 10:00 test",
                    memo =
                        Memo(
                            id = "memo_$timestamp",
                            timestamp = timestamp,
                            content = firstArg(),
                            rawContent = "- 10:00 test",
                            dateKey = "2024_01_15",
                        ),
                )
            }

            localHandler.saveMemoInDb("first", 1_700_000_000_000L)
            localHandler.saveMemoInDb("second", 1_700_000_100_000L)

            assertEquals(1, filenameCollections.get())
            assertEquals(1, timestampCollections.get())
        }
}
