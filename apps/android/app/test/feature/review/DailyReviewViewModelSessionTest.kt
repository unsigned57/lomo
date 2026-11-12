package com.lomo.app.feature.review

import app.cash.turbine.test
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.provider.emptyImageMapProvider
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeDailyReviewSessionRepository
import com.lomo.app.testing.fakes.FakeMediaRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.app.testing.fakes.storeBackedToggleMemoCheckboxUseCase
import com.lomo.app.testing.fakes.testMemoUiMapper
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DailyReviewSessionUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.FakeSaveImageUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: review state publishes matching memos, cursor options and page as one session.
 * - Owning layer: app ViewModel; priority: P1.
 * Scenarios:
 * - Given loaded memos and a nonzero page, when attachment/random options change, then Loading
 *   precedes a Ready state containing only matches, page zero, and persisted new options.
 * - Given valid presentation collaborators, when constructing, then preferences and state load.
 * - Production scope: apps/android/app/src/feature/review/DailyReviewViewModel.kt
 * - Production scope: apps/android/domain/src/usecase/DailyReviewQueryUseCase.kt
 * - Production scope: apps/android/domain/src/usecase/DailyReviewSessionUseCase.kt
 * - Observable outcomes: Turbine state sequence, memo identities, page and stored options.
 * - TDD proof: review-state-red.log observed Ready with [] before mapped [plain, photo].
 * - Test Change Justification: extends the existing construction smoke regression with atomic
 *   query replacement; existing smoke assertions remain, no implementation-text checks added.
 * - Excludes: native order (store contract), Compose pixels and Android persistence transport.
 * Test Change Justification:
 * Reason category: bounded review-session migration.
 * Old behavior/assertion being replaced: The constructor smoke assertions remain; atomic replacement scenarios extend them.
 * Why old assertion is no longer correct: existing outcome assertions remain valid and retained.
 * Coverage preserved by: The public Loading/Ready sequence, memo IDs, page reset and persisted options remain asserted.
 * Why this is not fitting the test to the implementation: no expected product outcome is weakened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyReviewViewModelSessionTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()
    private val memoRepository = FakeMemoStore()
    private val appConfigRepository = FakeAppConfigRepository()
    private val imageMapProvider = emptyImageMapProvider()
    private val updateMemoContentUseCase =
        UpdateMemoContentUseCase(
            repository = com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository),
            validator = ValidateMemoContentUseCase(),
        )
    private val deleteMemoUseCase = DeleteMemoUseCase(
        com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository),
    )
    private val dailyReviewSessionRepository = FakeDailyReviewSessionRepository()
    private val dailyReviewSessionUseCase = DailyReviewSessionUseCase(dailyReviewSessionRepository)
    private val dailyReviewQueryUseCase =
        DailyReviewQueryUseCase(com.lomo.app.testing.fakes.FakeMemoQueryRepository(memoRepository))
    private val toggleMemoCheckboxUseCase = storeBackedToggleMemoCheckboxUseCase(memoRepository)
    private val saveImageUseCase: SaveImageUseCase = FakeSaveImageUseCase(FakeMediaRepository())

    init {
        extension(MainDispatcherExtension(testDispatcher))

        test("given review filter changes when reloading then each ready state has matching memos and page resets") {
            runTest {
                memoRepository.setActiveMemos(
                    listOf(sampleMemo("plain"), sampleMemo("photo").copy(imageUrls = listOf("photo.jpg"))),
                )
                val viewModel = createViewModel()
                viewModel.uiState.test {
                    awaitItem() shouldBe DailyReviewScreenState.Loading
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<DailyReviewScreenState.Ready>().memos.map {
                        it.memo.id
                    }.toSet() shouldBe
                        setOf(
                            "plain",
                            "photo",
                        )
                    viewModel.onPageChanged(1)
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<DailyReviewScreenState.Ready>().pageIndex shouldBe 1
                    val options = com.lomo.domain.model.DailyReviewOptions(
                        sort = com.lomo.domain.model.DailyReviewSort.RANDOM,
                        filter = com.lomo.domain.model.MemoListFilter(hasAttachment = true),
                    )
                    viewModel.updateReviewOptions(options)
                    advanceUntilIdle()
                    awaitItem() shouldBe DailyReviewScreenState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<DailyReviewScreenState.Ready>()
                    ready.memos.map { it.memo.id } shouldBe listOf("photo")
                    ready.pageIndex shouldBe 0
                    ready.options shouldBe options
                    dailyReviewSessionRepository.getSession()?.options shouldBe options
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

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

    private fun createViewModel(): DailyReviewViewModel = DailyReviewViewModel(
        DailyReviewViewModelDependencies(
            observeActiveDayCountUseCase =
            ObserveActiveDayCountUseCase(
                com.lomo.app.testing.fakes.FakeMemoStatisticsRepository(memoRepository),
            ),
            appConfigStateProvider =
            com.lomo.app.feature.common.AppConfigStateProvider(
                appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
                appPreferencesSnapshotRepository = appConfigRepository,
                customFontStore = com.lomo.app.testing.fakes.FakeCustomFontStore(),
                customFontHost = com.lomo.app.testing.fakes.testCustomFontHost(
                    com.lomo.app.testing.fakes.FakeCustomFontStore(),
                ),
                preferencesHealthRepository = com.lomo.app.testing.fakes.FakePreferencesHealthRepository(),
                appScope = CoroutineScope(SupervisorJob() + testDispatcher),
            ),
            appConfigUiCoordinator = AppConfigUiCoordinator(appConfigRepository),
            imageMapProvider = imageMapProvider,
            memoUiMapper = testMemoUiMapper(
                dispatcherProvider = com.lomo.domain.usecase.SingleDispatcherProvider(testDispatcher),
            ),
            deleteMemoUseCase = deleteMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            toggleMemoCheckboxUseCase = toggleMemoCheckboxUseCase,
            saveImageUseCase = saveImageUseCase,
            dailyReviewQueryUseCase = dailyReviewQueryUseCase,
            dailyReviewSessionUseCase = dailyReviewSessionUseCase,
        ),
    )

    private fun sampleMemo(id: String): Memo = Memo(
        id = id,
        timestamp = 1L,
        content = id,
        rawContent = "- 10:00 $id",
        dateKey = "2026_04_16",
    )
}
