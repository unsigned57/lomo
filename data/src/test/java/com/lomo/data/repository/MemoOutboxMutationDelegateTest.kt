package com.lomo.data.repository

import com.lomo.data.local.dao.LocalFileStateDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.LocalFileStateEntity
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeFileDataSource
import com.lomo.data.testing.projectedMemoEntity
import com.lomo.data.testing.projectedTrashMemoEntity
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import com.lomo.domain.usecase.MemoIdentityPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.OutputStream
import java.time.LocalDate

/*
 * Behavior Contract:
 * - Unit under test: MemoOutboxMutationDelegate lifecycle completion.
 * - Owning layer: data repository lifecycle mutation pipeline.
 * - Priority tier: P0.
 * - Capability: outbox file completion records all command-owned lifecycle handoffs before
 *   returning an ackable success to the outbox drain coordinator.
 *
 * Scenarios:
 * - Given a DELETE outbox retry after the main file was already rewritten and only the trash append
 *   remains, when the retry finishes idempotently, then the trash file is completed and both remote
 *   journal recorders receive the main-file delete before the delegate returns true.
 * - Given a CREATE outbox row is completed, when the version-origin handoff fails, then the failure
 *   is thrown before the markdown file is appended.
 * - Given an UPDATE outbox row is completed, when durable DB state contains the target memo and the
 *   version-origin handoff succeeds, then the target DB snapshot is appended before the file rewrite
 *   reports an ackable success.
 * - Given a PERMANENT_DELETE outbox row is completed, when the trash row and block still exist,
 *   then deleted version handoff, trash file mutation, DB trash removal, media cleanup, and remote
 *   journal state complete before the row can be acknowledged.
 * - Given a PERMANENT_DELETE outbox row is completed but the trash file block is missing, when
 *   completion runs, then a lifecycle completion failure is surfaced and version, remote, media,
 *   and DB completion state remain untouched so retry/failure is visible.
 * - Given a PERMANENT_DELETE outbox row is completed for a memo whose attachment is still referenced,
 *   then the trash row is removed but the shared media file is retained.
 * - Given a VERSION_RESTORE outbox row is completed for an active target revision, when the command
 *   flushes, then version blobs are restored through workspace media, markdown is restored through
 *   the workspace owner, image locations refresh, history handoff runs, and remote journals record
 *   the restored memo state.
 * - Given a VERSION_RESTORE outbox row cannot find the command source span, when completion runs,
 *   then a lifecycle completion failure is surfaced before remote journals or history handoff run.
 * - Given a VERSION_RESTORE outbox row targets the same source shard but that shard is missing or blank, when
 *   completion runs, then a lifecycle completion failure is surfaced and replacement content is not written.
 *
 * Observable outcomes:
 * - returned Boolean success, thrown version handoff failures, persisted file content, local file
 *   state, captured S3/WebDAV memo journal operations, recorded version snapshots, restored
 *   workspace markdown/media content, and version-restore history handoff.
 *
 * TDD proof:
 * - Fails before the same-shard restore fix because missing or blank source shards are treated as successful
 *   replacement writes.
 *
 * Excludes:
 * - remote transport upload, outbox DAO claiming/acknowledgement, app/UI callers, and version blob
 *   query internals owned by MemoVersionJournal.
 */
