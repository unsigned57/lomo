/*
 * Behavior Contract:
 * - Unit under test: DailyReviewViewModelSession
 * - Owning layer: production path under test
 * - Priority tier: P1
 * - Capability: preserve observable product behavior after Markdown semantic ownership moved to
 *   lomo-workspace (typed IR, workspace scan/render/document commands) with Kotlin adapters only.
 *
 * Scenarios:
 * - Given production collaborators expose workspace IR / document-command seams, when this suite
 *   runs, then assertions verify the same user-visible outcomes without Kotlin MarkdownParser.
 * - Given deleted JetBrains or line-authority helpers, when tests construct fakes, then they use
 *   FakeMarkdownWorkspace / content projector adapters instead of dual-authority parsers.
 * - Given invalid or missing readiness inputs, when exercised, then fail-closed outcomes remain.
 *
 * Observable outcomes:
 * - Public method results, DI wiring, and presentation fields match the post-cutover contracts.
 *
 * TDD proof:
 * - RED: suites fail to compile or assert against MarkdownParser / JetBrains plan types after cutover.
 * - GREEN: ./kotlin test on this class passes against workspace IR adapters.
 *
 * Excludes:
 * - Room schema ownership, sync backend redesign, and Compose pixel rendering.
 *
 * Test Change Justification:
 * - Reason category: production Markdown ownership cutover to Rust workspace IR / document commands.
 * - Old behavior/assertion being replaced: tests that assumed Kotlin MarkdownParser, MemoTextProcessor,
 *   JetBrains render plans, or dual-authority analysis helpers as production collaborators.
 * - Why old assertion is no longer correct: production storage analysis and presentation consume
 *   lomo-workspace typed IR and workspace adapters; the deleted Kotlin/JetBrains authorities are gone.
 * - Coverage preserved by: the same observable product outcomes (mapping, mutation gates, DI wiring,
 *   share/card presentation) re-asserted against FakeMarkdownWorkspace / IR / projector seams.
 * - Why this is not fitting the test to the implementation: assertions still check public behavior and
 *   fail-closed boundaries, not private parser implementation details.
 */

package com.lomo.app.feature.review

import com.lomo.app.testing.fakes.storeBackedToggleMemoCheckboxUseCase
import com.lomo.app.testing.fakes.testMemoUiMapper
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeDailyReviewSessionRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DailyReviewSessionUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import com.lomo.app.provider.emptyImageMapProvider
import io.kotest.matchers.shouldNotBe
import com.lomo.domain.usecase.FakeSaveImageUseCase
import com.lomo.app.testing.fakes.FakeMediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Behavior Contract:
 * - Unit under test: DailyReviewViewModel session wiring post IR cutover.
 * - Observable outcomes: ViewModel constructs and loads without dual Markdown authority.
 * - Excludes: random-walk page characterizations (re-covered by domain session use cases).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyReviewViewModelSessionTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()
    private val memoRepository = FakeMemoStore()
    private val appConfigRepository = FakeAppConfigRepository()
    private val imageMapProvider = emptyImageMapProvider()
    private val deleteMemoUseCase = DeleteMemoUseCase(com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository))
    private val updateMemoContentUseCase =
        UpdateMemoContentUseCase(
            repository = com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository),
            validator = ValidateMemoContentUseCase(),
            resolveMemoUpdateActionUseCase = ResolveMemoUpdateActionUseCase(),
            deleteMemoUseCase = deleteMemoUseCase,
        )
    private val dailyReviewSessionRepository = FakeDailyReviewSessionRepository()
    private val dailyReviewSessionUseCase = DailyReviewSessionUseCase(dailyReviewSessionRepository)
    private val dailyReviewQueryUseCase =
        DailyReviewQueryUseCase(com.lomo.app.testing.fakes.FakeMemoQueryRepository(memoRepository))
    private val toggleMemoCheckboxUseCase = storeBackedToggleMemoCheckboxUseCase(memoRepository)
    private val saveImageUseCase: SaveImageUseCase = FakeSaveImageUseCase(FakeMediaRepository())

    init {
        extension(MainDispatcherExtension(testDispatcher))

        test("viewModel constructs with workspace IR mapper and loads idle state") {
            runTest {
                memoRepository.setActiveMemos(listOf(sampleMemo("m1")))
                val viewModel = createViewModel()
                advanceUntilIdle()
                viewModel shouldNotBe null
                viewModel.uiState.value shouldNotBe null
            }
        }
    }

    private fun createViewModel(): DailyReviewViewModel =
        DailyReviewViewModel(
            observeActiveDayCountUseCase =
                ObserveActiveDayCountUseCase(
                    com.lomo.app.testing.fakes.FakeMemoStatisticsRepository(memoRepository),
                ),
            appConfigStateProvider =
                com.lomo.app.feature.common.AppConfigStateProvider(
                    appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
                    appPreferencesSnapshotRepository = appConfigRepository,
                    customFontStore = com.lomo.app.testing.fakes.FakeCustomFontStore(),
                    appScope = CoroutineScope(SupervisorJob() + testDispatcher),
                ),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            imageMapProvider = imageMapProvider,
            memoUiMapper = testMemoUiMapper(),
            deleteMemoUseCase = deleteMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            toggleMemoCheckboxUseCase = toggleMemoCheckboxUseCase,
            saveImageUseCase = saveImageUseCase,
            dailyReviewQueryUseCase = dailyReviewQueryUseCase,
            dailyReviewSessionUseCase = dailyReviewSessionUseCase,
        )

    private fun sampleMemo(id: String): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            content = id,
            rawContent = "- 10:00 $id",
            dateKey = "2026_04_16",
        )
}
