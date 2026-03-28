package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.dao.MemoFtsDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoRefreshEngine
 * - Behavior focus: refresh deletion planning when file listings temporarily miss previously known memo files.
 * - Observable outcomes: filesToDeleteInDb forwarded to MemoRefreshDbApplier and metadata confirmation lookups.
 * - Red phase: Fails before the fix when a file missing from one metadata listing is still resolvable by filename lookup but is deleted from the DB refresh set anyway.
 * - Excludes: Room transaction behavior, parser internals, and storage backend implementation details.
 */
class MemoRefreshEngineTest {
    @MockK
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var memoWriteDao: MemoWriteDao

    @MockK(relaxed = true)
    private lateinit var memoTagDao: MemoTagDao

    @MockK(relaxed = true)
    private lateinit var memoFtsDao: MemoFtsDao

    @MockK(relaxed = true)
    private lateinit var memoTrashDao: MemoTrashDao

    @MockK
    private lateinit var localFileStateDao: LocalFileStateDao

    @MockK(relaxed = true)
    private lateinit var parser: MarkdownParser

    @MockK
    private lateinit var refreshParserWorker: MemoRefreshParserWorker

    @MockK(relaxed = true)
    private lateinit var refreshDbApplier: MemoRefreshDbApplier

    private lateinit var engine: MemoRefreshEngine

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        engine =
            MemoRefreshEngine(
                markdownStorageDataSource = markdownStorageDataSource,
                memoWriteDao = memoWriteDao,
                memoTagDao = memoTagDao,
                memoFtsDao = memoFtsDao,
                memoTrashDao = memoTrashDao,
                localFileStateDao = localFileStateDao,
                parser = parser,
                refreshPlanner = MemoRefreshPlanner,
                refreshParserWorker = refreshParserWorker,
                refreshDbApplier = refreshDbApplier,
            )
    }

    @Test
    fun `refresh does not delete db records when missing main file is still resolvable`() =
        runTest {
            val knownState =
                LocalFileStateEntity(
                    filename = "2026_03_25.md",
                    isTrash = false,
                    safUri = "content://lomo/2026_03_25",
                    lastKnownModifiedTime = 1_000L,
                )
            val parseResult = emptyParseResult()
            val capturedDeleteSet = slot<Set<Pair<String, Boolean>>>()

            coEvery { localFileStateDao.getAll() } returns listOf(knownState)
            coEvery { markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.MAIN) } returns emptyList()
            coEvery { markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.TRASH) } returns emptyList()
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "2026_03_25.md")
            } returns FileMetadata(filename = "2026_03_25.md", lastModified = 2_000L)
            coEvery { refreshParserWorker.parse(emptyList(), emptyList()) } returns parseResult

            engine.refresh()

            coVerify(exactly = 1) {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "2026_03_25.md")
            }
            coVerify(exactly = 1) { refreshDbApplier.apply(parseResult, capture(capturedDeleteSet)) }
            assertEquals(emptySet<Pair<String, Boolean>>(), capturedDeleteSet.captured)
        }

    @Test
    fun `refresh keeps confirmed missing main file in delete set`() =
        runTest {
            val knownState =
                LocalFileStateEntity(
                    filename = "2026_03_25.md",
                    isTrash = false,
                    safUri = null,
                    lastKnownModifiedTime = 1_000L,
                )
            val parseResult = emptyParseResult()
            val capturedDeleteSet = slot<Set<Pair<String, Boolean>>>()

            coEvery { localFileStateDao.getAll() } returns listOf(knownState)
            coEvery { markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.MAIN) } returns emptyList()
            coEvery { markdownStorageDataSource.listMetadataWithIdsIn(MemoDirectoryType.TRASH) } returns emptyList()
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "2026_03_25.md")
            } returns null
            coEvery { refreshParserWorker.parse(emptyList(), emptyList()) } returns parseResult

            engine.refresh()

            coVerify(exactly = 1) { refreshDbApplier.apply(parseResult, capture(capturedDeleteSet)) }
            assertEquals(setOf("2026_03_25.md" to false), capturedDeleteSet.captured)
        }

    private fun emptyParseResult() =
        MemoRefreshParseResult(
            mainMemos = emptyList(),
            trashMemos = emptyList(),
            metadataToUpdate = emptyList(),
            mainDatesToReplace = emptySet(),
            trashDatesToReplace = emptySet(),
        )
}
