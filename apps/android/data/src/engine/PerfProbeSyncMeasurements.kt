package com.lomo.data.engine

import android.content.Intent
import com.lomo.data.engine.sync.RemoteSyncBackendProbe
import com.lomo.data.engine.sync.RemoteSyncBoundaryFailure
import com.lomo.data.engine.sync.RemoteSyncCyclePlanSummary
import com.lomo.data.engine.sync.RemoteSyncCycleRequest
import com.lomo.data.engine.sync.RemoteSyncCycleStatus
import org.json.JSONArray
import org.json.JSONObject

/** Optional provider fields are absent extras for backends that do not use them. */
private fun Intent.syncCycleRequest(
    workspace: String,
    backend: String,
    endpoint: String,
    secretLeaseId: String?,
): RemoteSyncCycleRequest = RemoteSyncCycleRequest(
    workspaceRoot = workspace,
    backendKind = backend,
    endpointUrl = endpoint,
    identity = getStringExtra("identity").orEmpty(),
    s3Bucket = getStringExtra("bucket").orEmpty(),
    s3Prefix = getStringExtra("prefix").orEmpty(),
    s3Region = getStringExtra("region").orEmpty(),
    gitBranch = getStringExtra("branch").orEmpty(),
    gitAuthorName = getStringExtra("authorName").orEmpty(),
    gitAuthorEmail = getStringExtra("authorEmail").orEmpty(),
    remoteDatasetId = getStringExtra("dataset").orEmpty(),
    secretLeaseId = secretLeaseId,
    applyRemote = true,
)

internal suspend fun EnginePerfProbe.syncCycle(intent: Intent, run: String): JSONObject {
    requireReadySession()
    val workspace =
        intent.getStringExtra("workspace")
            ?: return errorPayload(run, "sync_cycle requires --es workspace")
    val backend =
        intent.getStringExtra("backend")
            ?: return errorPayload(run, "sync_cycle requires --es backend")
    val endpoint =
        intent.getStringExtra("endpoint")
            ?: return errorPayload(run, "sync_cycle requires --es endpoint")
    val samples = intent.probeCount("samples", 1)
    val secret = intent.getStringExtra("secret")
    val lease =
        secret?.let {
            remoteSync.issueSecretLease(
                it.toByteArray(Charsets.UTF_8),
                SECRET_LEASE_TTL_MS,
            )
        }
    try {
        val request = intent.syncCycleRequest(workspace, backend, endpoint, lease?.leaseId)
        val probeStart = System.nanoTime()
        val probe = try {
            val facts = remoteSync.probeBackend(request)
            SyncBackendObservation.Connected(facts, elapsedMs(probeStart))
        } catch (failure: RemoteSyncBoundaryFailure) {
            SyncBackendObservation.Failed(failure, elapsedMs(probeStart))
        }
        val times = JSONArray()
        val summaries = JSONArray()
        val failures = JSONArray()
        val observedCycles = mutableListOf<RemoteSyncCyclePlanSummary>()
        repeat(samples) {
            val t0 = System.nanoTime()
            try {
                val summary = remoteSync.runCycle(request)
                observedCycles += summary
                times.put(elapsedMs(t0))
                summaries.put(
                    JSONObject()
                        .put("sessionId", summary.sessionId)
                        .put("sessionKind", summary.sessionKind)
                        .put("sessionRevision", summary.sessionRevision)
                        .put("baselineEstablished", summary.baselineEstablished)
                        .put("ensurePresentCount", summary.ensurePresentCount)
                        .put("ensureAbsentCount", summary.ensureAbsentCount)
                        .put("pullPresentCount", summary.pullPresentCount)
                        .put("openConflictCount", summary.openConflictCount)
                        .put("holdCount", summary.holdCount)
                        .put("openConflictPaths", summary.openConflictPaths)
                        .put("conflictRevision", summary.conflictRevision)
                        .put("retryDisposition", summary.retryDisposition),
                )
            } catch (failure: RemoteSyncBoundaryFailure) {
                times.put(elapsedMs(t0))
                failures.put(
                    JSONObject()
                        .put("category", failure.category)
                        .put("code", failure.code)
                        .put("retryDisposition", failure.retryDisposition)
                        .put("diagnostic", failure.diagnostic),
                )
            }
        }
        // The durable record is the authority — read it however the cycles went.
        val status = remoteSync.cycleStatus(workspace)
        check(probe is SyncBackendObservation.Connected) { "Sync backend probe failed: ${probe.toPayload()}" }
        requireSyncTransferEvidence(status.localEntryCount, samples, observedCycles)
        return SyncProbeMeasurement(
            run,
            backend,
            SyncProbeSamples(samples, times, summaries, failures),
            probe,
            status,
        ).toPayload()
    } finally {
        lease?.let { remoteSync.revokeSecretLease(it.leaseId) }
    }
}

