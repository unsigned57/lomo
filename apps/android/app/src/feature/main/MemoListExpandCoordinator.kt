package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MemoQueryRepository
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koin.compose.koinInject

/**
 * One expand request: the memo identity, the content revision the user expanded from, and the
 * workspace epoch that owns the fact. A preview row only observes bounded content, so `updatedAt`
 * is the row-visible revision; a request keyed to a superseded revision can never publish.
 */
internal data class MemoExpandRequest(
    val memoId: String,
    val revision: Long,
    val workspaceEpoch: String,
)

/** Explicit expand lifecycle — a row is collapsed, loading, showing a full snapshot, or failed. */
internal sealed interface MemoExpandEntry {
    data object Loading : MemoExpandEntry

    data class Expanded(
        val model: MemoUiModel,
    ) : MemoExpandEntry

    data class Failed(
        val message: String,
    ) : MemoExpandEntry
}

/**
 * Drives row expansion as a state machine instead of an id→model cache.
 *
 * `sync` reconciles the desired expanded set every time the expand intent, the observed preview
 * revisions, or the workspace epoch move:
 * - a request whose revision or epoch changed cancels the in-flight load and reloads;
 * - a row that leaves the expanded set cancels its load but keeps a cached snapshot for
 *   same-version re-expansion;
 * - a result only publishes while its request key is still the desired one — a late result for a
 *   superseded revision or an old workspace is discarded;
 * - Expanded snapshots are byte-bounded; eviction prefers snapshots no longer requested.
 */
