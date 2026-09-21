package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: main-screen list focus request resolver.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: Jump from Daily Review or Gallery places a memo by list rank. Missing identity is
 *   a terminal Missing outcome; an offscreen rank is WaitingForPage until paging exposes the row.
 *
 * Scenarios:
 * - Given the target is in the loaded snapshot, when resolved, then the request is Immediate.
 * - Given paging placeholders precede the snapshot, when resolved, then the index is absolute.
 * - Given the target is absent from the snapshot, when resolved, then the request is NotFound.
 * - Given an offscreen identity with a store rank, when fallback runs, then placement is requested
 *   and the attempt is WaitingForPage.
 * - Given an identity the store cannot rank, when fallback runs, then the attempt is Missing.
 *
 * Observable outcomes: focus request type, placement indexes, and attempt kind.
 *
 * TDD proof: Fails if offscreen success is collapsed into a boolean false that looks like Missing.
 *
 * Excludes: Compose rendering, NavHost back-stack transitions, and LazyListState scroll physics.
 *
 * Test Change Justification:
 * - Reason category: systemic behavior replacement.
 * - Old behavior/assertion being replaced: offscreen focus returned Boolean false after a successful
 *   scrollToItem, indistinguishable from a missing memo.
 * - Why old assertion is no longer correct: Missing must surface to the user; WaitingForPage must
 *   retry until the identity is in the loaded window.
 * - Coverage preserved by: visible Immediate placement and absent-from-snapshot NotFound remain.
 * - Why this is not fitting the test to the implementation: asserts the public attempt kind the
 *   event host consumes.
 */
class MainScreenFocusRequestResolverTest : AppFunSpec() {
    init {
        test("returns immediate focus request when target memo is visible") {
            val request =
                resolveMainScreenFocusRequest(
                    memoId = "memo-2",
                    visibleUiMemos =
                        listOf(
                            memoUiModel("memo-1"),
                            memoUiModel("memo-2"),
                            memoUiModel("memo-3"),
                        ).toImmutableList(),
                )

            request shouldBe MainScreenFocusRequest.Immediate(index = 1)
        }

        test("returns immediate focus request for the last visible memo") {
            val request =
                resolveMainScreenFocusRequest(
                    memoId = "memo-3",
                    visibleUiMemos =
                        listOf(
                            memoUiModel("memo-1"),
                            memoUiModel("memo-2"),
                            memoUiModel("memo-3"),
                        ).toImmutableList(),
                )

            request shouldBe MainScreenFocusRequest.Immediate(index = 2)
        }

        test("returns absolute focus index when paging snapshot starts after placeholders") {
            val request =
                resolveMainScreenFocusRequest(
                    memoId = "memo-42",
                    visibleUiMemoStartIndex = 40,
                    visibleUiMemos =
                        listOf(
                            memoUiModel("memo-40"),
                            memoUiModel("memo-41"),
                            memoUiModel("memo-42"),
                        ).toImmutableList(),
                )

            request shouldBe MainScreenFocusRequest.Immediate(index = 42)
        }

        test("returns not found when target memo is not visible") {
            val request =
                resolveMainScreenFocusRequest(
                    memoId = "missing",
                    visibleUiMemos =
                        listOf(
                            memoUiModel("memo-1"),
                            memoUiModel("memo-2"),
                        ).toImmutableList(),
                )

            request shouldBe MainScreenFocusRequest.NotFound
        }

        test("focuses matching memo with one direct placement request") {
            runTest {
                val positioner = RecordingFocusPositioner()

                val handled =
                    focusMemoInMainScreen(
                        memoId = "memo-2",
                        visibleUiMemos =
                            listOf(
                                memoUiModel("memo-1"),
                                memoUiModel("memo-2"),
                                memoUiModel("memo-3"),
                            ).toImmutableList(),
                        positioner = positioner,
                    )

                handled shouldBe true
                positioner.indexes shouldBe listOf(1)
            }
        }

        test("does not request placement when target memo is absent") {
            runTest {
                val positioner = RecordingFocusPositioner()

                val handled =
                    focusMemoInMainScreen(
                        memoId = "missing",
                        visibleUiMemos =
                            listOf(
                                memoUiModel("memo-1"),
                                memoUiModel("memo-2"),
                            ).toImmutableList(),
                        positioner = positioner,
                    )

                handled shouldBe false
                positioner.indexes shouldBe emptyList()
            }
        }

        test("offscreen focus requests direct placement and waits for paging to expose the target") {
            runTest {
                val positioner = RecordingFocusPositioner()

                val attempt =
                    focusMemoInMainScreenWithFallback(
                        memoId = "memo-42",
                        visibleUiMemos = listOf(memoUiModel("memo-1")).toImmutableList(),
                        canResolveOffscreenMainListFocus = true,
                        resolveOffscreenIndex = { memoId -> if (memoId == "memo-42") 42 else null },
                        positioner = positioner,
                    )

                attempt shouldBe MainScreenFocusAttempt.WaitingForPage
                positioner.indexes shouldBe listOf(42)
            }
        }

        test("missing identity is a terminal focus outcome") {
            runTest {
                val positioner = RecordingFocusPositioner()

                val attempt =
                    focusMemoInMainScreenWithFallback(
                        memoId = "gone",
                        visibleUiMemos = listOf(memoUiModel("memo-1")).toImmutableList(),
                        canResolveOffscreenMainListFocus = true,
                        resolveOffscreenIndex = { null },
                        positioner = positioner,
                    )

                attempt shouldBe MainScreenFocusAttempt.Missing
                positioner.indexes shouldBe emptyList()
            }
        }
    }

    private fun memoUiModel(id: String): MemoUiModel =
        MemoUiModel(
            memo =
                Memo(
                    id = id,
                    timestamp =
                        LocalDate.of(2026, 4, 10)
                            .atStartOfDay(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli(),
                    content = id,
                    rawContent = id,
                    dateKey = "2026_04_10",
                    localDate = LocalDate.of(2026, 4, 10),
                ),
            processedContent = id,
            renderDocument = com.lomo.app.testing.fakes.emptyRenderDocument(),
            tags = persistentListOf(),
        )

    private class RecordingFocusPositioner : MainScreenFocusPositioner {
        val indexes = mutableListOf<Int>()

        override suspend fun requestPositionAtItem(index: Int) {
            indexes += index
        }
    }
}
