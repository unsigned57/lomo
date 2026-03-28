package com.lomo.app.feature.common

import com.lomo.ui.component.menu.MemoActionId
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: AppConfigUiCoordinator
 * - Behavior focus: heat-style memo action promotion that rewards repeated usage without moving a tile straight to the front after one tap.
 * - Observable outcomes: persisted memo action order after a usage event and the absence of writes when auto reorder is disabled.
 * - Red phase: Fails before the fix because the coordinator currently rewrites the selected action to the first position immediately instead of promoting it gradually.
 * - Excludes: Compose rendering, DataStore serialization details, and settings screen wiring.
 */
class AppConfigUiCoordinatorMemoActionUsageTest {
    private val appConfigRepository: com.lomo.domain.repository.AppConfigRepository = mockk(relaxed = true)

    @Test
    fun `recordMemoActionUsage promotes the selected action only one position when history already exists`() =
        runTest {
            every { appConfigRepository.isMemoActionAutoReorderEnabled() } returns flowOf(true)
            every { appConfigRepository.getMemoActionOrder() } returns
                flowOf(
                    listOf(
                        MemoActionId.COPY.storageKey,
                        MemoActionId.HISTORY.storageKey,
                        MemoActionId.EDIT.storageKey,
                    ),
                )

            AppConfigUiCoordinator(appConfigRepository).recordMemoActionUsage(MemoActionId.EDIT.storageKey)

            coVerify(exactly = 1) {
                appConfigRepository.updateMemoActionOrder(
                    listOf(
                        MemoActionId.COPY.storageKey,
                        MemoActionId.EDIT.storageKey,
                        MemoActionId.HISTORY.storageKey,
                        MemoActionId.SHARE_IMAGE.storageKey,
                        MemoActionId.SHARE_TEXT.storageKey,
                        MemoActionId.LAN_SHARE.storageKey,
                        MemoActionId.PIN.storageKey,
                        MemoActionId.JUMP.storageKey,
                        MemoActionId.DELETE.storageKey,
                    ),
                )
            }
        }

    @Test
    fun `recordMemoActionUsage seeds default order and nudges the selected action when no history exists`() =
        runTest {
            every { appConfigRepository.isMemoActionAutoReorderEnabled() } returns flowOf(true)
            every { appConfigRepository.getMemoActionOrder() } returns flowOf(emptyList())

            AppConfigUiCoordinator(appConfigRepository).recordMemoActionUsage(MemoActionId.DELETE.storageKey)

            coVerify(exactly = 1) {
                appConfigRepository.updateMemoActionOrder(
                    listOf(
                        MemoActionId.COPY.storageKey,
                        MemoActionId.SHARE_IMAGE.storageKey,
                        MemoActionId.SHARE_TEXT.storageKey,
                        MemoActionId.LAN_SHARE.storageKey,
                        MemoActionId.PIN.storageKey,
                        MemoActionId.JUMP.storageKey,
                        MemoActionId.HISTORY.storageKey,
                        MemoActionId.DELETE.storageKey,
                        MemoActionId.EDIT.storageKey,
                    ),
                )
            }
        }

    @Test
    fun `recordMemoActionUsage skips writes when auto reorder is disabled`() =
        runTest {
            every { appConfigRepository.isMemoActionAutoReorderEnabled() } returns flowOf(false)

            AppConfigUiCoordinator(appConfigRepository).recordMemoActionUsage(MemoActionId.HISTORY.storageKey)

            coVerify(exactly = 0) { appConfigRepository.updateMemoActionOrder(any()) }
        }
}