class MemoOutboxMutationDelegateTest : DataFunSpec() {
    init {
        beforeTest {
            MockKAnnotations.init(this@MemoOutboxMutationDelegateTest)
            every { dataStore.storageFilenameFormat } returns flowOf("yyyy_MM_dd")
            every { dataStore.storageTimestampFormat } returns flowOf("HH:mm")
        }

        test("given delete retry after main rewrite when trash append finishes then main remote state is journaled before ack") {
            givenDeleteRetryAfterMainRewriteWhenTrashAppendFinishesThenMainRemoteStateIsJournaledBeforeAck()
        }

        test("given create outbox when version append fails then file apply is not ackable") {
            givenCreateOutboxWhenVersionAppendFailsThenFileApplyIsNotAckable()
        }

        test("given update outbox when version append succeeds then target db snapshot is handed off before ack") {
            givenUpdateOutboxWhenVersionAppendSucceedsThenTargetDbSnapshotIsHandedOffBeforeAck()
        }

        test("given permanent delete outbox when completion succeeds then trash state media version and remote journal are completed") {
            givenPermanentDeleteOutboxWhenCompletionSucceedsThenTrashStateMediaVersionAndRemoteJournalAreCompleted()
        }

        test("given permanent delete outbox when trash block is missing then completion is not ackable") {
            givenPermanentDeleteOutboxWhenTrashBlockIsMissingThenCompletionIsNotAckable()
        }

        test("given permanent delete outbox when attachment is still referenced then media file is retained") {
            givenPermanentDeleteOutboxWhenAttachmentIsStillReferencedThenMediaFileIsRetained()
        }

        test("given version restore outbox when active revision flushes then lifecycle owner restores workspace media history and remote state") {
            givenVersionRestoreOutboxWhenActiveRevisionFlushesThenLifecycleOwnerRestoresWorkspaceMediaHistoryAndRemoteState()
        }

        test("given version restore outbox when source span is missing then completion is not ackable") {
            givenVersionRestoreOutboxWhenSourceSpanIsMissingThenCompletionIsNotAckable()
        }

        test("given same shard version restore outbox when source shard is missing or blank then completion is not ackable") {
            givenSameShardVersionRestoreOutboxWhenSourceShardIsMissingOrBlankThenCompletionIsNotAckable()
        }
    }

    @MockK
    private lateinit var dao: TestMemoDaoSuite

    @MockK
    private lateinit var savePlanFactory: MemoSavePlanFactory

    @MockK
    private lateinit var memoVersionJournal: MemoVersionJournal

    @MockK
    private lateinit var memoVersionRestoreSupport: MemoVersionRestoreSupport

    @MockK
    private lateinit var dataStore: LomoDataStore

    private fun givenDeleteRetryAfterMainRewriteWhenTrashAppendFinishesThenMainRemoteStateIsJournaledBeforeAck() =
        runTest {
            val memo = outboxMemo()
            val command = MemoLifecycleCommand.deleteToTrash(memo)
            val filename = command.filename
            val fileDataSource = FakeFileDataSource()
            val localFileStateDao = InMemoryOutboxLocalFileStateDao()
            val s3Recorder = RecordingS3LocalChangeRecorder()
            val webDavRecorder = RecordingWebDavLocalChangeRecorder()
            val runtime =
                outboxRuntime(
                    fileDataSource = fileDataSource,
                    localFileStateDao = localFileStateDao,
                    s3LocalChangeRecorder = s3Recorder,
                    webDavLocalChangeRecorder = webDavRecorder,
                )

            val result = flushDeleteFromOutbox(runtime, command)

            result shouldBe true
            fileDataSource.files[MemoDirectoryType.TRASH to filename] shouldBe "\n${memo.rawContent}\n"
            localFileStateDao.getByFilename(filename, isTrash = true)?.isTrash shouldBe true
            s3Recorder.operations shouldContainExactly listOf(MemoJournalOperation.Delete(filename))
            webDavRecorder.operations shouldContainExactly listOf(MemoJournalOperation.Delete(filename))
        }

    private fun givenCreateOutboxWhenVersionAppendFailsThenFileApplyIsNotAckable() =
        runTest {
            val memo = outboxMemo().copy(id = "memo_create_version_fail", content = "created", rawContent = "- 10:00 created")
            val command = MemoLifecycleCommand.createMemo(memo)
            val fileDataSource = FakeFileDataSource()
            val recorder =
                RecordingMemoVersionRecorder(
                    failure = IllegalStateException("version append unavailable"),
                )
            val runtime =
                outboxRuntime(
                    fileDataSource = fileDataSource,
                    localFileStateDao = InMemoryOutboxLocalFileStateDao(),
                    s3LocalChangeRecorder = RecordingS3LocalChangeRecorder(),
                    webDavLocalChangeRecorder = RecordingWebDavLocalChangeRecorder(),
                    memoVersionRecorder = recorder,
                )
            coEvery { dao.getMemo(memo.id) } returns projectedMemoEntity(memo)

            val failure =
                shouldThrow<IllegalStateException> {
                    flushCreateFromOutbox(runtime, command)
                }

            failure.message shouldBe "version append unavailable"
            recorder.records.shouldContainExactly(
                listOf(
                    VersionRecord(
                        memo = memo.copy(localDate = LocalDate.of(2026, 5, 25)),
                        lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                        origin = MemoRevisionOrigin.LOCAL_CREATE,
                    ),
                ),
            )
            fileDataSource.files shouldBe emptyMap()
        }

