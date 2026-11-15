/*
 * Behavior Contract:
 * - Unit under test: WidgetPreviewFixtures & WidgetPreviewPublishCoordinator
 * - Owning layer: app
 * - Priority tier: P1
 * - Production scope: deterministic Glance preview fixture generation and API 35+
 *   AppWidgetManager.setWidgetPreview stamp deduplication without OS rate-limit thrash.
 * - Capability: Home-screen preview publishing is a no-op below API 35, publishes all 4 widgets
 *   on API 35+ when version/locale/nightMode stamp changes, only saves the stamp when every widget
 *   preview is accepted, and builds non-empty realistic READY fixtures.
 *
 * Scenarios:
 * - Given SDK < 35, when publishIfNeeded runs, then it skips without invoking preview composition.
 * - Given SDK >= 35 and matching persisted stamp, when publishIfNeeded runs, then it skips.
 * - Given SDK >= 35 and stale/changed stamp with all providers returning true, when publishIfNeeded
 *   runs, then all 4 providers are published and the new stamp is stored.
 * - Given SDK >= 35 and one provider returning false (rate-limited), when publishIfNeeded runs,
 *   then the new stamp is not persisted so future launches retry.
 * - Given sample localized copy, when buildSampleWidgetRenderSnapshot is called, then Quick Notes,
 *   Tasks, Insights stats + 126 heatmap days and Review quote/tags are READY and populated.
 * Observable outcomes: publication result, provider IDs, persisted stamp, and populated preview slices.
 * TDD proof: original RED execution is not recorded in this snapshot; verify the existing scenarios
 * with `./kotlin test --include-module=app --include-classes='com.lomo.app.widget.WidgetPreviewGenerationTest'`.
 * Excludes: launcher rendering and Android's actual preview rate-limit implementation.
 */

package com.lomo.app.widget

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private const val API_34 = 34
private const val API_35 = 35

class WidgetPreviewGenerationTest : AppFunSpec() {
    init {
        test("below API 35, preview publishing returns UnsupportedSdk without invoking publisher") {
            runTest {
                var persistedStamp: String? = null
                val publishedTargets = mutableListOf<WidgetPreviewTarget>()
                val coordinator =
                    WidgetPreviewPublishCoordinator(
                        sdkInt = API_34,
                        readStamp = { persistedStamp },
                        writeStamp = { persistedStamp = it },
                        publishTarget = { target ->
                            publishedTargets += target
                            true
                        },
                    )

                val outcome =
                    coordinator.publishIfNeeded(
                        stamp =
                        buildWidgetPreviewStamp(
                            versionCode = 100L,
                            lastUpdateTime = 1234L,
                            localeTag = "en-US",
                            nightModeMask = 16,
                        ),
                    )

                outcome shouldBe WidgetPreviewPublishOutcome.UnsupportedSdk
                publishedTargets shouldBe emptyList()
                persistedStamp shouldBe null
            }
        }

        test("on API 35, matching stamp skips redundant setWidgetPreview calls") {
            runTest {
                val currentStamp =
                    buildWidgetPreviewStamp(
                        versionCode = 100L,
                        lastUpdateTime = 1234L,
                        localeTag = "zh-CN",
                        nightModeMask = 32,
                    )
                var persistedStamp: String? = currentStamp
                val publishedTargets = mutableListOf<WidgetPreviewTarget>()
                val coordinator =
                    WidgetPreviewPublishCoordinator(
                        sdkInt = API_35,
                        readStamp = { persistedStamp },
                        writeStamp = { persistedStamp = it },
                        publishTarget = { target ->
                            publishedTargets += target
                            true
                        },
                    )

                val outcome = coordinator.publishIfNeeded(currentStamp)

                outcome shouldBe WidgetPreviewPublishOutcome.UpToDate
                publishedTargets shouldBe emptyList()
            }
        }

        test("on API 35, changed stamp publishes all 4 home-screen widgets and saves stamp") {
            runTest {
                var persistedStamp: String? = "old-stamp"
                val publishedTargets = mutableListOf<WidgetPreviewTarget>()
                val newStamp =
                    buildWidgetPreviewStamp(
                        versionCode = 101L,
                        lastUpdateTime = 5678L,
                        localeTag = "en-US",
                        nightModeMask = 32,
                    )
                val coordinator =
                    WidgetPreviewPublishCoordinator(
                        sdkInt = API_35,
                        readStamp = { persistedStamp },
                        writeStamp = { persistedStamp = it },
                        publishTarget = { target ->
                            publishedTargets += target
                            true
                        },
                    )

                val outcome = coordinator.publishIfNeeded(newStamp)

                outcome shouldBe WidgetPreviewPublishOutcome.Published
                publishedTargets shouldBe WidgetPreviewTarget.entries
                persistedStamp shouldBe newStamp
            }
        }

        test("on API 35, rate-limited partial rejection preserves old stamp for later retry") {
            runTest {
                var persistedStamp: String? = "old-stamp"
                val newStamp =
                    buildWidgetPreviewStamp(
                        versionCode = 101L,
                        lastUpdateTime = 5678L,
                        localeTag = "en-US",
                        nightModeMask = 16,
                    )
                val coordinator =
                    WidgetPreviewPublishCoordinator(
                        sdkInt = API_35,
                        readStamp = { persistedStamp },
                        writeStamp = { persistedStamp = it },
                        publishTarget = { target -> target != WidgetPreviewTarget.REVIEW },
                    )

                val outcome = coordinator.publishIfNeeded(newStamp)

                outcome shouldBe WidgetPreviewPublishOutcome.PartialFailure
                persistedStamp shouldBe "old-stamp"
            }
        }

        test("sample preview fixture produces populated READY snapshot across all widgets") {
            val nowMillis = 1_700_000_120_000L
            val snapshot =
                buildSampleWidgetRenderSnapshot(
                    strings =
                    WidgetPreviewSampleStrings(
                        memoFirst = "Capture ideas fast with tags and Markdown.",
                        memoSecond = "Morning walk notes and reading highlights.",
                        taskFirst = "Finish reviewing Q4 notes",
                        taskSecond = "Sketch weekend reading list",
                        reviewQuote = "Let fleeting fragments compound into lasting clarity.",
                        tagPrimary = "ideas",
                        tagSecondary = "reading",
                    ),
                    nowMillis = nowMillis,
                    asOfEpochDay = 20_000L,
                )

            snapshot.availability shouldBe WidgetSnapshotAvailability.READY
            snapshot.items shouldHaveSize 2
            snapshot.items[0].isPinned shouldBe true
            snapshot.tasks shouldHaveSize 2
            snapshot.tasks[0].done shouldBe true
            snapshot.tasks[1].done shouldBe false
            snapshot.stats.shouldNotBeNull().apply {
                currentStreak shouldBe 7
                totalMemos shouldBe 148
                thisWeekCount shouldBe 12
                activeDays shouldBe 46
                heatmapDays shouldHaveSize WIDGET_HEATMAP_DAY_LIMIT
            }
            snapshot.reviews shouldHaveSize 2
            snapshot.reviews[0].dateKey.isNotBlank() shouldBe true
        }
    }
}
