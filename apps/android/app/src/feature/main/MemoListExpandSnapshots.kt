package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.repository.MemoQueryRepository
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import org.koin.compose.koinInject

/**
 * Loads Full snapshots for expanded preview rows. Collapse keeps the cached Full so scrolling
 * back does not re-issue getMemoById.
 */
@Composable
internal fun rememberExpandedMemoSnapshots(
    expandedMemoIds: Set<String>,
): PersistentMap<String, MemoUiModel> {
    val repository = koinInject<MemoQueryRepository>()
    val mapper = koinInject<MemoUiMapper>()
    val appConfig = koinInject<AppConfigStateProvider>()
    val imageMapProvider = koinInject<ImageMapProvider>()
    var snapshots by remember { mutableStateOf(persistentMapOf<String, MemoUiModel>()) }
    LaunchedEffect(expandedMemoIds, repository, mapper, appConfig, imageMapProvider) {
        for (id in expandedMemoIds.filterNot { it in snapshots }) {
            // behavior-contract: loop-io-ok: bounded by the rows the user explicitly expanded
            val memo = repository.getMemoById(id)
            if (memo != null) {
                val model =
                    mapper.mapToCachedUiModel(
                        memo = memo,
                        rootPath = appConfig.rootDirectory.value,
                        imagePath = appConfig.imageDirectory.value,
                        imageMap = imageMapProvider.imageMap.value,
                        reminders = memo.reminders,
                    )
                snapshots = snapshots.put(id, model)
            }
        }
    }
    return snapshots
}
