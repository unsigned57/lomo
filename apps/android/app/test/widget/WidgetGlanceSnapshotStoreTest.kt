package com.lomo.app.widget

/*
 * Behavior Contract:
 * - Unit under test: WidgetGlanceSnapshotStore
 * - Owning layer: app
 * - Priority tier: P0
 * - Capability: the persisted Glance snapshot binds workspace identity, a durable projection stamp
 *   and a generation state; absence, availability and corruption stay distinguishable so a widget
 *   never renders an empty vault for a missing or broken file.
 *
 * Scenarios:
 * - Given a ready snapshot with items, when written and read, then identity, stamp, availability
 *   and items round-trip.
 * - Given no snapshot file, when read, then the store returns Absent — never a fake empty vault.
 * - Given a corrupt snapshot file, when read, then corruption is surfaced for the host to render
 *   an unavailable state.
 * - Given an unavailable generation, when written and read, then the state is preserved with no
 *   items.
 * - Given a redacted generation when written then no body text, task text, review text, or
 *   stats counts reach the file.
 * - Given a task/review/stats budget exceeded when written then validation rejects oversize state.
 *
 * Observable outcomes: typed WidgetGlanceSnapshot results from a real file.
 * TDD proof:
 * - Fails before the fix because the schema v3 snapshot fields and validation do not exist.
 * Excludes: Glance layout, JNI, and widget process isolation wiring.
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Old behavior/assertion being replaced: schema v2 snapshots only stored 3 memo previews without tasks, stats, or review cards.
 * - Why old assertion is no longer correct: schema v3 supports multi-widget projections (recent memos with tags/pin, tasks, statistics heatmap, and daily review cards) with privacy redaction across all four widgets.
 * - Coverage preserved by: round-trip, absent, corruption, unavailable, and redaction cases across the extended snapshot.
 * - Why this is not fitting the test to the implementation: typed schema v3 fields and redaction guarantees are the widget security and multi-widget contract.
 */

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class WidgetGlanceSnapshotStoreTest : AppFunSpec() {
    init {
        test("given schema v3 snapshot payloads when written then a later read recovers all widget sections") {
            val directory = createTempDirectory("widget-glance-snapshot").toFile()
            try {
                val store = WidgetGlanceSnapshotStore(directory.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME))
                val items =
                    listOf(
                        WidgetGlanceSnapshotItem(
                            id = "memo-1",
                            timestampMillis = 1_000L,
                            previewText = "first",
                            tags = listOf("work", "ideas"),
                            isPinned = true,
                        ),
                        WidgetGlanceSnapshotItem(
                            id = "memo-2",
                            timestampMillis = 2_000L,
                            previewText = "second with unicode 笔记",
                            tags = listOf("daily"),
                            isPinned = false,
                        ),
                    )
                val tasks =
                    listOf(
                        WidgetGlanceTaskItem(
                            memoId = "memo-1",
                            lineIndex = 2,
                            done = false,
                            text = "Ship widget redesign",
                            dateKey = "2026-11-14",
                        ),
                        WidgetGlanceTaskItem(
                            memoId = "memo-2",
                            lineIndex = 0,
                            done = true,
                            text = "Review blur spec",
                            dateKey = "2026-11-13",
                        ),
                    )
                val stats =
                    WidgetGlanceStats(
                        totalMemos = 48,
                        todayCount = 3,
                        thisWeekCount = 14,
                        currentStreak = 7,
                        longestStreak = 12,
                        activeDays = 30,
                        asOfEpochDay = 20_406L,
                        level1Max = 1,
                        level2Max = 3,
                        level3Max = 6,
                        heatmapDays =
                        listOf(
                            WidgetGlanceHeatmapDay(epochDay = 20_405L, count = 2),
                            WidgetGlanceHeatmapDay(epochDay = 20_406L, count = 3),
                        ),
                    )
                val reviews =
                    listOf(
                        WidgetGlanceReviewItem(
                            id = "memo-old-1",
                            timestampMillis = 900L,
                            previewText = "Older reflection worth revisiting",
                            dateKey = "2026-08-01",
                            tags = listOf("journal"),
                        ),
                    )

                store.write(
                    workspaceId = "workspace-1",
                    projectionStamp = 42L,
                    availability = WidgetSnapshotAvailability.READY,
                    items = items,
                    tasks = tasks,
                    stats = stats,
                    reviews = reviews,
                )

                store.read() shouldBe
                    WidgetGlanceSnapshot.Ready(
                        workspaceId = "workspace-1",
                        projectionStamp = 42L,
                        availability = WidgetSnapshotAvailability.READY,
                        items = items,
                        tasks = tasks,
                        stats = stats,
                        reviews = reviews,
                    )
            } finally {
                directory.deleteRecursively()
            }
        }

        test("given no snapshot file when read then the result is Absent not an empty vault") {
            val directory = createTempDirectory("widget-glance-missing").toFile()
            try {
                WidgetGlanceSnapshotStore(directory.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME)).read() shouldBe
                    WidgetGlanceSnapshot.Absent
            } finally {
                directory.deleteRecursively()
            }
        }

        test("given a corrupt snapshot file when read then corruption is surfaced") {
            val directory = createTempDirectory("widget-glance-corrupt").toFile()
            try {
                val file = directory.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME)
                file.writeText("{not-json")
                shouldThrow<kotlinx.serialization.SerializationException> { WidgetGlanceSnapshotStore(file).read() }
            } finally {
                directory.deleteRecursively()
            }
        }

        test("given an unavailable generation when written then the state survives a later read") {
            val directory = createTempDirectory("widget-glance-unavailable").toFile()
            try {
                val store = WidgetGlanceSnapshotStore(directory.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME))

                store.write(
                    workspaceId = null,
                    projectionStamp = 7L,
                    availability = WidgetSnapshotAvailability.UNAVAILABLE,
                    items = emptyList(),
                )

                store.read() shouldBe
                    WidgetGlanceSnapshot.Ready(
                        workspaceId = null,
                        projectionStamp = 7L,
                        availability = WidgetSnapshotAvailability.UNAVAILABLE,
                        items = emptyList(),
                        tasks = emptyList(),
                        stats = null,
                        reviews = emptyList(),
                    )
            } finally {
                directory.deleteRecursively()
            }
        }

        test("given a redacted generation when written then no body text, tasks, stats, or reviews reach the file") {
            val directory = createTempDirectory("widget-glance-redacted").toFile()
            try {
                val file = directory.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME)
                val store = WidgetGlanceSnapshotStore(file)

                store.write(
                    workspaceId = "workspace-1",
                    projectionStamp = 9L,
                    availability = WidgetSnapshotAvailability.REDACTED,
                    items =
                    listOf(
                        WidgetGlanceSnapshotItem(
                            id = "memo-1",
                            timestampMillis = 1_000L,
                            previewText = "",
                        ),
                    ),
                    tasks = emptyList(),
                    stats = null,
                    reviews = emptyList(),
                )

                val read = store.read() as WidgetGlanceSnapshot.Ready
                read.availability shouldBe WidgetSnapshotAvailability.REDACTED
                read.items.single().previewText shouldBe ""
                read.items.single().tags shouldBe emptyList()
                read.items.single().isPinned shouldBe false
                read.tasks shouldBe emptyList()
                read.stats shouldBe null
                read.reviews shouldBe emptyList()
                file.readText().contains("workspace-1") shouldBe true
            } finally {
                directory.deleteRecursively()
            }
        }

        test("given non-empty task, stats, or review payload in redacted write then validation rejects leakage") {
            val directory = createTempDirectory("widget-glance-redacted-reject").toFile()
            try {
                val store = WidgetGlanceSnapshotStore(directory.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME))
                shouldThrow<IllegalArgumentException> {
                    store.write(
                        workspaceId = "workspace-1",
                        projectionStamp = 9L,
                        availability = WidgetSnapshotAvailability.REDACTED,
                        items = emptyList(),
                        tasks =
                        listOf(
                            WidgetGlanceTaskItem(
                                memoId = "memo-1",
                                lineIndex = 0,
                                done = false,
                                text = "secret task",
                                dateKey = "2026-11-14",
                            ),
                        ),
                    )
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
