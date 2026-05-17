# Kotest Golden Sample: `StatisticsViewModel`

Use this walkthrough when converting an `app` ViewModel test to Kotest `FunSpec` with `MainDispatcherExtension` and Turbine-friendly state assertions.

## Target

- Production file: `app/src/main/java/com/lomo/app/feature/statistics/StatisticsViewModel.kt`
- Test file: `app/src/test/java/com/lomo/app/feature/statistics/StatisticsViewModelTest.kt`
- Layer: `app`
- Priority: `P1`

## Scenario matrix

- Happy: sharing statistics persists the PNG and emits a one-off event with the saved path.
- Boundary: consuming the current event clears it but does not touch a different event id.
- Failure: share persistence errors surface a user-visible error message.
- Must-not-happen: screenshot share work must not bypass `PersistShareImageUseCase`.

## Red

Start with a failing Kotest reproducer that uses the shared dispatcher extension and small fakes:

```kotlin
private class FakeMemoStatisticsUseCase(
    private val result: MemoStatistics,
) : MemoStatisticsUseCaseContract {
    override suspend fun invoke(): MemoStatistics = result
}

private class FakePersistShareImageUseCase(
    private val filePath: String = "/tmp/stats_share_1.png",
    private val failure: Throwable? = null,
) : PersistShareImageUseCaseContract {
    var lastPrefix: String? = null

    override suspend fun invoke(
        pngBytes: ByteArray,
        fileNamePrefix: String,
    ): String {
        lastPrefix = fileNamePrefix
        failure?.let { throw it }
        return filePath
    }
}

/*
 * Test Contract:
 * - Unit under test: StatisticsViewModel
 * - Owning layer: app
 * - Priority tier: P1
 *
 * Scenario matrix:
 * - Happy: share requests emit a file-path event after persistence succeeds.
 * - Boundary: consuming the current event clears only that event.
 * - Failure: persistence failures expose a user-visible share error.
 * - Must-not-happen: the ViewModel must not invent a file path without calling the use case.
 *
 * Observable outcomes:
 * - shareImageEvent, shareErrorMessage, and persisted filename prefix.
 *
 * Red phase:
 * - Fails before the fix because StatisticsViewModel could not persist the PNG and emitted no share event.
 *
 * Excludes:
 * - Compose screenshot capture, FileProvider URIs, and Android share-sheet dispatch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest : FunSpec({
    val mainDispatcher = MainDispatcherExtension()
    extension(mainDispatcher)

    test("shareStatisticsImage persists png bytes with stats prefix and emits event") {
        runTest {
            val stats = sampleStatistics()
            val fakeStatsUseCase = FakeMemoStatisticsUseCase(result = stats)
            val fakePersistShareImage = FakePersistShareImageUseCase()
            val viewModel =
                StatisticsViewModel(
                    memoStatisticsUseCase = fakeStatsUseCase,
                    persistShareImageUseCase = fakePersistShareImage,
                )

            viewModel.shareStatisticsImage(byteArrayOf(1, 2, 3))
            advanceUntilIdle()

            viewModel.shareImageEvent.value shouldBe StatisticsShareImageEvent(1L, "/tmp/stats_share_1.png")
            fakePersistShareImage.lastPrefix shouldBe "stats_share"
        }
    }
})
```

Run the narrowest command:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.lomo.app.feature.statistics.StatisticsViewModelTest'
```

Expected red symptom:

```text
expected:<StatisticsShareImageEvent(id=1, filePath=/tmp/stats_share_1.png)> but was:<null>
```

## Green

Add the minimal production path that delegates persistence and emits a one-off event:

```kotlin
fun shareStatisticsImage(pngBytes: ByteArray) {
    viewModelScope.launch {
        runCatching {
            persistShareImageUseCase(
                pngBytes = pngBytes,
                fileNamePrefix = STATS_SHARE_FILE_PREFIX,
            )
        }.onSuccess { filePath ->
            _shareErrorMessage.value = null
            _shareImageEvent.value =
                StatisticsShareImageEvent(
                    id = nextShareEventId(),
                    filePath = filePath,
                )
        }.onFailure(::reportShareFailure)
    }
}
```

Re-run the reproducer:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.lomo.app.feature.statistics.StatisticsViewModelTest'
```

## Refactor

After green, add the failure-path companion test using `assertSoftly` so any
regression in either field shows up in one shot:

```kotlin
test("shareStatisticsImage exposes share error when persistence fails") {
    runTest {
        val fakePersistShareImage = FakePersistShareImageUseCase(failure = IllegalStateException("disk full"))
        val viewModel =
            StatisticsViewModel(
                memoStatisticsUseCase = FakeMemoStatisticsUseCase(sampleStatistics()),
                persistShareImageUseCase = fakePersistShareImage,
            )

        viewModel.shareStatisticsImage(byteArrayOf(9, 8, 7))
        advanceUntilIdle()

        assertSoftly(viewModel) {
            shareErrorMessage.value shouldBe "Failed to share statistics: disk full"
            shareImageEvent.value shouldBe null
        }
    }
}
```

When the user-visible contract is the *sequence* of state emissions, use
Turbine instead of inspecting `StateFlow.value` between calls:

```kotlin
test("shareStatisticsImage streams loading then loaded events") {
    runTest {
        val viewModel = newViewModel()
        viewModel.shareImageEvent.test {
            awaitItem() shouldBe null
            viewModel.shareStatisticsImage(byteArrayOf(1, 2, 3))
            awaitItem().shouldBeInstanceOf<StatisticsShareImageEvent>()
            cancelAndConsumeRemainingEvents()
        }
    }
}
```

## Final Kotest shape

The finished file should stay on `FunSpec`, register `MainDispatcherExtension`, and keep user-visible assertions at the `StateFlow` boundary:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest : FunSpec({
    val mainDispatcher = MainDispatcherExtension()
    extension(mainDispatcher)

    test("loadStatistics still exposes memo statistics state") {
        runTest {
            val viewModel =
                StatisticsViewModel(
                    memoStatisticsUseCase = FakeMemoStatisticsUseCase(sampleStatistics()),
                    persistShareImageUseCase = FakePersistShareImageUseCase(),
                )

            viewModel.ensureLoaded()
            advanceUntilIdle()

            viewModel.uiState.value shouldBe UiState.Success(sampleStatistics())
        }
    }
})
```