    private fun givenUpdateOutboxWhenVersionAppendSucceedsThenTargetDbSnapshotIsHandedOffBeforeAck() =
        runTest {
            val sourceMemo =
                outboxMemo().copy(
                    id = "memo_update_version",
                    timestamp = 1_711_418_400_000L,
                    content = "before",
                    rawContent = "- 10:00 before",
                    dateKey = "2024_03_26",
                ).withParsedWorkspaceId()
            val targetMemo =
                sourceMemo.copy(
                    content = "after",
                    rawContent = "- 10:00 after",
                    updatedAt = sourceMemo.updatedAt + 1,
                )
            val command = MemoLifecycleCommand.updateMemo(sourceMemo, "after")
            val filename = command.filename
            val fileDataSource = FakeFileDataSource()
            fileDataSource.files[MemoDirectoryType.MAIN to filename] = "${sourceMemo.rawContent}\n"
            val recorder = RecordingMemoVersionRecorder()
            val localFileStateDao = InMemoryOutboxLocalFileStateDao()
            val runtime =
                outboxRuntime(
                    fileDataSource = fileDataSource,
                    localFileStateDao = localFileStateDao,
                    s3LocalChangeRecorder = RecordingS3LocalChangeRecorder(),
                    webDavLocalChangeRecorder = RecordingWebDavLocalChangeRecorder(),
                    memoVersionRecorder = recorder,
                )
            coEvery { dao.getMemo(sourceMemo.id) } returns projectedMemoEntity(targetMemo)

            val result = flushUpdateFromOutbox(runtime, MemoStorageFormatProvider(dataStore, immediateTestBackgroundScope()), command)

            result shouldBe true
            recorder.records.shouldContainExactly(
                listOf(
                    VersionRecord(
                        memo = targetMemo.copy(localDate = LocalDate.of(2024, 3, 26)),
                        lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                        origin = MemoRevisionOrigin.LOCAL_EDIT,
                    ),
                ),
            )
            fileDataSource.files[MemoDirectoryType.MAIN to filename] shouldBe "- 10:00 after"
        }

    private fun givenPermanentDeleteOutboxWhenCompletionSucceedsThenTrashStateMediaVersionAndRemoteJournalAreCompleted() =
        runTest {
            val memo =
                outboxMemo()
                    .copy(
                        id = "memo_permanent_delete",
                        content = "delete forever ![image](orphan.png) [voice](voice_1.m4a)",
                        rawContent = "- 10:00 delete forever ![image](orphan.png) [voice](voice_1.m4a)",
                        imageUrls = listOf("orphan.png", "voice_1.m4a"),
                        isDeleted = true,
                    ).withParsedWorkspaceId()
            val command = MemoLifecycleCommand.permanentDelete(memo)
            val filename = command.filename
            val fileDataSource = FakeFileDataSource()
            fileDataSource.files[MemoDirectoryType.TRASH to filename] = "${memo.rawContent}\n"
            fileDataSource.fileLastModified[MemoDirectoryType.TRASH to filename] = 900L
            val localFileStateDao = InMemoryOutboxLocalFileStateDao()
            localFileStateDao.upsert(
                LocalFileStateEntity(
                    filename = filename,
                    isTrash = true,
                    lastKnownModifiedTime = 100L,
                ),
            )
            val recorder = RecordingMemoVersionRecorder()
            val s3Recorder = RecordingS3LocalChangeRecorder()
            val webDavRecorder = RecordingWebDavLocalChangeRecorder()
            val runtime =
                outboxRuntime(
                    fileDataSource = fileDataSource,
                    localFileStateDao = localFileStateDao,
                    s3LocalChangeRecorder = s3Recorder,
                    webDavLocalChangeRecorder = webDavRecorder,
                    memoVersionRecorder = recorder,
                )
            coEvery { dao.getTrashMemo(memo.id) } returns projectedTrashMemoEntity(memo)
            coEvery { dao.countMemosAndTrashWithImage("orphan.png", memo.id) } returns 0
            coEvery { dao.countMemosAndTrashWithImage("voice_1.m4a", memo.id) } returns 0
            coEvery { dao.deleteImageRefsByMemoId(memo.id) } returns Unit
            coEvery { dao.deleteTrashMemoById(memo.id) } returns Unit

            val result = flushPermanentDeleteFromOutbox(runtime, command)

            result shouldBe true
            fileDataSource.files[MemoDirectoryType.TRASH to filename] shouldBe null
            localFileStateDao.getByFilename(filename, isTrash = true) shouldBe null
            recorder.records.shouldContainExactly(
                listOf(
                    VersionRecord(
                        memo = memo.copy(localDate = LocalDate.of(2026, 5, 25)),
                        lifecycleState = MemoRevisionLifecycleState.DELETED,
                        origin = MemoRevisionOrigin.LOCAL_DELETE,
                    ),
                ),
            )
            fileDataSource.deletedImages.shouldContainExactly(listOf("orphan.png"))
            fileDataSource.deletedVoiceFiles.shouldContainExactly(listOf("voice_1.m4a"))
            s3Recorder.operations.shouldContainExactly(
                listOf(
                    MemoJournalOperation.Delete(filename),
                    MemoJournalOperation.ImageDelete("orphan.png"),
                    MemoJournalOperation.VoiceDelete("voice_1.m4a"),
                ),
            )
            webDavRecorder.operations.shouldContainExactly(
                listOf(
                    MemoJournalOperation.Delete(filename),
                    MemoJournalOperation.ImageDelete("orphan.png"),
                    MemoJournalOperation.VoiceDelete("voice_1.m4a"),
                ),
            )
            coVerify(exactly = 1) { dao.deleteImageRefsByMemoId(memo.id) }
            coVerify(exactly = 1) { dao.deleteTrashMemoById(memo.id) }
        }

