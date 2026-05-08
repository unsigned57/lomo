package com.lomo.app.feature.common

import com.lomo.domain.repository.AppConfigRepository
import com.lomo.ui.component.menu.MemoActionId
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: AppConfigUiCoordinator scoped memo-action ordering.
 * - Behavior focus: each screen surface must keep its own memo action order and auto-reorder history.
 * - Observable outcomes: repository reads and writes include the same surface scope, and the legacy main
 *   action-order setter is not used for non-main screens.
 * - Red phase: Fails before the fix because action ordering is global, so Gallery/Search/Tag/Review writes
 *   overwrite the main list and each other instead of carrying a page scope.
 * - Excludes: Compose menu rendering, DataStore serialization, and drag gesture detection.
 */
class AppConfigUiCoordinatorScopedMemoActionUsageTest {
    private val appConfigRepository: AppConfigRepository = mockk(relaxed = true)

    @Test
    fun `recordMemoActionUsage promotes only the requested surface order`() =
        runTest {
            every { appConfigRepository.isMemoActionAutoReorderEnabled() } returns flowOf(true)
            every { appConfigRepository.getMemoActionOrder(MemoActionOrderScopes.GALLERY) } returns
                flowOf(
                    listOf(
                        MemoActionId.COPY.storageKey,
                        MemoActionId.JUMP.storageKey,
                        MemoActionId.EDIT.storageKey,
                    ),
                )

            AppConfigUiCoordinator(appConfigRepository).recordMemoActionUsage(
                scope = MemoActionOrderScopes.GALLERY,
                actionId = MemoActionId.EDIT.storageKey,
            )

            coVerify(exactly = 1) {
                appConfigRepository.updateMemoActionOrder(
                    MemoActionOrderScopes.GALLERY,
                    listOf(
                        MemoActionId.COPY.storageKey,
                        MemoActionId.EDIT.storageKey,
                        MemoActionId.JUMP.storageKey,
                        MemoActionId.SHARE_IMAGE.storageKey,
                        MemoActionId.SHARE_TEXT.storageKey,
                        MemoActionId.LAN_SHARE.storageKey,
                        MemoActionId.PIN.storageKey,
                        MemoActionId.HISTORY.storageKey,
                        MemoActionId.DELETE.storageKey,
                    ),
                )
            }
            coVerify(exactly = 0) { appConfigRepository.updateMemoActionOrder(any<List<String>>()) }
        }

    @Test
    fun `manual reorder writes only the requested surface order`() =
        runTest {
            val order = listOf(MemoActionId.JUMP.storageKey, MemoActionId.COPY.storageKey)

            AppConfigUiCoordinator(appConfigRepository).updateMemoActionOrder(
                scope = MemoActionOrderScopes.REVIEW,
                order = order,
            )

            coVerify(exactly = 1) {
                appConfigRepository.updateMemoActionOrder(MemoActionOrderScopes.REVIEW, order)
            }
            coVerify(exactly = 0) { appConfigRepository.updateMemoActionOrder(any<List<String>>()) }
        }
}
