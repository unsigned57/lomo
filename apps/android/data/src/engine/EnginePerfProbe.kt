package com.lomo.data.engine

import android.content.Context
import android.content.Intent
import com.lomo.data.engine.media.PendingMediaStageRegistry
import com.lomo.data.engine.sync.RemoteSyncRepository
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.nativebridge.StoreMemoQuery
import com.lomo.nativebridge.StoreMemoSort
import com.lomo.nativebridge.StoreMemoSortField
import com.lomo.nativebridge.StoreSortDirection
import kotlinx.coroutines.CoroutineDispatcher
import org.json.JSONArray
import org.json.JSONObject

internal class EnginePerfProbe(
    internal val session: ManagedEngineSession,
    internal val switchRoot: SwitchRootStorageUseCase,
    internal val capabilityRegistry: CapabilityRegistry,
    internal val platformAccess: PlatformActionAccess,
    internal val remoteSync: RemoteSyncRepository,
    internal val mediaStages: PendingMediaStageRegistry,
    internal val context: Context,
) {
    internal suspend fun execute(intent: Intent, run: String, ioDispatcher: CoroutineDispatcher): JSONObject =
        when (val op = requireNotNull(intent.getStringExtra("op")) { "perf probe requires op" }) {
            "configure" -> configure(intent, run)
            "rebuild" -> rebuild(intent, run)
            "paging" -> paging(intent, run)
            "paging_prod" -> pagingProduction(intent, run)
            "search" -> search(intent, run)
            "search_prod" -> searchProduction(intent, run)
            "preview_render" -> previewRender(intent, run)
            "media_classify" -> mediaClassify(intent, run)
            "gallery_paging" -> galleryPaging(intent, run)
            "history" -> history(intent, run)
            "history_seed" -> historySeed(intent, run)
            "listchildren" -> listChildren(intent, run)
            "sync_cycle" -> syncCycle(intent, run)
            "ffi" -> ffi(intent, run)
            "views" -> views(intent, run)
            "trash_cycle" -> trashCycle(intent, run)
            "media_stage" -> mediaStage(intent, run, ioDispatcher)
            "workspace_activate" -> measureWorkspace(intent, run, activate = true)
            "workspace_reconcile" -> measureWorkspace(intent, run, activate = false)
            else -> errorPayload(run, "unknown perf probe op: $op")
        }

    internal suspend fun requireReadySession() {
        val readiness = session.requestEngineStart()
        check(readiness is EngineReadiness.Ready) {
            "workspace engine not ready for perf probe: $readiness"
        }
    }

    internal suspend fun configure(intent: Intent, run: String): JSONObject {
        val location =
            intent.getStringExtra("location")
                ?: return errorPayload(run, "configure requires --es location")
        val t0 = System.nanoTime()
        switchRoot.updateRootLocation(StorageLocation(raw = location))
        val configureMs = elapsedMs(t0)
        val readiness = session.requestEngineStart()
        check(readiness is EngineReadiness.Ready && session.mount.value.location == StorageLocation(location)) {
            "configure did not commit the requested Ready workspace: ${session.mount.value}"
        }
        return okPayload(run, "configure").apply {
            put("metrics", JSONObject().put("configure_ms", JSONArray().put(configureMs)))
            put(
                "facts",
                JSONObject()
                    .put("location", location)
                    .put("readiness", readiness.javaClass.simpleName),
            )
        }
    }

    internal suspend fun rebuild(intent: Intent, run: String): JSONObject {
        requireReadySession()
        val samples = intent.probeCount("samples", 3)
        val times = JSONArray()
        var lastResult: com.lomo.nativebridge.StoreRebuildResult? = null
        repeat(samples) {
            val t0 = System.nanoTime()
            lastResult = session.startRebuild(REBUILD_BATCH_SIZE.toUInt())
            times.put(elapsedMs(t0))
        }
        val result =
            lastResult ?: return errorPayload(run, "rebuild produced no samples")
        return okPayload(run, "rebuild").apply {
            put("metrics", JSONObject().put("reconcile_ms", times))
            put(
                "facts",
                JSONObject()
                    .put("memosIndexed", result.memosIndexed.toLong())
                    .put("fileCount", result.fileCount.toLong())
                    .put("attachmentCount", result.attachmentCount.toLong())
                    .put("highWaterRevision", result.highWaterRevision.toLong())
                    .put("rewritten", result.rewritten),
            )
        }
    }

    internal fun firstMemoId(): String? = session
        .queryMemos(
            StoreMemoQuery(
                searchText = null,
                filters = EMPTY_FILTERS,
                sort =
                StoreMemoSort(
                    seed = 0L,
                    timeZone = "UTC",
                    field = StoreMemoSortField.CREATED_AT,
                    direction = StoreSortDirection.DESCENDING,
                ),
                boundary = null,
            ),
            null,
            1u,
            null,
            false,
        ).items
        .firstOrNull()
        ?.memoId

    internal suspend fun ffi(intent: Intent, run: String): JSONObject {
        requireReadySession()
        val samples = intent.probeCount("samples", 15)
        val times = JSONArray()
        repeat(samples) {
            val t0 = System.nanoTime()
            session.renderMarkdown(FFI_BODY)
            times.put(elapsedMs(t0))
        }
        return okPayload(run, "ffi").apply {
            put("metrics", JSONObject().put("ffi_roundtrip_ms", times))
        }
    }
}