    private fun givenPermanentDeleteOutboxWhenTrashBlockIsMissingThenCompletionIsNotAckable() =
        runTest {
            val memo =
                outboxMemo()
                    .copy(
                        id = "memo_permanent_delete_missing_block",
                        content = "missing trash block ![image](missing.png)",
                        rawContent = "- 10:00 missing trash block ![image](missing.png)",
                        imageUrls = listOf("missing.png"),
                        isDeleted = true,
                    )
            val command = MemoLifecycleCommand.permanentDelete(memo)
            val filename = command.filename
            val fileDataSource = FakeFileDataSource()
            fileDataSource.files[MemoDirectoryType.TRASH to filename] = "- 09:00 another trashed memo\n"
            val recorder = RecordingMemoVersionRecorder()
            val s3Recorder = RecordingS3LocalChangeRecorder()
            val webDavRecorder = RecordingWebDavLocalChangeRecorder()
            val runtime =
                outboxRuntime(
                    fileDataSource = fileDataSource,
                    localFileStateDao = InMemoryOutboxLocalFileStateDao(),
                    s3LocalChangeRecorder = s3Recorder,
                    webDavLocalChangeRecorder = webDavRecorder,
                    memoVersionRecorder = recorder,
                )
            coEvery { dao.getTrashMemo(memo.id) } returns projectedTrashMemoEntity(memo)

            val failure =
                shouldThrow<MemoOutboxLifecycleCompletionException> {
                    flushPermanentDeleteFromOutbox(runtime, command)
                }

            failure.message shouldBe
                "PERMANENT_DELETE missing trash block for memo ${memo.id}: ${command.metadata.operationId.value}"
            fileDataSource.files[MemoDirectoryType.TRASH to filename] shouldBe "- 09:00 another trashed memo\n"
            recorder.records shouldBe emptyList()
            fileDataSource.deletedImages shouldBe emptyList()
            s3Recorder.operations shouldBe emptyList()
            webDavRecorder.operations shouldBe emptyList()
            coVerify(exactly = 0) { dao.deleteImageRefsByMemoId(memo.id) }
            coVerify(exactly = 0) { dao.deleteTrashMemoById(memo.id) }
        }