internal class MemoListExpandCoordinator(
    private val scope: CoroutineScope,
    private val loadFullMemo: suspend (String) -> Memo?,
    private val mapFullMemo: suspend (Memo) -> MemoUiModel,
    maxConcurrentLoads: Int = DEFAULT_MAX_CONCURRENT_EXPAND_LOADS,
    private val maxExpandedBodyBytes: Long = DEFAULT_MAX_EXPANDED_BODY_BYTES,
) {
    private class Tracked(
        val request: MemoExpandRequest,
        var entry: MemoExpandEntry,
        var job: Job?,
        var touchedAt: Long,
    )

    private val lock = Any()
    private val tracked = LinkedHashMap<String, Tracked>()
    private val loadGate = Semaphore(maxConcurrentLoads)
    private val _states = MutableStateFlow<PersistentMap<String, MemoExpandEntry>>(persistentMapOf())
    val states: StateFlow<PersistentMap<String, MemoExpandEntry>> = _states.asStateFlow()
    private var epoch: String? = null
    private var desiredIds: Set<String> = emptySet()
    private var touchCounter = 0L

    /**
     * Reconcile the desired expanded set. `requestedRevisions` carries the latest preview revision
     * observed per expanded memo; a null revision keeps the last requested revision (the preview
     * row can leave the loaded window while expansion stays wanted).
     */
    fun sync(
        workspaceEpoch: String,
        requestedRevisions: Map<String, Long?>,
    ) {
        synchronized(lock) {
            if (workspaceEpoch != epoch) {
                epoch = workspaceEpoch
                tracked.values.forEach { it.job?.cancel() }
                tracked.clear()
            }
            val desired =
                requestedRevisions.mapValues { (memoId, revision) ->
                    MemoExpandRequest(
                        memoId = memoId,
                        revision = revision ?: tracked[memoId]?.run { request.revision } ?: UNRESOLVED_REVISION,
                        workspaceEpoch = workspaceEpoch,
                    )
                }
            desiredIds = desired.keys
            for (memoId in tracked.keys.toList()) {
                val existing = tracked.getValue(memoId)
                val want = desired[memoId]
                when {
                    want == existing.request -> existing.touchedAt = ++touchCounter
                    want == null -> {
                        if (existing.entry is MemoExpandEntry.Loading) {
                            existing.job?.cancel()
                            tracked.remove(memoId)
                        }
                    }
                    else -> {
                        existing.job?.cancel()
                        tracked[memoId] =
                            Tracked(
                                request = want,
                                entry = MemoExpandEntry.Loading,
                                job = startLoad(want),
                                touchedAt = ++touchCounter,
                            )
                    }
                }
            }
            for ((memoId, request) in desired) {
                if (memoId !in tracked) {
                    tracked[memoId] =
                        Tracked(
                            request = request,
                            entry = MemoExpandEntry.Loading,
                            job = startLoad(request),
                            touchedAt = ++touchCounter,
                        )
                }
            }
            evictOverflow()
            publish()
        }
    }

    /** Re-issue a failed expand while its request is still desired. */
    fun retryExpand(memoId: String) {
        synchronized(lock) {
            val existing = tracked[memoId] ?: return
            if (existing.entry !is MemoExpandEntry.Failed) return
            existing.job?.cancel()
            tracked[memoId] =
                Tracked(
                    request = existing.request,
                    entry = MemoExpandEntry.Loading,
                    job = startLoad(existing.request),
                    touchedAt = ++touchCounter,
                )
            publish()
        }
    }

    private fun startLoad(request: MemoExpandRequest): Job =
        scope.launch {
            val entry =
                try {
                    loadGate.withPermit {
                        when (val memo = loadFullMemo(request.memoId)) {
                            null -> MemoExpandEntry.Failed("Memo is no longer available")
                            else -> MemoExpandEntry.Expanded(mapFullMemo(memo))
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    MemoExpandEntry.Failed(error.message ?: "Expand load failed")
                }
            synchronized(lock) {
                val current = tracked[request.memoId] ?: return@synchronized
                if (current.request != request || current.entry !is MemoExpandEntry.Loading) {
                    return@synchronized
                }
                current.entry = entry
                current.job = null
                current.touchedAt = ++touchCounter
                evictOverflow()
                publish()
            }
        }

    private fun evictOverflow() {
        var expandedBytes = tracked.values.sumOf { bodyBytes(it.entry) }
        if (expandedBytes <= maxExpandedBodyBytes) return
        val evictionOrder =
            tracked.entries
                .filter { it.value.entry is MemoExpandEntry.Expanded }
                .sortedBy { it.value.touchedAt }
                .partition { it.key !in desiredIds }
        for ((memoId, trackedEntry) in evictionOrder.first + evictionOrder.second) {
            if (expandedBytes <= maxExpandedBodyBytes) break
            expandedBytes -= bodyBytes(trackedEntry.entry)
            tracked.remove(memoId)
        }
    }

    private fun bodyBytes(entry: MemoExpandEntry): Long =
        when (entry) {
            is MemoExpandEntry.Expanded -> entry.model.memo.content.encodeToByteArray().size.toLong()
            else -> 0L
        }

    private fun publish() {
        _states.value =
            tracked.entries.fold(persistentMapOf<String, MemoExpandEntry>()) { acc, (memoId, trackedEntry) ->
                acc.put(memoId, trackedEntry.entry)
            }
    }

    private companion object {
        private const val DEFAULT_MAX_CONCURRENT_EXPAND_LOADS = 4
        private const val DEFAULT_MAX_EXPANDED_BODY_BYTES = 4L * 1024 * 1024
        private const val UNRESOLVED_REVISION = Long.MIN_VALUE
    }
}

internal class MemoListExpandPresentation(
    val states: PersistentMap<String, MemoExpandEntry>,
    val onRetry: (String) -> Unit,
)

/**
 * Compose bridge: keeps the coordinator's desired set in sync with the expand intent and the
 * preview revisions the list currently observes, bound to the mounted workspace identity.
 */
@Composable
internal fun rememberMemoListExpandStates(
    expandedMemoIds: Set<String>,
    previewMemos: List<MemoUiModel>,
): MemoListExpandPresentation {
    val repository = koinInject<MemoQueryRepository>()
    val mapper = koinInject<MemoUiMapper>()
    val appConfig = koinInject<AppConfigStateProvider>()
    val imageMapProvider = koinInject<ImageMapProvider>()
    val readinessRepository = koinInject<EngineReadinessRepository>()
    val scope = rememberCoroutineScope()
    val coordinator =
        remember(scope, repository, mapper, appConfig, imageMapProvider) {
            MemoListExpandCoordinator(
                scope = scope,
                loadFullMemo = repository::getMemoById,
                mapFullMemo = { memo ->
                    mapper.mapToCachedUiModel(
                        memo = memo,
                        rootPath = appConfig.rootDirectory.value,
                        imagePath = appConfig.imageDirectory.value,
                        imageMap = imageMapProvider.imageMap.value,
                        reminders = memo.reminders,
                    )
                },
            )
        }
    val mount by readinessRepository.mount.collectAsStateWithLifecycle()
    val previewRevisions = previewMemos.associate { it.memo.id to it.memo.updatedAt }
    val workspaceEpoch = mount.location?.raw.orEmpty()
    LaunchedEffect(workspaceEpoch, expandedMemoIds, previewRevisions) {
        coordinator.sync(
            workspaceEpoch = workspaceEpoch,
            requestedRevisions = expandedMemoIds.associateWith { previewRevisions[it] },
        )
    }
    val states by coordinator.states.collectAsStateWithLifecycle()
    return MemoListExpandPresentation(
        states = states,
        onRetry = coordinator::retryExpand,
    )
}
