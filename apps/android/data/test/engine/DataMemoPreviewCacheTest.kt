package com.lomo.data.engine

import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import com.lomo.domain.model.MemoPreviewRenderRequest
import com.lomo.domain.model.MemoPreviewRenderState
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.nativebridge.RenderDocument
import com.lomo.nativebridge.RenderNode
import com.lomo.nativebridge.RenderNodeKind
import com.lomo.nativebridge.SessionMemoPreviewOutcome
import com.lomo.nativebridge.SessionMemoPreviewRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

private class FakePreviewSessionBridge : SessionNativeBridge {
    val calls = mutableListOf<List<SessionMemoPreviewRequest>>()
    var handler: (List<SessionMemoPreviewRequest>, UInt) -> List<SessionMemoPreviewOutcome> =
        { requests, schemaVersion ->
            requests.map { request ->
                renderedOutcome(
                    memoId = request.memoId,
                    contentRevision = request.expectedContentRevision,
                    preview = "preview:${request.memoId}",
                    schemaVersion = schemaVersion,
                )
            }
        }

    override fun sessionRenderMemoPreviews(
        items: List<SessionMemoPreviewRequest>,
        schemaVersion: UInt,
    ): List<SessionMemoPreviewOutcome> {
        calls += items
        return handler(items, schemaVersion)
    }
}

private fun renderedOutcome(
    memoId: String,
    contentRevision: ULong,
    preview: String,
    schemaVersion: UInt = MarkdownRenderDocument.SCHEMA_VERSION,
): SessionMemoPreviewOutcome {
    val bytes = preview.encodeToByteArray().size.toULong()
    return SessionMemoPreviewOutcome.Rendered(
        memoId = memoId,
        contentRevision = contentRevision,
        sourceByteLength = bytes,
        document =
        RenderDocument(
            schemaVersion = schemaVersion,
            plainText = preview,
            nodeCount = 2u,
            tagNames = emptyList(),
            attachments = emptyList(),
            nodes =
            listOf(
                RenderNode(
                    kind = RenderNodeKind.PARAGRAPH,
                    sourceStart = 0uL,
                    sourceEnd = bytes,
                    depth = 1u,
                    text = null,
                    destination = null,
                    title = null,
                    level = null,
                    ordered = null,
                    listStart = null,
                    checked = null,
                    actionStart = null,
                    actionEnd = null,
                    mediaClass = null,
                    alignment = null,
                ),
                RenderNode(
                    kind = RenderNodeKind.TEXT,
                    sourceStart = 0uL,
                    sourceEnd = bytes,
                    depth = 2u,
                    text = preview,
                    destination = null,
                    title = null,
                    level = null,
                    ordered = null,
                    listStart = null,
                    checked = null,
                    actionStart = null,
                    actionEnd = null,
                    mediaClass = null,
                    alignment = null,
                ),
            ),
        ),
    )
}

