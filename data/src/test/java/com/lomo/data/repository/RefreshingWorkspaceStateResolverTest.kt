package com.lomo.data.repository

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.WorkspaceTransitionRepository
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec

/*
 * Behavior Contract:
 * - Unit under test: RefreshingWorkspaceStateResolver
 * - Behavior focus: restore-time derived memo and image state reset/rebuild ordering.
 * - Observable outcomes: memo cleanup executes before refresh, and image locations refresh after the
 *   workspace rebuild finishes.
 * - TDD proof: Fails before the fix because workspace rebuild only refreshes markdown-derived state and
 *   leaves the image location cache stale after revision restore.
 * - Excludes: refresh planner internals, Room SQL details, media backend enumeration, and UI behavior.
 */
class RefreshingWorkspaceStateResolverTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("rebuildFromCurrentWorkspace clears memo state refreshes markdown and then refreshes image locations") { `rebuildFromCurrentWorkspace clears memo state refreshes markdown and then refreshes image locations`() }
    }


    @MockK(relaxed = true)
    private lateinit var cleanupRepository: WorkspaceTransitionRepository

    @MockK(relaxed = true)
    private lateinit var mediaRepository: MediaRepository

    @MockK(relaxed = true)
    private lateinit var refreshEngine: MemoRefreshEngine

    private lateinit var resolver: RefreshingWorkspaceStateResolver

    private fun setUp() {
        MockKAnnotations.init(this)
        resolver =
            RefreshingWorkspaceStateResolver(
                cleanupRepository = cleanupRepository,
                mediaRepository = mediaRepository,
                refreshEngine = refreshEngine,
            )
    }

    private fun `rebuildFromCurrentWorkspace clears memo state refreshes markdown and then refreshes image locations`() =
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
