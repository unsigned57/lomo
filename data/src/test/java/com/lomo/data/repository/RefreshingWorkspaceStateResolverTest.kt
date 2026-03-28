package com.lomo.data.repository

import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.WorkspaceTransitionRepository
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: RefreshingWorkspaceStateResolver
 * - Behavior focus: restore-time derived memo and image state reset/rebuild ordering.
 * - Observable outcomes: memo cleanup executes before refresh, and image locations refresh after the
 *   workspace rebuild finishes.
 * - Red phase: Fails before the fix because workspace rebuild only refreshes markdown-derived state and
 *   leaves the image location cache stale after revision restore.
 * - Excludes: refresh planner internals, Room SQL details, media backend enumeration, and UI behavior.
 */
class RefreshingWorkspaceStateResolverTest {
    @MockK(relaxed = true)
    private lateinit var cleanupRepository: WorkspaceTransitionRepository

    @MockK(relaxed = true)
    private lateinit var mediaRepository: MediaRepository

    @MockK(relaxed = true)
    private lateinit var refreshEngine: MemoRefreshEngine

    private lateinit var resolver: RefreshingWorkspaceStateResolver

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        resolver =
            RefreshingWorkspaceStateResolver(
                cleanupRepository = cleanupRepository,
                mediaRepository = mediaRepository,
                refreshEngine = refreshEngine,
            )
    }

    @Test
    fun `rebuildFromCurrentWorkspace clears memo state refreshes markdown and then refreshes image locations`() =
        runTest {
            coEvery { cleanupRepository.clearMemoStateAfterWorkspaceTransition() } just runs
            coEvery { refreshEngine.refresh() } just runs
            coEvery { mediaRepository.refreshImageLocations() } just runs

            resolver.rebuildFromCurrentWorkspace()

            coVerifyOrder {
                cleanupRepository.clearMemoStateAfterWorkspaceTransition()
                refreshEngine.refresh()
                mediaRepository.refreshImageLocations()
            }
        }
}
