package com.lomo.data.engine

import android.content.Intent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/*
 * Behavior Contract: measure real staged-temp adoption and stage-lease lifecycle.
 * Given an existing bounded media fixture, when a sample runs, then a fresh app-cache copy is
 * consumed by the production media stage registry and its uniquely owned incoming lease is
 * released. The registry owns the stage root; no scratch ledger or fabricated media identity.
 * Invalid source/count fails before staging. TDD: unknown media_stage on device was RED.
 * Excludes: memo promotion, existing leases owned by other drafts, and image decoding.
 */
internal suspend fun EnginePerfProbe.mediaStage(
    intent: Intent,
    run: String,
    ioDispatcher: CoroutineDispatcher,
): JSONObject = withContext(ioDispatcher) {
    val samples = intent.probeCount("samples", 5, MAX_MUTATION_PROBE_SAMPLES)
    val source = File(requireNotNull(intent.getStringExtra("sourcePath")) { "media_stage requires sourcePath" })
    require(source.isFile && source.length() in 1..MAX_PROBE_MEDIA_BYTES) { "Invalid or oversized media probe source" }
    requireReadySession()
    val invocation = ProbeWriteInvocation.newInvocation(run)
    val stageTimes = JSONArray()
    val leaseTimes = JSONArray()
    val facts = JSONObject().put("samples", samples).put("sourceBytes", source.length())
    repeat(samples) { sample ->
        val temp = File.createTempFile("lomo-perf-media-", ".${source.extension}", context.cacheDir)
        val owner = invocation.operationId(sample)
        try {
            source.copyTo(temp, overwrite = true)
            val start = System.nanoTime()
            val staged = mediaStages.stageIncoming(temp.absolutePath, source.name)
            stageTimes.put(elapsedMs(start))
            check(!temp.exists()) { "Production staging did not consume the owned temp source" }
            facts.put("stagedBytes", staged.size).put("mime", staged.mime).put("digest", staged.digest)
            val leaseStart = System.nanoTime()
            try {
                val record = mediaStages.recordIncoming(staged, owner)
                check(record.stagedBytesPresent && record.digest == staged.digest) {
                    "Stage lease has no verified bytes"
                }
            } finally {
                mediaStages.releaseIncoming(owner)
            }
            leaseTimes.put(elapsedMs(leaseStart))
        } finally {
            Files.deleteIfExists(temp.toPath())
        }
    }
    okPayload(run, "media_stage").put("facts", facts.put("releasedOwnedLeases", samples)).put(
        "metrics",
        JSONObject().put("media_stage_ms", stageTimes).put("media_lease_cycle_ms", leaseTimes),
    )
}

private const val MAX_PROBE_MEDIA_BYTES = 16L * 1024 * 1024
