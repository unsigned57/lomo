package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.memo.MemoIdentityPolicy
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileContent
import com.lomo.data.source.FileDataSource
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Unit tests for MemoSynchronizer. These tests verify the synchronization logic between file system
 * and database.
 */
class MemoSynchronizerTest {
    @MockK private lateinit var fileDataSource: FileDataSource

    @MockK private lateinit var memoDao: MemoDao

    @MockK private lateinit var localFileStateDao: LocalFileStateDao

    @MockK private lateinit var dataStore: com.lomo.data.local.datastore.LomoDataStore

    private lateinit var processor: MemoTextProcessor
    private lateinit var parser: MarkdownParser
    private lateinit var memoIdentityPolicy: MemoIdentityPolicy
    private lateinit var synchronizer: MemoSynchronizer

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)
        processor = MemoTextProcessor()
        memoIdentityPolicy = MemoIdentityPolicy()
        parser = MarkdownParser(processor, memoIdentityPolicy)

        // Mock default formats
        coEvery { dataStore.storageFilenameFormat } returns
            kotlinx.coroutines.flow.flowOf("yyyy_MM_dd")
        coEvery { dataStore.storageTimestampFormat } returns
            kotlinx.coroutines.flow.flowOf("HH:mm:ss")

        val mutationHandler =
            MemoMutationHandler(
                fileDataSource,
                memoDao,
                localFileStateDao,
                MemoSavePlanFactory(parser, processor, memoIdentityPolicy),
                processor,
                dataStore,
                MemoTrashMutationHandler(
                    fileDataSource,
                    memoDao,
                    localFileStateDao,
                    processor,
                ),
                ResolveMemoUpdateActionUseCase(),
                memoIdentityPolicy,
            )
        val refreshEngine =
            MemoRefreshEngine(
                fileDataSource,
                memoDao,
                localFileStateDao,
                parser,
                MemoRefreshPlanner(),
                MemoRefreshParserWorker(fileDataSource, memoDao, parser),
                MemoRefreshDbApplier(memoDao, localFileStateDao) { block -> block() },
            )

        synchronizer =
            MemoSynchronizer(
                refreshEngine,
                mutationHandler,
            )
    }

    @Test
    fun `refresh syncs new memos from files to database`() =
        runTest {
            // MemoSynchronizer.refresh() uses incremental sync with metadata + document ID
            val metadata =
                FileMetadataWithId(
                    filename = "2024_01_15.md",
                    lastModified = System.currentTimeMillis(),
                    documentId = "doc123",
                )
            val fileContent = "- 10:30:00 Test memo content"

            // Mock the incremental sync path
            coEvery { fileDataSource.listMetadataWithIdsIn(MemoDirectoryType.MAIN) } returns listOf(metadata)
            coEvery { fileDataSource.listMetadataWithIdsIn(MemoDirectoryType.TRASH) } returns emptyList()
            coEvery { fileDataSource.readFileByDocumentIdIn(MemoDirectoryType.MAIN, "doc123") } returns fileContent
            coEvery { localFileStateDao.getAll() } returns emptyList()

            synchronizer.refresh()

            // Should insert the parsed memo
            coVerify { memoDao.insertMemos(any()) }
        }

    @Test
    fun `refresh handles empty file list`() =
        runTest {
            coEvery { fileDataSource.listMetadataWithIdsIn(MemoDirectoryType.MAIN) } returns emptyList()
            coEvery { fileDataSource.listMetadataWithIdsIn(MemoDirectoryType.TRASH) } returns emptyList()
            coEvery { localFileStateDao.getAll() } returns emptyList()

            // Should not crash
            synchronizer.refresh()
        }

    @Test
    fun `refresh with target filename uses listFiles path`() =
        runTest {
            // When targetFilename is specified, refresh uses listFiles instead of incremental sync
            val fileContent =
                FileContent(
                    filename = "2024_01_15.md",
                    content = "- 10:30:00 Test memo content",
                    lastModified = System.currentTimeMillis(),
                )

            coEvery { fileDataSource.listFilesIn(MemoDirectoryType.MAIN, "2024_01_15.md") } returns listOf(fileContent)

            synchronizer.refresh(targetFilename = "2024_01_15.md")

            // Should insert the parsed memo via syncFiles
            coVerify { memoDao.insertMemos(any()) }
        }

    @Test
    fun `refresh does not clear when files exist but unchanged`() =
        runTest {
            // When files exist but are unchanged (in sync metadata), nothing should be deleted
            val metadata =
                FileMetadataWithId(
                    filename = "2024_01_15.md",
                    lastModified = 1000L,
                    documentId = "doc123",
                )
            val syncEntity =
                LocalFileStateEntity(
                    filename = "2024_01_15.md",
                    lastKnownModifiedTime = 1000L, // Same as metadata - file unchanged
                )

            coEvery { fileDataSource.listMetadataWithIdsIn(MemoDirectoryType.MAIN) } returns listOf(metadata)
            coEvery { fileDataSource.listMetadataWithIdsIn(MemoDirectoryType.TRASH) } returns emptyList()
            coEvery { localFileStateDao.getAll() } returns listOf(syncEntity)

            synchronizer.refresh()

            // Should NOT insert or delete anything (file unchanged)
            coVerify(exactly = 0) { memoDao.insertMemos(any()) }
            coVerify(exactly = 0) { memoDao.clearAll() }
        }

    @Test
    fun `saveMemo creates new memo file entry`() =
        runTest {
            val timestamp = System.currentTimeMillis()
            val content = "New memo content"

            // Mock that memo doesn't exist (for unique ID check)
            coEvery { memoDao.getMemo(any()) } returns null
            coEvery {
                fileDataSource.saveFileIn(
                    MemoDirectoryType.MAIN,
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns "uri"

            synchronizer.saveMemo(content, timestamp)

            coVerify {
                fileDataSource.saveFileIn(
                    MemoDirectoryType.MAIN,
                    any(),
                    any(),
                    eq(true),
                    any(),
                )
            } // append = true
        }

    @Test
    fun `saveMemo appends to existing file`() =
        runTest {
            val timestamp = System.currentTimeMillis()
            val content = "Another memo"

            // Mock that memo doesn't exist (for unique ID check)
            coEvery { memoDao.getMemo(any()) } returns null
            coEvery {
                fileDataSource.saveFileIn(
                    MemoDirectoryType.MAIN,
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns "uri"

            synchronizer.saveMemo(content, timestamp)

            // saveMemo always appends
            coVerify {
                fileDataSource.saveFileIn(
                    MemoDirectoryType.MAIN,
                    any(),
                    any(),
                    eq(true),
                    any(),
                )
            }
        }

    @Test
    fun `saveMemo stores canonical timestamp for HH_mm format`() =
        runTest {
            val zone = ZoneId.systemDefault()
            val inputTimestamp =
                LocalDateTime
                    .of(2024, 1, 15, 12, 30, 45, 123_000_000)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
            val expectedTimestamp =
                LocalDateTime
                    .of(2024, 1, 15, 12, 30, 0, 0)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()

            coEvery { dataStore.storageFilenameFormat } returns kotlinx.coroutines.flow.flowOf("yyyy_MM_dd")
            coEvery { dataStore.storageTimestampFormat } returns kotlinx.coroutines.flow.flowOf("HH:mm")
            coEvery { memoDao.getMemo(any()) } returns null
            coEvery { fileDataSource.readFileIn(MemoDirectoryType.MAIN, "2024_01_15.md") } returns null
            coEvery {
                fileDataSource.saveFileIn(
                    MemoDirectoryType.MAIN,
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns "uri"
            coEvery {
                fileDataSource.getFileMetadataIn(
                    MemoDirectoryType.MAIN,
                    "2024_01_15.md",
                )
            } returns FileMetadata("2024_01_15.md", 1000L)
            coEvery { localFileStateDao.getByFilename("2024_01_15.md", false) } returns null

            val memoEntitySlot = slot<com.lomo.data.local.entity.MemoEntity>()
            coEvery { memoDao.insertMemo(capture(memoEntitySlot)) } returns Unit

            synchronizer.saveMemo("canonical time", inputTimestamp)

            assertEquals(expectedTimestamp, memoEntitySlot.captured.timestamp)
        }
}
