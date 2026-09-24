package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: AndroidPlatformActionExecutor.
 * - Owning layer: data Android capability edge.
 * - Priority tier: P0.
 * - Capability: execute a Rust-authored platform batch in order and return the longest verified
 *   action-result prefix without changing batch identity or interpreting workspace bytes.
 *
 * Scenarios:
 * - Given all seven action variants, when access verifies each side effect, then results preserve
 *   schema/job/batch/attempt and action order.
 * - Given action N fails, when execution returns, then N is the final result and N+1 is not run.
 * - Given the batch deadline is expired, when execution starts, then no platform access occurs and
 *   the first action returns a structured timeout failure.
 * - Given an unknown schema or invalid action count, when execution starts, then it fails before any
 *   side effect rather than truncating or accepting a default.
 * - Given a batch containing only independent reads, when execution starts, then reads overlap while
 *   their verified results remain in Rust-authored action order.
 * - Given one independent read fails, when the concurrent batch completes, then the submitted
 *   result stops at that failure as the longest verified ordered prefix.
 *
 * Observable outcomes:
 * - Ordered accessed action ids and PlatformBatchResult identity/outcomes.
 *
 * TDD proof:
 * - RED: compilation fails before implementation because AndroidPlatformActionExecutor and
 *   PlatformActionAccess do not exist.
 *
 * Excludes:
 * - ContentResolver mechanics, exchange file streaming, Rust job advancement, and UI behavior.
 *
 * Test Change Justification:
 * - Reason category: platform protocol contract change.
 * - Old behavior/assertion being replaced: metadata fixtures omitted the opaque document handle.
 * - Why old assertion is no longer correct: current platform metadata requires a handle for stable follow-up reads.
 * - Coverage preserved by: the existing batch ordering, failure-prefix, and deadline scenarios are unchanged.
 * - Why this is not fitting the test to the implementation: assertions still target ordered public batch results.
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.nativebridge.ActionEvidence
import com.lomo.nativebridge.ActionOutcome
import com.lomo.nativebridge.ContentDigest
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.DocumentMetadata
import com.lomo.nativebridge.EngineFailure
import com.lomo.nativebridge.ExchangeArtifact
import com.lomo.nativebridge.ExpectedFingerprint
import com.lomo.nativebridge.MetadataPage
import com.lomo.nativebridge.PlatformAction
import com.lomo.nativebridge.PlatformActionBatch
import com.lomo.nativebridge.PlatformActionOutput
import com.lomo.nativebridge.PlatformBatchResult
import com.lomo.nativebridge.VerifiedAbsence
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AndroidPlatformActionExecutorTest : DataFunSpec() {
    init {
        test("given all action variants when executed then batch identity and result order are preserved") {
            val accessed = mutableListOf<String>()
            val access = PlatformActionAccess { action ->
                accessed += action.actionId()
                ActionOutcome.Applied(action.output())
            }
            val executor = AndroidPlatformActionExecutor(access) { 1_000L }

            val result = executor.execute(batch(actions = allActions(), deadline = 2_000uL))

            result.schemaVersion shouldBe 1u
            result.jobId shouldBe "job-android"
            result.batchId shouldBe "batch-android"
            result.attempt shouldBe 3u
            result.actionResults.map { it.actionId } shouldContainExactly allActions().map(PlatformAction::actionId)
            accessed shouldContainExactly allActions().map(PlatformAction::actionId)
        }

        test("given action N fails when batch executes then later actions are not invoked") {
            val accessed = mutableListOf<String>()
            val access = PlatformActionAccess { action ->
                accessed += action.actionId()
                if (action.actionId() == "action-3") {
                    ActionOutcome.Failed(FAILURE)
                } else {
                    ActionOutcome.AlreadySatisfied(action.output())
                }
            }
            val executor = AndroidPlatformActionExecutor(access) { 1_000L }

            val result = executor.execute(batch(actions = allActions(), deadline = 2_000uL))

            result.actionResults.map { it.actionId } shouldContainExactly
                listOf("action-1", "action-2", "action-3")
            accessed shouldContainExactly listOf("action-1", "action-2", "action-3")
            (result.actionResults.last().outcome as ActionOutcome.Failed).failure shouldBe FAILURE
        }

        test("given expired batch when execution starts then first action fails without platform access") {
            var accessCount = 0
            val executor =
                AndroidPlatformActionExecutor(
                    access = PlatformActionAccess {
                        accessCount += 1
                        ActionOutcome.Applied(it.output())
                    },
                    currentTimeMillis = { 2_001L },
                )

            val result = executor.execute(batch(actions = allActions(), deadline = 2_000uL))

            accessCount shouldBe 0
            result.actionResults.map { it.actionId } shouldContainExactly listOf("action-1")
            val failure = (result.actionResults.single().outcome as ActionOutcome.Failed).failure
            failure.category shouldBe "timeout"
            failure.code shouldBe "platform_batch_deadline_exceeded"
        }

        test("given invalid schema or action count when execution starts then no side effect occurs") {
            var accessCount = 0
            val executor =
                AndroidPlatformActionExecutor(
                    access = PlatformActionAccess {
                        accessCount += 1
                        ActionOutcome.Applied(it.output())
                    },
                    currentTimeMillis = { 1_000L },
                )

            shouldThrow<IllegalArgumentException> {
                executor.execute(batch(actions = allActions(), deadline = 2_000uL).copy(schemaVersion = 2u))
            }
            shouldThrow<IllegalArgumentException> {
                executor.execute(batch(actions = emptyList(), deadline = 2_000uL))
            }
            accessCount shouldBe 0
        }

        test("given independent read batch when executed then reads overlap and results preserve order") {
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val accessed = Collections.synchronizedList(mutableListOf<String>())
            val access =
                PlatformActionAccess { action ->
                    accessed += action.actionId()
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS)) { "read release timed out" }
                    ActionOutcome.Applied(action.output())
                }
            val executor = AndroidPlatformActionExecutor(access) { 1_000L }
            val caller = Executors.newSingleThreadExecutor()
            val reads =
                listOf(
                    readAction("read-1", "one.md", "exchange-one"),
                    readAction("read-2", "two.md", "exchange-two"),
                )

            try {
                val result = caller.submit<PlatformBatchResult> {
                    executor.execute(batch(actions = reads, deadline = 2_000uL))
                }

                entered.await(1, TimeUnit.SECONDS) shouldBe true
                release.countDown()

                result.get(2, TimeUnit.SECONDS).actionResults.map { it.actionId } shouldContainExactly
                    listOf("read-1", "read-2")
                accessed.toSet() shouldBe setOf("read-1", "read-2")
            } finally {
                release.countDown()
                caller.shutdownNow()
            }
        }

        test("given an independent read fails when batch completes then result stops at ordered failure") {
            val reads =
                listOf(
                    readAction("read-1", "one.md", "exchange-one"),
                    readAction("read-2", "two.md", "exchange-two"),
                    readAction("read-3", "three.md", "exchange-three"),
                )
            val executor =
                AndroidPlatformActionExecutor(
                    access =
                        PlatformActionAccess { action ->
                            if (action.actionId() == "read-2") {
                                ActionOutcome.Failed(FAILURE)
                            } else {
                                ActionOutcome.Applied(action.output())
                            }
                        },
                    currentTimeMillis = { 1_000L },
                )

            val result = executor.execute(batch(actions = reads, deadline = 2_000uL))

            result.actionResults.map { it.actionId } shouldContainExactly listOf("read-1", "read-2")
            result.actionResults.last().outcome shouldBe ActionOutcome.Failed(FAILURE)
        }
    }
}

