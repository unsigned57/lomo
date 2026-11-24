package com.lomo.data.engine

import android.content.Intent
import com.lomo.data.source.AttachmentNameClassifier
import com.lomo.data.source.ImageEntrySelector
import com.lomo.data.source.NativeAttachmentNameClassifier
import com.lomo.data.source.isContentStorageUri
import com.lomo.data.source.isImageAttachmentFileName
import com.lomo.nativebridge.ActionOutcome
import com.lomo.nativebridge.ListingView
import com.lomo.nativebridge.PlatformAction
import com.lomo.nativebridge.PlatformActionOutput
import com.lomo.nativebridge.WorkspaceTarget
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Selects the images of one workspace directory the way a storage inventory does: in bounded batches. */
internal suspend fun EnginePerfProbe.mediaClassify(intent: Intent, run: String): JSONObject {
    requireReadySession()
    val path =
        intent.getStringExtra("path")
            ?: return errorPayload(run, "media_classify requires --es path")
    val samples = intent.probeCount("samples", DEFAULT_PROD_SAMPLES)
    val pageSize = intent.probeCount("pageSize", LIST_PAGE_SIZE, LIST_PAGE_SIZE)
    val names = listRelativeChildren(path, pageSize, run)
    check(names.isNotEmpty()) { "media_classify directory $path is empty" }
    val classifier = CountingAttachmentNameClassifier(NativeAttachmentNameClassifier)
    val times = JSONArray()
    val singleTimes = JSONArray()
    var expectedImages: List<String>? = null
    repeat(samples) {
        val t0 = System.nanoTime()
        val selector = ImageEntrySelector<String>(classifier) { name -> name }
        names.forEach(selector::offer)
        val images = selector.finish()
        times.put(elapsedMs(t0))
        // The single-name owner entry over the same names, in the same process: the reference
        // for both the selection and the cost of one call per file.
        val t1 = System.nanoTime()
        val reference = names.filter(::isImageAttachmentFileName)
        singleTimes.put(elapsedMs(t1))
        check(images == reference) { "media_classify batch selection disagrees with the single-name classifier" }
        check(expectedImages == null || expectedImages == images) { "media_classify output drifted across samples" }
        expectedImages = images
    }
    return okPayload(run, "media_classify").apply {
        put(
            "metrics",
            JSONObject()
                .put("media_classify_bulk_ms", times)
                .put("media_classify_single_calls_ms", singleTimes),
        )
        put(
            "facts",
            JSONObject()
                .put("path", path)
                .put("samples", samples)
                .put("itemsClassified", names.size)
                .put("imageCount", expectedImages?.size ?: 0)
                .put("ffiCallsPerSample", classifier.calls / samples)
                .put("singleEntryCallsPerSample", names.size)
                .put("requestUtf8Bytes", names.sumOf { name -> name.utf8Size() }),
        )
    }
}

private class CountingAttachmentNameClassifier(private val delegate: AttachmentNameClassifier) :
    AttachmentNameClassifier by delegate {
    var calls = 0
        private set

    override fun classify(names: List<String>): List<com.lomo.nativebridge.MediaClass> {
        calls += 1
        return delegate.classify(names)
    }
}

/** Relative paths of every child of [path], enumerated once through the production listing lane. */
private fun EnginePerfProbe.listRelativeChildren(path: String, pageSize: Int, run: String): List<String> {
    val location = checkNotNull(session.activeWorkspaceLocation.value) { "media_classify requires an active workspace" }
    val grant =
        if (isContentStorageUri(location.raw)) {
            capabilityRegistry.register(treeUri = location.raw)
        } else {
            capabilityRegistry.registerDirect(rootPath = File(location.raw))
        }
    try {
        val collected = mutableListOf<String>()
        var cursor: String? = null
        var pageIndex = 0
        // behavior-contract: loop-io-ok: enumerates the target directory once before the timed classification
        do {
            val outcome =
                platformAccess.execute(
                    PlatformAction.ListChildren(
                        actionId = "probe-classify-$run-$pageIndex",
                        capabilityToken = grant.capabilityToken,
                        target = WorkspaceTarget.Relative(path),
                        cursor = cursor,
                        pageSize = pageSize.toUInt(),
                        view = ListingView.WORKSPACE,
                    ),
                )
            val page = outcome.requireListedPage()
            page.items.mapNotNullTo(collected) { entry -> (entry.target as? WorkspaceTarget.Relative)?.path }
            pageIndex += 1
            cursor = page.nextCursor
        } while (cursor != null)
        return collected
    } finally {
        capabilityRegistry.revoke(grant.capabilityToken)
    }
}

private fun ActionOutcome.requireListedPage() = when (this) {
    is ActionOutcome.Applied ->
        (output as? PlatformActionOutput.Listed)?.page
            ?: error("media_classify listing returned ${output.javaClass.simpleName}")

    is ActionOutcome.Failed -> throw com.lomo.nativebridge.EngineError.Failure(failure)

    is ActionOutcome.AlreadySatisfied -> error("media_classify listing cannot replay as AlreadySatisfied")
}
