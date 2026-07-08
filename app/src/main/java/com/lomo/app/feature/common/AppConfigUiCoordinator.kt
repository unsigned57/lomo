package com.lomo.app.feature.common

import com.lomo.app.feature.memo.defaultMemoActionOrder
import com.lomo.domain.model.StorageArea
import com.lomo.domain.repository.AppConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map


class AppConfigUiCoordinator(
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

    suspend fun currentImageDirectory(): String? = appConfigRepository.currentLocation(StorageArea.IMAGE)?.raw

    fun voiceDirectory(): Flow<String?> =
        appConfigRepository
            .observeLocation(StorageArea.VOICE)
            .map { it?.raw }

        suspend fun recordMemoActionUsage(
            actionId: String,
            scope: String = MemoActionOrderScopes.MAIN,
        ) {
            if (!appConfigRepository.isMemoActionAutoReorderEnabled().first()) {
                return
            }
            val promotedOrder =
                promoteMemoActionOrder(
                    currentOrder =
                        if (scope == MemoActionOrderScopes.MAIN) {
                            appConfigRepository.getMemoActionOrder().first()
                        } else {
                            appConfigRepository.getMemoActionOrder(scope).first()
                        },
                    selectedActionId = actionId,
                )
            updateMemoActionOrder(order = promotedOrder, scope = scope)
        }

        fun appLockEnabled(): Flow<Boolean?> =
            appConfigRepository
                .isAppLockEnabled()
                .map<Boolean, Boolean?> { it }

        fun sidebarTagOrder(): Flow<List<String>> = appConfigRepository.getSidebarTagOrder()

        suspend fun updateMemoActionOrder(
            order: List<String>,
            scope: String = MemoActionOrderScopes.MAIN,
        ) {
            if (scope == MemoActionOrderScopes.MAIN) {
                appConfigRepository.updateMemoActionOrder(order)
            } else {
                appConfigRepository.updateMemoActionOrder(scope, order)
            }
        }

        suspend fun updateInputToolbarToolOrder(order: List<String>) {
            appConfigRepository.updateInputToolbarToolOrder(order)
        }

        suspend fun updateSidebarTagOrder(order: List<String>) {
            appConfigRepository.updateSidebarTagOrder(order)
        }
    }

internal fun promoteMemoActionOrder(
    currentOrder: List<String>,
    selectedActionId: String,
): List<String> {
    val defaultOrder = defaultMemoActionOrder()
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
