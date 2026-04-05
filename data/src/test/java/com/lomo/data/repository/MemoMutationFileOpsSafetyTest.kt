package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.parser.MarkdownParser
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
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoMutationFileOps top-level helpers and MemoTrashMutationHandler safety branch.
 * - Behavior focus: cached-uri vs fallback reads, safe block rewrite persistence, append/upsert state behavior, persisted-uri filtering, and weak-match rejection safety.
 * - Observable outcomes: returned file content/result booleans, saved file payloads, LocalFileState upsert contents, and parsed-uri acceptance/rejection by scheme.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: parser internals, Room persistence details, and UI rendering.
 */
class MemoMutationFileOpsSafetyTest {
    @MockK(relaxed = true)
    private lateinit var fileDataSource: FileDataSource

    @MockK(relaxed = true)
    private lateinit var memoDao: TestMemoDaoSuite

    @MockK(relaxed = true)
    private lateinit var localFileStateDao: LocalFileStateDao

    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var memoVersionJournal: MemoVersionJournal

    private lateinit var runtime: MemoMutationRuntime
    private lateinit var storageFormatProvider: MemoStorageFormatProvider
    private lateinit var trashMutationHandler: MemoTrashMutationHandler

    @After
    fun tearDown() {
        unmockkStatic(android.net.Uri::class)
    }

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        val textProcessor = MemoTextProcessor()
        val memoIdentityPolicy = MemoIdentityPolicy()
        val parser = MarkdownParser(textProcessor, memoIdentityPolicy)
        coEvery { dataStore.storageFilenameFormat } returns flowOf("yyyy_MM_dd")
        coEvery { dataStore.storageTimestampFormat } returns flowOf("HH:mm")
        runtime =
            MemoMutationRuntime(
                markdownStorageDataSource = fileDataSource,
                daoBundle = testMemoMutationDaoBundle(memoDao),
                localFileStateDao = localFileStateDao,
                savePlanFactory = MemoSavePlanFactory(parser, textProcessor, memoIdentityPolicy),
                textProcessor = textProcessor,
                trashMutationHandler =
                    MemoTrashMutationHandler(
                        markdownStorageDataSource = fileDataSource,
                        mediaStorageDataSource = fileDataSource,
                        memoWriteDao = memoDao,
                        memoTagDao = memoDao,
                        memoFtsDao = memoDao,
                        memoTrashDao = memoDao,
                        memoSearchDao = memoDao,
                        localFileStateDao = localFileStateDao,
                        textProcessor = textProcessor,
                        memoVersionRecorder = AsyncMemoVersionRecorder(memoVersionJournal),
                    ),
                memoIdentityPolicy = memoIdentityPolicy,
                memoVersionRecorder = AsyncMemoVersionRecorder(memoVersionJournal),
            )
        storageFormatProvider = MemoStorageFormatProvider(dataStore)
        trashMutationHandler = runtime.trashMutationHandler
    }

    @Test
    fun `buildUpdatedFileContent returns null when only timestamp matches a different memo block`() =
        runTest {
            val memo =
                Memo(
                    id = "2026_03_26_10:00_deadbeef",
                    timestamp = 1_711_418_400_000L,
                    updatedAt = 1_711_418_400_000L,
                    content = "target memo",
                    rawContent = "- 10:00 target memo",
                    dateKey = "2026_03_26",
                )

            val result =
                buildUpdatedFileContent(
                    runtime = runtime,
                    storageFormatProvider = storageFormatProvider,
                    currentFileContent = "- 10:00 another memo",
                    memo = memo,
                    newContent = "after",
                )

            assertNull(result)
        }

    @Test
    fun `readCurrentMainFileContent prefers cached uri and falls back to main file when uri read misses`() =
        runTest {
            val filename = "2026_03_26.md"
            val parsedUri = mockk<android.net.Uri>(relaxed = true)
            mockkStatic(android.net.Uri::class)
            every { android.net.Uri.parse("content://memo/current") } returns parsedUri
            coEvery { localFileStateDao.getByFilename(filename, false) } returns
                LocalFileStateEntity(
                    filename = filename,
                    isTrash = false,
                    safUri = "content://memo/current",
                    lastKnownModifiedTime = 1L,
                )
            coEvery { fileDataSource.readFile(parsedUri) } returns null
            coEvery { fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename) } returns "fallback-body"

            val result = readCurrentMainFileContent(runtime, filename)

            assertEquals("fallback-body", result)
            coVerify(exactly = 1) { fileDataSource.readFile(parsedUri) }
            coVerify(exactly = 1) { fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename) }
        }

    @Test
    fun `readCurrentMainFileContent ignores non persisted uri and reads main file directly`() =
        runTest {
            val filename = "2026_03_26.md"
            coEvery { localFileStateDao.getByFilename(filename, false) } returns
                LocalFileStateEntity(
                    filename = filename,
                    isTrash = false,
                    safUri = "https://example.com/not-persisted",
                    lastKnownModifiedTime = 1L,
                )
            coEvery { fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename) } returns "direct-body"

            val result = readCurrentMainFileContent(runtime, filename)

            assertEquals("direct-body", result)
            coVerify(exactly = 0) { fileDataSource.readFile(any<android.net.Uri>()) }
            coVerify(exactly = 1) { fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename) }
        }

    @Test
    fun `flushMainMemoUpdateToFile returns false when current file is unavailable`() =
        runTest {
            val memo =
                Memo(
                    id = "memo_missing",
                    timestamp = 1_711_418_400_000L,
                    updatedAt = 1_711_418_400_000L,
                    content = "target",
                    rawContent = "- 10:00 target",
                    dateKey = "2026_03_26",
                )
            coEvery { localFileStateDao.getByFilename("${memo.dateKey}.md", false) } returns null
            coEvery { fileDataSource.readFileIn(MemoDirectoryType.MAIN, "${memo.dateKey}.md") } returns null

            val result =
                flushMainMemoUpdateToFile(
                    runtime = runtime,
                    storageFormatProvider = storageFormatProvider,
                    memo = memo,
                    newContent = "after",
                )

            assertEquals(false, result)
            coVerify(exactly = 0) {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "${memo.dateKey}.md",
                    content = any(),
                    append = false,
                )
            }
        }

    @Test
    fun `flushMainMemoUpdateToFile persists updated body and local state when block matches safely`() =
        runTest {
            val memo =
                Memo(
                    id = "2026_03_26_10:00_deadbeef",
                    timestamp = 1_711_418_400_000L,
                    updatedAt = 1_711_418_400_000L,
                    content = "before",
                    rawContent = "- 10:00 before",
                    dateKey = "2026_03_26",
                )
            val filename = "${memo.dateKey}.md"
            coEvery { localFileStateDao.getByFilename(filename, false) } returnsMany
                listOf(
                    null, // readCurrentMainFileContent
                    null, // upsertMainState existing row lookup
                )
            coEvery { fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename) } returns "- 10:00 before"
            coEvery {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = match { it.contains("after") },
                    append = false,
                )
            } returns "content://saved/memo"
            coEvery { fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename) } returns
                FileMetadata(filename, 456_789L)

            val result =
                flushMainMemoUpdateToFile(
                    runtime = runtime,
                    storageFormatProvider = storageFormatProvider,
                    memo = memo,
                    newContent = "after",
                )

            assertEquals(true, result)
            val captured = slot<LocalFileStateEntity>()
            coVerify(exactly = 1) { localFileStateDao.upsert(capture(captured)) }
            assertEquals(filename, captured.captured.filename)
            assertEquals(456_789L, captured.captured.lastKnownModifiedTime)
            assertEquals("content://saved/memo", captured.captured.safUri)
        }

    @Test
    fun `appendMainMemoContentAndUpdateState keeps existing saf uri when save returns null`() =
        runTest {
            val filename = "2026_03_27.md"
            val existing =
                LocalFileStateEntity(
                    filename = filename,
                    isTrash = false,
                    safUri = "content://memo/existing",
                    lastKnownModifiedTime = 111L,
                )
            val parsedUri = mockk<android.net.Uri>(relaxed = true)
            mockkStatic(android.net.Uri::class)
            every { android.net.Uri.parse("content://memo/existing") } returns parsedUri
            coEvery { localFileStateDao.getByFilename(filename, false) } returnsMany listOf(existing, existing)
            coEvery {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = "\n- 10:00 appended",
                    append = true,
                    uri = parsedUri,
                )
            } returns null
            coEvery { fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename) } returns
                FileMetadata(filename, 999L)

            appendMainMemoContentAndUpdateState(
                runtime = runtime,
                filename = filename,
                rawContent = "- 10:00 appended",
            )

            val captured = slot<LocalFileStateEntity>()
            coVerify(exactly = 1) { localFileStateDao.upsert(capture(captured)) }
            assertEquals("content://memo/existing", captured.captured.safUri)
            assertEquals(999L, captured.captured.lastKnownModifiedTime)
        }

    @Test
    fun `toPersistedUriOrNull accepts content and file schemes only`() {
        val contentUri = mockk<android.net.Uri>(relaxed = true)
        val fileUri = mockk<android.net.Uri>(relaxed = true)
        mockkStatic(android.net.Uri::class)
        every { android.net.Uri.parse("content://memo/1") } returns contentUri
        every { android.net.Uri.parse("file:///tmp/memo.md") } returns fileUri

        assertSame(contentUri, "content://memo/1".toPersistedUriOrNull())
        assertSame(fileUri, "file:///tmp/memo.md".toPersistedUriOrNull())
        assertNull("https://example.com/memo".toPersistedUriOrNull())
        assertNull((null as String?).toPersistedUriOrNull())
        assertTrue(" ".toPersistedUriOrNull() == null)
    }

    @Test
    fun `moveToTrashFileOnly returns false when only timestamp matches a different memo block`() =
        runTest {
            val memo =
                Memo(
                    id = "2026_03_26_10:00_deadbeef",
                    timestamp = 1_711_418_400_000L,
                    updatedAt = 1_711_418_400_000L,
                    content = "target memo",
                    rawContent = "- 10:00 target memo",
                    dateKey = "2026_03_26",
                )
            coEvery { localFileStateDao.getByFilename("2026_03_26.md", false) } returns null
            coEvery { fileDataSource.readFileIn(any(), any()) } returns "- 10:00 another memo"

            val result = trashMutationHandler.moveToTrashFileOnly(memo)

            assertEquals(false, result)
        }
}