    private fun givenPermanentDeleteOutboxWhenAttachmentIsStillReferencedThenMediaFileIsRetained() =
        runTest {
            val memo =
                outboxMemo()
                    .copy(
                        id = "memo_permanent_delete_shared_attachment",
                        content = "delete forever ![image](shared.png)",
                        rawContent = "- 10:00 delete forever ![image](shared.png)",
                        imageUrls = listOf("shared.png"),
                        isDeleted = true,
                    ).withParsedWorkspaceId()
            val command = MemoLifecycleCommand.permanentDelete(memo)
            val filename = command.filename
            val fileDataSource = FakeFileDataSource()
            fileDataSource.files[MemoDirectoryType.TRASH to filename] = "${memo.rawContent}\n"
            val runtime =
                outboxRuntime(
                    fileDataSource = fileDataSource,
                    localFileStateDao = InMemoryOutboxLocalFileStateDao(),
                    s3LocalChangeRecorder = RecordingS3LocalChangeRecorder(),
                    webDavLocalChangeRecorder = RecordingWebDavLocalChangeRecorder(),
                )
            coEvery { dao.getTrashMemo(memo.id) } returns projectedTrashMemoEntity(memo)
            coEvery { dao.countMemosAndTrashWithImage("shared.png", memo.id) } returns 1
            coEvery { dao.deleteImageRefsByMemoId(memo.id) } returns Unit
            coEvery { dao.deleteTrashMemoById(memo.id) } returns Unit

            val result = flushPermanentDeleteFromOutbox(runtime, command)

            result shouldBe true
            fileDataSource.deletedImages shouldBe emptyList()
            coVerify(exactly = 1) { dao.deleteImageRefsByMemoId(memo.id) }
            coVerify(exactly = 1) { dao.deleteTrashMemoById(memo.id) }
        }

    private fun givenVersionRestoreOutboxWhenActiveRevisionFlushesThenLifecycleOwnerRestoresWorkspaceMediaHistoryAndRemoteState() =
        runTest {
            val sourceMemo =
                outboxMemo()
                    .copy(
                        id = "memo_version_restore_active",
                        timestamp = 1_795_478_400_000L,
                        updatedAt = 1_795_478_500_000L,
                        content = "current",
                        rawContent = "- 10:00 current",
                        dateKey = "2026_05_25",
                    ).withParsedWorkspaceId()
            val targetMemo =
                sourceMemo.copy(
                    timestamp = 1_795_392_000_000L,
                    updatedAt = 1_795_392_100_000L,
                    content = "restored ![image](restore.png)",
                    rawContent = "- 10:00 restored ![image](restore.png)",
                    dateKey = "2026_05_24",
                )
            val command =
                MemoLifecycleCommand.restoreRevision(
                    currentMemo = sourceMemo,
                    currentRevisionId = "revision-current",
                    targetRevisionId = "revision-target",
                    targetLifecycleState = MemoRevisionLifecycleState.ACTIVE,
                    targetMemo = targetMemo,
                    targetRawContent = targetMemo.rawContent,
                )
            val fileDataSource = FakeFileDataSource()
            fileDataSource.files[MemoDirectoryType.MAIN to command.sourceFilename] = "${sourceMemo.rawContent}\n"
            val workspaceMediaAccess = RecordingWorkspaceMediaAccess()
            val mediaRepository = RecordingMediaRepository()
            val s3Recorder = RecordingS3LocalChangeRecorder()
            val webDavRecorder = RecordingWebDavLocalChangeRecorder()
            val runtime =
                outboxRuntime(
                    fileDataSource = fileDataSource,
                    localFileStateDao = InMemoryOutboxLocalFileStateDao(),
                    s3LocalChangeRecorder = s3Recorder,
                    webDavLocalChangeRecorder = webDavRecorder,
                    workspaceMediaAccess = workspaceMediaAccess,
                    mediaRepository = mediaRepository,
                )
            val restoredAssets =
                listOf(
                    MemoRevisionRestoreAsset(
                        category = WorkspaceMediaCategory.IMAGE,
                        filename = "restore.png",
                        writeTo = { output -> output.write("restored-image".toByteArray()) },
                    ),
                )
            coEvery { memoVersionRestoreSupport.readRevisionRestoreAssets("revision-target") } returns restoredAssets
            coEvery { memoVersionRestoreSupport.recordRevisionRestoreHandoff(command) } returns Unit

            val result = flushVersionRestoreFromOutbox(runtime, command)

            result shouldBe true
            fileDataSource.files[MemoDirectoryType.MAIN to command.sourceFilename] shouldBe null
            fileDataSource.files[MemoDirectoryType.MAIN to command.filename] shouldBe targetMemo.rawContent
            workspaceMediaAccess.writes.map { write -> write.category to write.filename } shouldContainExactly
                listOf(WorkspaceMediaCategory.IMAGE to "restore.png")
            workspaceMediaAccess.writes.single().bytes.decodeToString() shouldBe "restored-image"
            mediaRepository.refreshImageLocationsCallCount shouldBe 1
            s3Recorder.operations shouldContainExactly
                listOf(
                    MemoJournalOperation.Upsert(command.filename),
                    MemoJournalOperation.Delete(command.sourceFilename),
                )
            webDavRecorder.operations shouldContainExactly
                listOf(
                    MemoJournalOperation.Upsert(command.filename),
                    MemoJournalOperation.Delete(command.sourceFilename),
                )
            coVerify(exactly = 1) { memoVersionRestoreSupport.readRevisionRestoreAssets("revision-target") }
            coVerify(exactly = 1) { memoVersionRestoreSupport.recordRevisionRestoreHandoff(command) }
        }

