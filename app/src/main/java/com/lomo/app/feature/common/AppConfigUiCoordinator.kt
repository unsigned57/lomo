package com.lomo.app.feature.common

import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.observeAppPreferences
import com.lomo.domain.model.StorageArea
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.ui.component.menu.MemoActionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AppConfigUiCoordinator
    @Inject
    constructor(
        private val appConfigRepository: AppConfigRepository,
    ) {
        fun rootDirectory(): Flow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.ROOT)
                .map { it?.raw }

        fun imageDirectory(): Flow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.IMAGE)
                .map { it?.raw }

        fun voiceDirectory(): Flow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.VOICE)
                .map { it?.raw }

        fun appPreferences(): Flow<AppPreferencesState> = appConfigRepository.observeAppPreferences()

        suspend fun recordMemoActionUsage(actionId: String) {
            if (!appConfigRepository.isMemoActionAutoReorderEnabled().first()) {
                return
            }
            appConfigRepository.updateMemoActionOrder(
                promoteMemoActionOrder(
                    currentOrder = appConfigRepository.getMemoActionOrder().first(),
                    selectedActionId = actionId,
                ),
            )
        }

        fun appLockEnabled(): Flow<Boolean?> =
            appConfigRepository
                .isAppLockEnabled()
                .map<Boolean, Boolean?> { it }
    }

internal fun promoteMemoActionOrder(
    currentOrder: List<String>,
    selectedActionId: String,
): List<String> {
    val defaultOrder = MemoActionId.entries.map(MemoActionId::storageKey)
    val normalizedOrder =
        buildList {
            val seen = mutableSetOf<String>()
            currentOrder
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .filter { it in defaultOrder }
                .filter(seen::add)
                .forEach(::add)
            defaultOrder
                .asSequence()
                .filter(seen::add)
                .forEach(::add)
        }
    val selectedIndex = normalizedOrder.indexOf(selectedActionId)
    if (selectedIndex <= 0) {
        return normalizedOrder
    }
    return normalizedOrder.toMutableList().apply {
        add(selectedIndex - 1, removeAt(selectedIndex))
    }
}
