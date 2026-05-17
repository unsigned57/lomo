# Kotest Golden Sample: `MemoStatisticsUseCase`

Use this walkthrough when converting a small `domain` use case to Kotest `FunSpec`.

## Target

- Production file: `domain/src/main/java/com/lomo/domain/usecase/MemoStatisticsUseCase.kt`
- Test file: `domain/src/test/java/com/lomo/domain/usecase/MemoStatisticsUseCaseTest.kt`
- Layer: `domain`
- Priority: `P0`

## Scenario matrix

- Happy: current-year memo timestamps produce the correct earliest and latest daily memo times.
- Boundary: when the current year has no memos, both daily time bounds stay `null`.
- Failure: a timezone-shifted instant must still count toward the current year after local conversion.
- Must-not-happen: previous-year memos must not leak into current-year time bounds.

## Red

Write the failing Kotest reproducer first:

```kotlin
/*
 * Test Contract:
 * - Unit under test: MemoStatisticsUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: current-year memos set both daily time bounds.
 * - Boundary: missing current-year memos leave both bounds empty.
 * - Failure: UTC timestamps must be converted into the local year before filtering.
 * - Must-not-happen: last-year memos must not influence current-year daily bounds.
 *
 * Observable outcomes:
 * - earliestDailyMemoTime and latestDailyMemoTime values on MemoStatistics.
 *
 * Red phase:
 * - Fails before the fix because MemoStatisticsUseCase did not populate current-year daily time bounds.
 *
 * Excludes:
 * - repository storage, chart rendering, and UI formatting.
 */
class MemoStatisticsUseCaseTest : FunSpec({
    test("computeStatistics reports earliest and latest memo times from current local year") {
        val zone = ZoneId.of("Asia/Shanghai")

        val stats =
            MemoStatisticsUseCase.computeStatistics(
                memos =
                    listOf(
                        memo("last-year", ZonedDateTime.of(2025, 12, 31, 23, 59, 0, 0, zone)),
                        memo("morning", ZonedDateTime.of(2026, 2, 1, 8, 15, 0, 0, zone)),
                        memo("night", ZonedDateTime.of(2026, 4, 3, 23, 40, 0, 0, zone)),
                    ),
                tagCounts = emptyList(),
                zone = zone,
                today = LocalDate.of(2026, 5, 8),
            )

        stats.earliestDailyMemoTime shouldBe LocalTime.of(8, 15)
        stats.latestDailyMemoTime shouldBe LocalTime.of(23, 40)
    }
})
```

Run the narrowest command:

```bash
./gradlew :domain:test --tests 'com.lomo.domain.usecase.MemoStatisticsUseCaseTest'
```

Expected red symptom:

```text
expected:<08:15> but was:<null>
```

## Green

Add the minimal production logic inside `computeStatistics`:

```kotlin
var dailyMemoTimeBounds = DailyMemoTimeBounds()

for (memo in memos) {
    val instant = Instant.ofEpochMilli(memo.timestamp)
    val dateTime = instant.atZone(zone)
    val date = dateTime.toLocalDate()
    val localTime = dateTime.toLocalTime()

    if (!date.isBefore(yearStart) && date.isBefore(nextYearStart)) {
        dailyMemoTimeBounds = dailyMemoTimeBounds.including(localTime)
    }
}
```

Re-run the reproducer until it goes green:

```bash
./gradlew :domain:test --tests 'com.lomo.domain.usecase.MemoStatisticsUseCaseTest'
```

## Refactor

After green, extract the repeated bound update into a tiny helper data class so the loop stays readable:

```kotlin
private data class DailyMemoTimeBounds(
    val earliest: LocalTime? = null,
    val latest: LocalTime? = null,
) {
    fun including(time: LocalTime): DailyMemoTimeBounds =
        DailyMemoTimeBounds(
            earliest = earliest?.takeIf { current -> !time.isBefore(current) } ?: time,
            latest = latest?.takeIf { current -> !time.isAfter(current) } ?: time,
        )
}
```

## Final Kotest shape

Keep the finished test in `FunSpec` form with a structured contract header and Kotest assertions:

```kotlin
class MemoStatisticsUseCaseTest : FunSpec({
    test("computeStatistics leaves daily time bounds empty when current year has no memos") {
        val zone = ZoneId.of("Asia/Shanghai")

        val stats =
            MemoStatisticsUseCase.computeStatistics(
                memos = listOf(memo("old", ZonedDateTime.of(2025, 12, 31, 22, 10, 0, 0, zone))),
                tagCounts = emptyList(),
                zone = zone,
                today = LocalDate.of(2026, 5, 8),
            )

        stats.earliestDailyMemoTime shouldBe null
        stats.latestDailyMemoTime shouldBe null
    }
})
```