    private fun givenVersionRestoreOutboxWhenSourceSpanIsMissingThenCompletionIsNotAckable() =
        runTest {
            val sourceMemo =
                outboxMemo()
                    .copy(
                        id = "memo_version_restore_missing_span",
                        timestamp = 1_795_478_400_000L,
                        updatedAt = 1_795_478_500_000L,
                        content = "current",
                        rawContent = "- 10:00 current",
                        dateKey = "2026_05_25",
                    )
            val targetMemo =
                sourceMemo.copy(
                    content = "restored",
                    rawContent = "- 10:00 restored",
                )
            val command =
                MemoLifecycleCommand.restoreRevision(
                    currentMemo = sourceMemo,
                    currentRevisionId = "revision-current",
                    targetRevisionId = "revision-target",
                    targetLifecycleState = MemoRevisionLifecycleState.ACTIVE,
                    targetMemo = targetMemo,
                    targetRawContent = targetMemo.rawContent,
                )
            val fileDataSource = FakeFileDataSource()
            fileDataSource.files[MemoDirectoryType.MAIN to command.sourceFilename] = "- 11:00 sibling"
            val s3Recorder = RecordingS3LocalChangeRecorder()
            val webDavRecorder = RecordingWebDavLocalChangeRecorder()
            val runtime =
                outboxRuntime(
                    fileDataSource = fileDataSource,
                    localFileStateDao = InMemoryOutboxLocalFileStateDao(),
                    s3LocalChangeRecorder = s3Recorder,
                    webDavLocalChangeRecorder = webDavRecorder,
                )
            coEvery { memoVersionRestoreSupport.readRevisionRestoreAssets("revision-target") } returns emptyList()

            val error =
                shouldThrow<MemoOutboxLifecycleCompletionException> {
                    flushVersionRestoreFromOutbox(runtime, command)
                }

            error.message shouldContain
                "VERSION_RESTORE missing source span for memo memo_version_restore_missing_span in MAIN shard " +
                "2026_05_25.md"
            fileDataSource.files[MemoDirectoryType.MAIN to command.sourceFilename] shouldBe "- 11:00 sibling"
            s3Recorder.operations shouldBe emptyList()
            webDavRecorder.operations shouldBe emptyList()
            coVerify(exactly = 1) { memoVersionRestoreSupport.readRevisionRestoreAssets("revision-target") }
            coVerify(exactly = 0) { memoVersionRestoreSupport.recordRevisionRestoreHandoff(command) }
        }