class DataMemoPreviewCacheTest :
    FunSpec({
        test("hit and miss respect workspace generation, content revision, and exact bodyPreview") {
            runTest {
                val bridge = FakePreviewSessionBridge()
                val readiness = FakeEngineReadinessRepository(EngineReadiness.Ready)
                readiness.activateWorkspace(StorageLocation("/ws-a"), null)
                val cache = DataMemoPreviewCache(bridge, readiness)
                val req =
                    MemoPreviewRenderRequest(
                        memoId = "m1",
                        contentRevision = 3L,
                        expectedBodyPreview = "preview:m1",
                    )

                val first = cache.loadPreviews(listOf(req))
                first["m1"]?.plainText shouldBe "preview:m1"
                bridge.calls.size shouldBe 1

                val second = cache.loadPreviews(listOf(req))
                second["m1"]?.plainText shouldBe "preview:m1"
                bridge.calls.size shouldBe 1
                cache.stats().hits shouldBe 1L

                // Exact bodyPreview mismatch misses rather than returning mismatched span/text IR.
                bridge.handler = { requests, _ ->
                    requests.map { renderedOutcome(it.memoId, it.expectedContentRevision, "updated:${it.memoId}") }
                }
                val mismatchedText =
                    cache.loadPreviews(
                        listOf(req.copy(expectedBodyPreview = "updated:m1")),
                    )
                mismatchedText["m1"]?.plainText shouldBe "updated:m1"
                bridge.calls.size shouldBe 2

                // Re-activating increments mount generation -> isolates cache entries across generations.
                readiness.activateWorkspace(StorageLocation("/ws-a"), null)
                cache.loadPreviews(listOf(req.copy(expectedBodyPreview = "updated:m1")))
                bridge.calls.size shouldBe 3
            }
        }

        test("batches thirty preview misses into four FFI calls of at most eight") {
            runTest {
                val bridge =
                    FakePreviewSessionBridge().apply {
                        handler = { requests, _ ->
                            requests.map {
                                renderedOutcome(it.memoId, it.expectedContentRevision, "body-${it.memoId}")
                            }
                        }
                    }
                val readiness = FakeEngineReadinessRepository(EngineReadiness.Ready)
                readiness.activateWorkspace(StorageLocation("/ws-batch"), null)
                val cache = DataMemoPreviewCache(bridge, readiness)
                val requests =
                    (1..30).map { index ->
                        MemoPreviewRenderRequest(
                            memoId = "m-$index",
                            contentRevision = index.toLong(),
                            expectedBodyPreview = "body-m-$index",
                        )
                    }

                val docs = cache.loadPreviews(requests)

                docs.size shouldBe 30
                bridge.calls.map { it.size } shouldBe listOf(8, 8, 8, 6)
                cache.stats().batchCalls shouldBe 4L

                // Warm hit after batching triggers zero FFI calls.
                cache.prefetchLeadingWindow(
                    requests.map { req ->
                        Memo(
                            id = req.memoId,
                            timestamp = 1L,
                            content = req.expectedBodyPreview,
                            rawContent = req.expectedBodyPreview,
                            dateKey = "2026_11_24",
                            contentRevision = req.contentRevision,
                            contentKind = MemoContentKind.Preview,
                        )
                    },
                    windowSize = 20,
                ).take(20).forEach { memo ->
                    memo.previewRenderState.shouldBeInstanceOf<MemoPreviewRenderState.Ready>()
                }
                bridge.calls.size shouldBe 4
            }
        }

        test("single-flight coalesces concurrent identical requests into one FFI batch") {
            runTest {
                val entered = CompletableDeferred<Unit>()
                val gate = CompletableDeferred<Unit>()
                val bridge =
                    FakePreviewSessionBridge().apply {
                        handler = { requests, _ ->
                            entered.complete(Unit)
                            kotlinx.coroutines.runBlocking { gate.await() }
                            requests.map { renderedOutcome(it.memoId, it.expectedContentRevision, "shared-body") }
                        }
                    }
                val readiness = FakeEngineReadinessRepository(EngineReadiness.Ready)
                readiness.activateWorkspace(StorageLocation("/ws-coal"), null)
                val cache = DataMemoPreviewCache(bridge, readiness)
                val req =
                    MemoPreviewRenderRequest(
                        memoId = "m-shared",
                        contentRevision = 7L,
                        expectedBodyPreview = "shared-body",
                    )

                val firstJob = async(Dispatchers.IO) { cache.loadPreviews(listOf(req)) }
                entered.await()
                val secondJob = async(start = CoroutineStart.UNDISPATCHED) { cache.loadPreviews(listOf(req)) }
                val differentText = req.copy(expectedBodyPreview = "shared-body".reversed())
                val mismatchedJob =
                    async(start = CoroutineStart.UNDISPATCHED) { cache.loadPreviews(listOf(differentText)) }
                val mismatchCompletedBeforeRender = mismatchedJob.isCompleted
                gate.complete(Unit)

                firstJob.await()["m-shared"]?.plainText shouldBe "shared-body"
                secondJob.await()["m-shared"]?.plainText shouldBe "shared-body"
                mismatchedJob.await() shouldBe emptyMap()
                mismatchCompletedBeforeRender shouldBe true
                bridge.calls shouldHaveSize 1
                cache.stats().coalescedHits shouldBe 1L
            }
        }

        test("RevisionMismatch, NotFound, and stale search epoch do not poison the cache") {
            runTest {
                var validEpoch = true
                val bridge =
                    FakePreviewSessionBridge().apply {
                        handler = { requests, _ ->
                            requests.map { request ->
                                when (request.memoId) {
                                    "stale-rev" -> SessionMemoPreviewOutcome.RevisionMismatch("stale-rev", 99uL)

                                    "missing" -> SessionMemoPreviewOutcome.NotFound("missing")

                                    else -> {
                                        if (request.memoId == "preempted") {
                                            validEpoch = false
                                        }
                                        renderedOutcome(request.memoId, request.expectedContentRevision, "ok")
                                    }
                                }
                            }
                        }
                    }
                val readiness = FakeEngineReadinessRepository(EngineReadiness.Ready)
                readiness.activateWorkspace(StorageLocation("/ws-edge"), null)
                val cache = DataMemoPreviewCache(bridge, readiness)

                val staleResult =
                    cache.loadPreviews(
                        listOf(
                            MemoPreviewRenderRequest("stale-rev", 1L, "ok"),
                            MemoPreviewRenderRequest("missing", 1L, "ok"),
                        ),
                    )
                staleResult shouldBe emptyMap()
                cache.stats().size shouldBe 0
                cache.stats().revisionMismatches shouldBe 2L

                val preemptedResult =
                    cache.loadPreviews(
                        listOf(MemoPreviewRenderRequest("preempted", 1L, "ok")),
                        isStillValid = { validEpoch },
                    )
                preemptedResult shouldBe emptyMap()
                cache.stats().size shouldBe 0

                // Restoring valid epoch executes a fresh call and commits cleanly.
                validEpoch = true
                val recovered = cache.loadPreviews(listOf(MemoPreviewRenderRequest("preempted", 1L, "ok")))
                recovered["preempted"]?.plainText shouldBe "ok"
                cache.stats().size shouldBe 1
            }
        }

        test("cancelled flight releases inFlight slot so retry succeeds") {
            runTest {
                val entered = CompletableDeferred<Unit>()
                val allowFinish = CompletableDeferred<Unit>()
                val bridge =
                    FakePreviewSessionBridge().apply {
                        handler = { requests, _ ->
                            entered.complete(Unit)
                            kotlinx.coroutines.runBlocking { allowFinish.await() }
                            requests.map { renderedOutcome(it.memoId, it.expectedContentRevision, "retry-ok") }
                        }
                    }
                val readiness = FakeEngineReadinessRepository(EngineReadiness.Ready)
                readiness.activateWorkspace(StorageLocation("/ws-cancel"), null)
                val cache = DataMemoPreviewCache(bridge, readiness)
                val req = MemoPreviewRenderRequest("m-cancel", 1L, "retry-ok")

                val job =
                    launch(Dispatchers.IO) {
                        cache.loadPreviews(listOf(req))
                    }
                entered.await()
                job.cancel()
                allowFinish.complete(Unit)
                job.join()

                val recovered = cache.loadPreviews(listOf(req))
                recovered["m-cancel"]?.plainText shouldBe "retry-ok"
            }
        }
    })
