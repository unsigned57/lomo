package com.lomo.app.widget

import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.repository.MemoListQueryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Keeps Glance widgets on the store publication clock. Mutations must not command a widget refresh.
 * The engine-owning process writes a snapshot that projection-only processes can render.
 */
internal class WidgetProjectionBinder(
    scope: CoroutineScope,
    listQueryRepository: MemoListQueryRepository,
    appWidgetRepository: AppWidgetRepository,
    snapshotStore: WidgetGlanceSnapshotStore,
    debounceMillis: Long = WIDGET_PROJECTION_DEBOUNCE_MILLIS,
    nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    init {
        scope.launch {
            listQueryRepository
                .observeListProjection()
                .collectLatest {
                    delay(debounceMillis)
                    try {
                        val items =
                            listQueryRepository.getRecentMemos(WIDGET_MEMO_LIMIT).map { memo ->
                                val presentation =
                                    resolveWidgetMemoItemPresentation(
                                        memo = memo,
                                        nowMillis = nowMillis(),
                                    )
                                WidgetGlanceSnapshotItem(
                                    id = presentation.id,
                                    timestampMillis = presentation.timestampMillis,
                                    previewText = presentation.previewText,
                                )
                            }
                        snapshotStore.write(items)
                        appWidgetRepository.updateAllWidgets()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Timber.w("Widget projection refresh failed: %s", failure.javaClass.simpleName)
                    }
                }
        }
    }

    companion object {
        const val WIDGET_PROJECTION_DEBOUNCE_MILLIS = 500L
    }
}