private data class SyncProbeSamples(
    val requested: Int,
    val times: JSONArray,
    val summaries: JSONArray,
    val failures: JSONArray,
)

private sealed interface SyncBackendObservation {
    val millis: Double
    data class Connected(val facts: RemoteSyncBackendProbe, override val millis: Double) : SyncBackendObservation
    data class Failed(val failure: RemoteSyncBoundaryFailure, override val millis: Double) : SyncBackendObservation

    fun toPayload(): JSONObject = when (this) {
        is Connected -> JSONObject()
            .put("listedEntryCount", facts.listedEntryCount)
            .put("snapshotRevisionPresent", facts.snapshotRevisionPresent)
            .put("conditionalWrite", facts.conditionalWrite)
            .put("conditionalDelete", facts.conditionalDelete)
            .put("probedAtMs", facts.probedAtMs)

        is Failed -> JSONObject()
            .put("failureCategory", failure.category)
            .put("failureCode", failure.code)
            .put("diagnostic", failure.diagnostic)
    }
}

private class SyncProbeMeasurement(
    val run: String,
    val backend: String,
    val samples: SyncProbeSamples,
    val probe: SyncBackendObservation,
    val status: RemoteSyncCycleStatus,
) {
    fun toPayload(): JSONObject = okPayload(run, "sync_cycle").apply {
        put("metrics", JSONObject().put("cycle_ms", samples.times).put("probe_ms", probe.millis))
        put(
            "facts",
            JSONObject()
                .put("backend", backend)
                .put("samples", samples.requested)
                .put("cycles", samples.summaries)
                .put("cycleFailures", samples.failures)
                .put("backendProbe", probe.toPayload())
                .put("cycleStatus", status.toProbePayload()),
        )
    }
}

private fun RemoteSyncCycleStatus.toProbePayload(): JSONObject = JSONObject()
    .put("hasRecord", hasRecord)
    .put("cycleSeq", cycleSeq)
    .put("cycleId", cycleId)
    .put("backendKind", backendKind)
    .put("sessionId", sessionId)
    .put("applyRemote", applyRemote)
    .put("phase", phase)
    .put("stage", stage)
    .put("ensurePresentCount", ensurePresentCount)
    .put("ensureAbsentCount", ensureAbsentCount)
    .put("pullPresentCount", pullPresentCount)
    .put("openConflictCount", openConflictCount)
    .put("holdCount", holdCount)
    .put("localEntryCount", localEntryCount)
    .put("remoteListedCount", remoteListedCount)
    .put("baselineEntryCount", baselineEntryCount)
    .put("pagesApplied", pagesApplied)
    .put("baselineAdvanced", baselineAdvanced)
    .put("retryDisposition", retryDisposition)
    .put("failureCode", failureCode)
    .put("failureMessage", failureMessage)
    .put("cancelRequested", cancelRequested)
    .put("startedAtMs", startedAtMs)
    .put("finishedAtMs", finishedAtMs)
    .put("lastSuccessfulAtMs", lastSuccessfulAtMs)
    .put("stateStamp", stateStamp)

/** A populated corpus must cause real uploads; successful empty cycles are not transfer evidence. */
internal fun requireSyncTransferEvidence(
    localEntries: Int,
    expectedSamples: Int,
    cycles: List<RemoteSyncCyclePlanSummary>,
): Long {
    check(localEntries > 0) { "Sync probe saw no local documents in the configured corpus" }
    check(cycles.size == expectedSamples) { "Sync probe did not complete every requested cycle" }
    check(cycles.all { it.holdCount == 0 && it.openConflictCount == 0 && it.retryDisposition != "transient" }) {
        "Sync probe contains held, conflicted or unverified transfers"
    }
    val uploads = cycles.sumOf { it.ensurePresentCount.toLong() }
    check(uploads > 0) { "Sync probe completed without uploading any corpus document" }
    return uploads
}