    private fun givenSameShardVersionRestoreOutboxWhenSourceShardIsMissingOrBlankThenCompletionIsNotAckable() =
        runTest {
            listOf(
                "missing" to null,
                "blank" to " \n",
            ).forEach { (label, sourceContent) ->
                withClue(label) {
                    MockKAnnotations.init(this@MemoOutboxMutationDelegateTest)
                    every { dataStore.storageFilenameFormat } returns flowOf("yyyy_MM_dd")
                    every { dataStore.storageTimestampFormat } returns flowOf("HH:mm")
                    val sourceMemo =
                        outboxMemo()
                            .copy(
                                id = "memo_version_restore_same_shard_$label",
                                timestamp = 1_795_478_400_000L,
                                updatedAt = 1_795_478_500_000L,
                                content = "current",
                                rawContent = "- 10:00 current",
                                dateKey = "2026_05_25",
                            )
                    val targetMemo =
                        sourceMemo.copy(
                            content = "restored",
                            rawContent = "- 10:00 restored",
                        )
                    val command =
                        MemoLifecycleCommand.restoreRevision(
                            currentMemo = sourceMemo,
                            currentRevisionId = "revision-current-$label",
                            targetRevisionId = "revision-target-$label",
                            targetLifecycleState = MemoRevisionLifecycleState.ACTIVE,
                            targetMemo = targetMemo,
                            targetRawContent = targetMemo.rawContent,
                        )
                    val fileDataSource = FakeFileDataSource()
                    if (sourceContent != null) {
                        fileDataSource.files[MemoDirectoryType.MAIN to command.sourceFilename] = sourceContent
                    }
                    val s3Recorder = RecordingS3LocalChangeRecorder()
                    val webDavRecorder = RecordingWebDavLocalChangeRecorder()
                    val runtime =
                        outboxRuntime(
                            fileDataSource = fileDataSource,
                            localFileStateDao = InMemoryOutboxLocalFileStateDao(),
                            s3LocalChangeRecorder = s3Recorder,
                            webDavLocalChangeRecorder = webDavRecorder,
                        )
                    coEvery { memoVersionRestoreSupport.readRevisionRestoreAssets("revision-target-$label") } returns emptyList()

                    val error =
                        shouldThrow<MemoOutboxLifecycleCompletionException> {
                            flushVersionRestoreFromOutbox(runtime, command)
                        }

                    error.message shouldContain
                        "VERSION_RESTORE missing source span for memo memo_version_restore_same_shard_$label in MAIN shard " +
                        "2026_05_25.md"
                    fileDataSource.files[MemoDirectoryType.MAIN to command.sourceFilename] shouldBe sourceContent
                    s3Recorder.operations shouldBe emptyList()
                    webDavRecorder.operations shouldBe emptyList()
                    coVerify(exactly = 1) { memoVersionRestoreSupport.readRevisionRestoreAssets("revision-target-$label") }
                    coVerify(exactly = 0) { memoVersionRestoreSupport.recordRevisionRestoreHandoff(command) }
                }
            }
        }

    private fun outboxRuntime(
        fileDataSource: FakeFileDataSource,
        localFileStateDao: LocalFileStateDao,
        s3LocalChangeRecorder: S3LocalChangeRecorder,
        webDavLocalChangeRecorder: WebDavLocalChangeRecorder,
        memoVersionRecorder: MemoVersionRecorder = RecordingMemoVersionRecorder(),
        workspaceMediaAccess: WorkspaceMediaAccess = ThrowingWorkspaceMediaAccess,
        mediaRepository: com.lomo.domain.repository.MediaRepository = ThrowingMediaRepository,
        journal: MemoVersionJournal = memoVersionJournal,
        restoreSupport: MemoVersionRestoreSupport = memoVersionRestoreSupport,
    ): MemoMutationRuntime =
        testMemoWorkspaceStore(
            markdownStorageDataSource = fileDataSource,
            localFileStateDao = localFileStateDao,
        ).let { workspaceStore ->
        MemoMutationRuntime(
            markdownStorageDataSource = fileDataSource,
            mediaStorageDataSource = fileDataSource,
            daoBundle = testMemoMutationDaoBundle(dao),
            memoStatisticsDao = dao,
            localFileStateDao = localFileStateDao,
            workspaceStore = workspaceStore,
            workspaceMediaAccess = workspaceMediaAccess,
            savePlanFactory = savePlanFactory,
            textProcessor = MemoTextProcessor(),
            trashMutationHandler =
                MemoTrashMutationHandler(
                    workspaceStore = workspaceStore,
                    memoWriteDao = dao,
                    memoTagDao = dao,
                    memoImageDao = dao,
                    memoTrashDao = dao,
                    memoVersionRecorder = AsyncMemoVersionRecorder(memoVersionJournal, immediateTestBackgroundScope()),
            ),
            memoIdentityPolicy = MemoIdentityPolicy(),
            memoVersionJournal = journal,
            memoVersionRestoreSupport = restoreSupport,
            memoVersionRecorder = memoVersionRecorder,
            mediaRepository = mediaRepository,
            s3LocalChangeRecorder = s3LocalChangeRecorder,
            webDavLocalChangeRecorder = webDavLocalChangeRecorder,
            mutationGate = MemoMutationGate(),
        )
        }

    private fun outboxMemo(): Memo =
        Memo(
            id = "memo_2026_05_25_10:00_delete_retry",
            timestamp = 1_795_478_400_000L,
            updatedAt = 1_795_478_400_000L,
            content = "delete retry",
            rawContent = "- 10:00 delete retry",
            dateKey = "2026_05_25",
        )

