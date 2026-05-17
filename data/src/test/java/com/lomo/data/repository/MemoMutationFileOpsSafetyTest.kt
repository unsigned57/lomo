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
import java.io.File
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Test Contract:
 * - Unit under test: MemoMutationFileOps top-level helpers and MemoTrashMutationHandler safety branch.
 * - Behavior focus: cached-uri vs fallback reads, safe block rewrite persistence, append/upsert state behavior, persisted-uri filtering, and weak-match rejection safety.
 * - Observable outcomes: returned file content/result booleans, saved file payloads, LocalFileState upsert contents, and parsed-uri acceptance/rejection by scheme.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: parser internals, Room persistence details, and UI rendering.
 */
class MemoMutationFileOpsSafetyTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tearDown()
        }

        test("buildUpdatedFileContent returns null when only timestamp matches a different memo block") { `buildUpdatedFileContent returns null when only timestamp matches a different memo block`() }

        test("readCurrentMainFileContent prefers cached uri and falls back to main file when uri read misses") { `readCurrentMainFileContent prefers cached uri and falls back to main file when uri read misses`() }

        test("readCurrentMainFileContent ignores non persisted uri and reads main file directly") { `readCurrentMainFileContent ignores non persisted uri and reads main file directly`() }

        test("flushMainMemoUpdateToFile returns false when current file is unavailable") { `flushMainMemoUpdateToFile returns false when current file is unavailable`() }

        test("flushMainMemoUpdateToFile persists updated body and local state when block matches safely") { `flushMainMemoUpdateToFile persists updated body and local state when block matches safely`() }

        test("persistUpdatedMainFile reuses direct save path metadata without extra lookup") { `persistUpdatedMainFile reuses direct save path metadata without extra lookup`() }

        test("appendMainMemoContentAndUpdateState keeps existing saf uri when save returns null") { `appendMainMemoContentAndUpdateState keeps existing saf uri when save returns null`() }

        test("appendMainMemoContentAndUpdateState reuses direct save path metadata without extra lookup") { `appendMainMemoContentAndUpdateState reuses direct save path metadata without extra lookup`() }

        test("toPersistedUriOrNull accepts content and file schemes only") { `toPersistedUriOrNull accepts content and file schemes only`() }

        test("moveToTrashFileOnly returns false when only timestamp matches a different memo block") { `moveToTrashFileOnly returns false when only timestamp matches a different memo block`() }
    }


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

    private fun tearDown() {
        unmockkStatic(android.net.Uri::class)
    }

    private fun setUp() {
        MockKAnnotations.init(this)
        val textProcessor = MemoTextProcessor()
        val memoIdentityPolicy = MemoIdentityPolicy()
        val parser = MarkdownParser(textProcessor, memoIdentityPolicy)
        coEvery { dataStore.storageFilenameFormat } returns flowOf("yyyy_MM_dd")
        coEvery { dataStore.storageTimestampFormat } returns flowOf("HH:mm")
        runtime =
            MemoMutationRuntime(
                markdownStorageDataSource = fileDataSource,
                mediaStorageDataSource = fileDataSource,
                daoBundle = testMemoMutationDaoBundle(memoDao),
                memoSearchDao = memoDao,
                localFileStateDao = localFileStateDao,
                savePlanFactory = MemoSavePlanFactory(parser, textProcessor, memoIdentityPolicy),
                textProcessor = textProcessor,
                trashMutationHandler =
                    MemoTrashMutationHandler(
                        markdownStorageDataSource = fileDataSource,
                        mediaStorageDataSource = fileDataSource,
                        memoWriteDao = memoDao,
                        memoTagDao = memoDao,
                        memoImageDao = memoDao,
                        memoTrashDao = memoDao,
                        memoSearchDao = memoDao,
                        localFileStateDao = localFileStateDao,
                        textProcessor = textProcessor,
                        memoVersionRecorder = AsyncMemoVersionRecorder(memoVersionJournal),
                ),
                memoIdentityPolicy = memoIdentityPolicy,
                memoVersionRecorder = AsyncMemoVersionRecorder(memoVersionJournal),
                s3LocalChangeRecorder = NoOpS3LocalChangeRecorder,
                webDavLocalChangeRecorder = NoOpWebDavLocalChangeRecorder,
                mutationGate = MemoMutationGate(),
            )
        storageFormatProvider = MemoStorageFormatProvider(dataStore)
        trashMutationHandler = runtime.trashMutationHandler
    }

    private fun `buildUpdatedFileContent returns null when only timestamp matches a different memo block`() =
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
                    storageFormatProvider = storageFormatProvider,
                    currentFileContent = "- 10:00 another memo",
                    memo = memo,
                    newContent = "after",
                )

            result.shouldBeNull()
        }

    private fun `readCurrentMainFileContent prefers cached uri and falls back to main file when uri read misses`() =
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

            result shouldBe "fallback-body"
            coVerify(exactly = 1) { fileDataSource.readFile(parsedUri) }
            coVerify(exactly = 1) { fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename) }
        }

    private fun `readCurrentMainFileContent ignores non persisted uri and reads main file directly`() =
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

            result shouldBe "direct-body"
            coVerify(exactly = 0) { fileDataSource.readFile(any<android.net.Uri>()) }
            coVerify(exactly = 1) { fileDataSource.readFileIn(MemoDirectoryType.MAIN, filename) }
        }

    private fun `flushMainMemoUpdateToFile returns false when current file is unavailable`() =
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

            result shouldBe false
            coVerify(exactly = 0) {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "${memo.dateKey}.md",
                    content = any(),
                    append = false,
                )
            }
        }

    private fun `flushMainMemoUpdateToFile persists updated body and local state when block matches safely`() =
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

            result shouldBe true
            val captured = slot<LocalFileStateEntity>()
            coVerify(exactly = 1) { localFileStateDao.upsert(capture(captured)) }
            captured.captured.filename shouldBe filename
            captured.captured.lastKnownModifiedTime shouldBe 456_789L
            captured.captured.safUri shouldBe "content://saved/memo"
        }

    private fun `persistUpdatedMainFile reuses direct save path metadata without extra lookup`() =
        runTest {
            val filename = "2026_03_28.md"
            val savedFile =
                File.createTempFile("memo-mutation-file-ops", ".md").apply {
                    writeText("after")
                    setLastModified(456_789L)
                    deleteOnExit()
                }
            coEvery { localFileStateDao.getByFilename(filename, false) } returns null
            coEvery {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = "after",
                    append = false,
                )
            } returns savedFile.absolutePath

            persistUpdatedMainFile(
                runtime = runtime,
                filename = filename,
                updatedContent = "after",
            )

            val captured = slot<LocalFileStateEntity>()
            coVerify(exactly = 1) { localFileStateDao.upsert(capture(captured)) }
            coVerify(exactly = 0) { fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename) }
            captured.captured.safUri shouldBe savedFile.absolutePath
            captured.captured.lastKnownModifiedTime shouldBe 456_789L
        }

    private fun `appendMainMemoContentAndUpdateState keeps existing saf uri when save returns null`() =
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
            captured.captured.safUri shouldBe "content://memo/existing"
            captured.captured.lastKnownModifiedTime shouldBe 999L
        }

    private fun `appendMainMemoContentAndUpdateState reuses direct save path metadata without extra lookup`() =
        runTest {
            val filename = "2026_03_29.md"
            val savedFile =
                File.createTempFile("memo-append-file-ops", ".md").apply {
                    writeText("after")
                    setLastModified(654_321L)
                    deleteOnExit()
                }
            coEvery { localFileStateDao.getByFilename(filename, false) } returnsMany listOf(null, null)
            coEvery {
                fileDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = filename,
                    content = "\n- 10:00 appended",
                    append = true,
                    uri = null,
                )
            } returns savedFile.absolutePath

            appendMainMemoContentAndUpdateState(
                runtime = runtime,
                filename = filename,
                rawContent = "- 10:00 appended",
            )

            val captured = slot<LocalFileStateEntity>()
            coVerify(exactly = 1) { localFileStateDao.upsert(capture(captured)) }
            coVerify(exactly = 0) { fileDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, filename) }
            captured.captured.safUri shouldBe savedFile.absolutePath
            captured.captured.lastKnownModifiedTime shouldBe 654_321L
        }

    private fun `toPersistedUriOrNull accepts content and file schemes only`() {
        val contentUri = mockk<android.net.Uri>(relaxed = true)
        val fileUri = mockk<android.net.Uri>(relaxed = true)
        mockkStatic(android.net.Uri::class)
        every { android.net.Uri.parse("content://memo/1") } returns contentUri
        every { android.net.Uri.parse("file:///tmp/memo.md") } returns fileUri

        ("content://memo/1".toPersistedUriOrNull() === contentUri).shouldBeTrue()
        ("file:///tmp/memo.md".toPersistedUriOrNull() === fileUri).shouldBeTrue()
        "https://example.com/memo".toPersistedUriOrNull().shouldBeNull()
        (null as String?).toPersistedUriOrNull().shouldBeNull()
        (" ".toPersistedUriOrNull() == null).shouldBeTrue()
    }

    private fun `moveToTrashFileOnly returns false when only timestamp matches a different memo block`() =
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

            result shouldBe false
        }
}