private val VERIFIED_EVIDENCE =
    ActionEvidence(
        length = 12uL,
        digest = ContentDigest.Verified("a".repeat(64)),
        fingerprint = "fingerprint-android",
    )

private val FAILURE =
    EngineFailure(
        category = "permission",
        code = "saf_grant_revoked",
        retryDisposition = "after_user_action",
        operationId = null,
        jobId = "job-android",
        diagnostic = "Workspace permission is no longer available",
    )

private fun batch(
    actions: List<PlatformAction>,
    deadline: ULong,
): PlatformActionBatch =
    PlatformActionBatch(
        schemaVersion = 1u,
        jobId = "job-android",
        batchId = "batch-android",
        attempt = 3u,
        deadlineEpochMillis = deadline,
        actions = actions,
    )

private fun allActions(): List<PlatformAction> =
    listOf(
        PlatformAction.Stat("action-1", "root-capability", WorkspaceTarget.Root),
        PlatformAction.ListChildren("action-2", "root-capability", WorkspaceTarget.Root, null, 256u),
        PlatformAction.EnsureDirectory("action-3", "root-capability", "images"),
        PlatformAction.ReadToExchange(
            "action-4",
            "root-capability",
            "memo.md",
            null,
            "exchange-read",
            ExpectedFingerprint.Absent,
        ),
        PlatformAction.WriteFromExchange(
            "action-5",
            "root-capability",
            ExchangeArtifact("exchange-write", 12uL, "a".repeat(64)),
            "memo.md",
            WriteMode.REPLACE,
            ExpectedFingerprint.Absent,
        ),
        PlatformAction.Move(
            "action-6",
            "root-capability",
            "memo.md",
            "trash/memo.md",
            ExpectedFingerprint.Match(VERIFIED_EVIDENCE),
            ExpectedFingerprint.Absent,
        ),
        PlatformAction.Delete(
            "action-7",
            "root-capability",
            "trash/memo.md",
            ExpectedFingerprint.Match(VERIFIED_EVIDENCE),
        ),
    )

