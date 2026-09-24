package com.lomo.app.widget

import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.model.SecuritySessionState
import com.lomo.domain.model.WorkspaceMount
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MemoListQueryRepository
import com.lomo.domain.repository.SecuritySessionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps Glance widgets on the store publication clock. Mutations must not command a widget refresh.
 * The engine-owning process writes a snapshot that projection-only processes can render.
 *
 * Commit publications are debounced into one write per window; mount/security transitions write
 * immediately because privacy and workspace availability cannot wait out a timer. Every write is
 * stamped with the durable core revision it reflects so a restarted binder reconciles the persisted
 * file against the live mount instead of trusting wall time.
 */
internal class WidgetProjectionBinder(
    scope: CoroutineScope,
    private val listQueryRepository: MemoListQueryRepository,
    private val appWidgetRepository: AppWidgetRepository,
    private val snapshotStore: WidgetGlanceSnapshotStore,
    private val engineReadiness: EngineReadinessRepository,
    private val securitySession: SecuritySessionPolicy,
    private val debounceMillis: Long = WIDGET_PROJECTION_DEBOUNCE_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val writeMutex = Mutex()
    private val lastObservedStamp = AtomicLong(0L)

    init {
        // Commit-driven ticks: debounce into one snapshot write per window.
        scope.launch {
            listQueryRepository
                .observeListProjection()
                .collectLatest { publication ->
                    lastObservedStamp.set(publication.coreRevision)
                    delay(debounceMillis)
                    refreshSnapshot()
                }
        }
        // State transitions (mount admission, lock preference) write immediately: a privacy or
        // availability change must not wait out the debounce window, and the first emission after
        // process start reconciles whatever stamp the file still carries.
        scope.launch {
            combine(
                engineReadiness.mount,
                securitySession.observe(),
            ) { mount, session -> mount to session }
                .collectLatest {
                    refreshSnapshot()
                }
        }
    }

    private suspend fun refreshSnapshot() {
        try {
            val mount = engineReadiness.mount.value
            val availability =
                when {
                    !mount.admitsProjectionReads -> WidgetSnapshotAvailability.UNAVAILABLE
                    securitySession.current() != SecuritySessionState.LockOff ->
                        WidgetSnapshotAvailability.REDACTED
                    else -> WidgetSnapshotAvailability.READY
                }
            val items =
                when (availability) {
                    WidgetSnapshotAvailability.UNAVAILABLE -> emptyList()
                    else ->
                        snapshotItems(redacted = availability == WidgetSnapshotAvailability.REDACTED)
                }
            writeMutex.withLock {
                snapshotStore.write(
                    workspaceId = mount.authority?.workspaceId,
                    projectionStamp = lastObservedStamp.get(),
                    availability = availability,
                    items = items,
                )
            }
            // The snapshot is durable before the host is asked to redraw.
            appWidgetRepository.updateAllWidgets()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Timber.w("Widget projection refresh failed: %s", failure.javaClass.simpleName)
        }
    }

    private suspend fun snapshotItems(redacted: Boolean): List<WidgetGlanceSnapshotItem> =
        listQueryRepository.getRecentMemos(WIDGET_MEMO_LIMIT).map { memo ->
            WidgetGlanceSnapshotItem(
                id = memo.id,
                timestampMillis = memo.timestamp,
                previewText =
                    if (redacted) {
                        ""
                    } else {
                        resolveWidgetMemoItemPresentation(memo, nowMillis()).previewText
                    },
            )
        }

    companion object {
        const val WIDGET_PROJECTION_DEBOUNCE_MILLIS = 500L
    }
}