    private fun Memo.withParsedWorkspaceId(): Memo {
        val parsedId =
            MarkdownParser(MemoTextProcessor(), MemoIdentityPolicy())
                .parseContent(rawContent, dateKey)
                .single()
                .id
        return copy(id = parsedId)
    }
}

private class InMemoryOutboxLocalFileStateDao : LocalFileStateDao {
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

private data class VersionRecord(
    val memo: Memo,
    val lifecycleState: MemoRevisionLifecycleState,
    val origin: MemoRevisionOrigin,
)

private class RecordingMemoVersionRecorder(
    private val failure: Throwable? = null,
) : MemoVersionRecorder {
    val records = mutableListOf<VersionRecord>()

    override suspend fun enqueueLocalRevision(
        memo: Memo,
        lifecycleState: MemoRevisionLifecycleState,
        origin: MemoRevisionOrigin,
    ) {
        recordLocalRevision(memo, lifecycleState, origin)
    }

    override suspend fun recordLocalRevision(
        memo: Memo,
        lifecycleState: MemoRevisionLifecycleState,
        origin: MemoRevisionOrigin,
    ) {
        records += VersionRecord(memo, lifecycleState, origin)
        failure?.let { throw it }
    }
}

private data class WorkspaceMediaWrite(
    val category: WorkspaceMediaCategory,
    val filename: String,
    val bytes: ByteArray,
)

private class RecordingWorkspaceMediaAccess : WorkspaceMediaAccess {
    val writes = mutableListOf<WorkspaceMediaWrite>()

    override suspend fun listFiles(category: WorkspaceMediaCategory): List<WorkspaceMediaDescriptor> = emptyList()

    override suspend fun listFilenames(category: WorkspaceMediaCategory): List<String> = emptyList()

    override suspend fun readFileToStream(
        category: WorkspaceMediaCategory,
        filename: String,
        destination: OutputStream,
    ): Boolean = false

    override suspend fun writeFileFromStream(
        category: WorkspaceMediaCategory,
        filename: String,
        source: suspend (OutputStream) -> Unit,
    ) {
        val output = java.io.ByteArrayOutputStream()
        source(output)
        writes += WorkspaceMediaWrite(category = category, filename = filename, bytes = output.toByteArray())
    }

    override suspend fun deleteFile(
        category: WorkspaceMediaCategory,
        filename: String,
    ) {
        unexpectedWorkspaceMediaAccess("deleteFile")
    }
}

private fun unexpectedWorkspaceMediaAccess(method: String): Nothing =
    error("Unexpected WorkspaceMediaAccess.$method call in this version restore outbox test")

private sealed interface MemoJournalOperation {
    data class Upsert(val filename: String) : MemoJournalOperation

    data class Delete(val filename: String) : MemoJournalOperation

    data class ImageDelete(val filename: String) : MemoJournalOperation

    data class VoiceDelete(val filename: String) : MemoJournalOperation
}

private class RecordingS3LocalChangeRecorder : S3LocalChangeRecorder {
    val operations = mutableListOf<MemoJournalOperation>()

    override suspend fun recordMemoUpsert(filename: String) {
        operations += MemoJournalOperation.Upsert(filename)
    }

    override suspend fun recordMemoDelete(filename: String) {
        operations += MemoJournalOperation.Delete(filename)
    }

    override suspend fun recordImageUpsert(filename: String) = Unit

    override suspend fun recordImageDelete(filename: String) {
        operations += MemoJournalOperation.ImageDelete(filename)
    }

    override suspend fun recordVoiceUpsert(filename: String) = Unit

    override suspend fun recordVoiceDelete(filename: String) {
        operations += MemoJournalOperation.VoiceDelete(filename)
    }
}

private class RecordingWebDavLocalChangeRecorder : WebDavLocalChangeRecorder {
    val operations = mutableListOf<MemoJournalOperation>()

    override suspend fun recordMemoUpsert(filename: String) {
        operations += MemoJournalOperation.Upsert(filename)
    }

    override suspend fun recordMemoDelete(filename: String) {
        operations += MemoJournalOperation.Delete(filename)
    }

    override suspend fun recordImageUpsert(filename: String) = Unit

    override suspend fun recordImageDelete(filename: String) {
        operations += MemoJournalOperation.ImageDelete(filename)
    }

    override suspend fun recordVoiceUpsert(filename: String) = Unit

    override suspend fun recordVoiceDelete(filename: String) {
        operations += MemoJournalOperation.VoiceDelete(filename)
    }
}