private fun readAction(
    actionId: String,
    path: String,
    exchangeToken: String,
): PlatformAction.ReadToExchange =
    PlatformAction.ReadToExchange(
        actionId = actionId,
        capabilityToken = "root-capability",
        path = path,
        documentHandle = null,
        exchangeToken = exchangeToken,
        expectedSource = ExpectedFingerprint.Absent,
    )

private fun PlatformAction.actionId(): String =
    when (this) {
        is PlatformAction.Stat -> actionId
        is PlatformAction.ListChildren -> actionId
        is PlatformAction.EnsureDirectory -> actionId
        is PlatformAction.ReadToExchange -> actionId
        is PlatformAction.WriteFromExchange -> actionId
        is PlatformAction.ArtifactWrite -> actionId
        is PlatformAction.Move -> actionId
        is PlatformAction.Delete -> actionId
    }

private fun PlatformAction.output(): PlatformActionOutput {
    fun metadata(
        target: WorkspaceTarget,
        kind: DocumentKind,
        evidence: ActionEvidence = VERIFIED_EVIDENCE,
    ): DocumentMetadata =
        DocumentMetadata(
            target = target,
            documentHandle = "fixture-document",
            kind = kind,
            mimeType = null,
            evidence = evidence,
        )

    return when (this) {
        is PlatformAction.Stat ->
            PlatformActionOutput.Stat(metadata(target, DocumentKind.DIRECTORY))
        is PlatformAction.ListChildren ->
            PlatformActionOutput.Listed(MetadataPage(items = emptyList(), nextCursor = null))
        is PlatformAction.EnsureDirectory ->
            PlatformActionOutput.DirectoryReady(
                metadata(WorkspaceTarget.Relative(path), DocumentKind.DIRECTORY),
            )
        is PlatformAction.ReadToExchange ->
            PlatformActionOutput.ReadToExchange(
                sourceMetadata = metadata(WorkspaceTarget.Relative(path), DocumentKind.FILE),
                artifact = ExchangeArtifact(exchangeToken, 12uL, "a".repeat(64)),
            )
        is PlatformAction.WriteFromExchange ->
            PlatformActionOutput.WriteComplete(
                metadata(
                    target = WorkspaceTarget.Relative(path),
                    kind = DocumentKind.FILE,
                    evidence =
                        ActionEvidence(
                            length = artifact.length,
                            digest = ContentDigest.Verified(artifact.digest),
                            fingerprint = "fingerprint-android",
                        ),
                ),
            )
        is PlatformAction.ArtifactWrite ->
            PlatformActionOutput.WriteComplete(
                metadata(
                    target = WorkspaceTarget.Relative(path),
                    kind = DocumentKind.FILE,
                    evidence =
                        ActionEvidence(
                            length = source.length,
                            digest = ContentDigest.Verified(source.digest),
                            fingerprint = "fingerprint-android",
                        ),
                ),
            )
        is PlatformAction.Move ->
            PlatformActionOutput.MoveComplete(
                metadata(WorkspaceTarget.Relative(target), DocumentKind.FILE),
            )
        is PlatformAction.Delete ->
            PlatformActionOutput.DeleteComplete(
                VerifiedAbsence(
                    target = WorkspaceTarget.Relative(path),
                    fingerprint = "deleted-fingerprint-android",
                ),
            )
    }
}
