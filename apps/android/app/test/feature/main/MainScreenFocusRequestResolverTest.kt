/*
 * Behavior Contract:
 * - Unit under test: MainScreenFocusRequestResolver
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
 * Test Contract:
 * - Unit under test: main-screen Jump focus request resolver.
 * - Behavior focus: Jump actions returning from Daily Review or Gallery must resolve to an immediate list-focus
 *   request for the matching memo and request direct list placement instead of a visible scroll effect.
 * - Observable outcomes: returned focus request type, target index for hit and miss cases, and requested direct-placement indexes.
 * - Red phase: Fails before the fix because main-screen Jump handling still inlines a long-distance animated
 *   list scroll instead of resolving an immediate-focus request contract.
 * - Excludes: Compose rendering, NavHost back-stack transitions, and LazyListState scroll physics.
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

            (request) shouldBe (MainScreenFocusRequest.Immediate(index = 1))
        }
    }

    init {
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

            (request) shouldBe (MainScreenFocusRequest.Immediate(index = 2))
        }
    }

    init {
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

            (request) shouldBe (MainScreenFocusRequest.Immediate(index = 42))
        }
    }

    init {
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

            (request) shouldBe (MainScreenFocusRequest.NotFound)
        }
    }

    init {
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

                ((handled)) shouldBe true
                (positioner.indexes) shouldBe (listOf(1))
            }
        }
    }

    init {
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

                (handled) shouldBe (false)
                (positioner.indexes) shouldBe (emptyList<Int>())
            }
        }
    }

    init {
        test("offscreen focus requests direct placement and keeps request pending until paging exposes the target") {
            runTest {
                val positioner = RecordingFocusPositioner()

                val handled =
                    focusMemoInMainScreenWithFallback(
                        memoId = "memo-42",
                        visibleUiMemos = listOf(memoUiModel("memo-1")).toImmutableList(),
                        canResolveOffscreenMainListFocus = true,
                        resolveOffscreenIndex = { memoId -> if (memoId == "memo-42") 42 else null },
                        positioner = positioner,
                    )

                (handled) shouldBe (false)
                (positioner.indexes) shouldBe (listOf(42))
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
