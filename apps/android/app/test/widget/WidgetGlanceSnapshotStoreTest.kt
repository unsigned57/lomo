/*
 * Behavior Contract:
 * - Unit under test: WidgetGlanceSnapshotStore
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: Glance widgets read a persisted projection snapshot so a widget-only process
 *   never queries the store or opens the native engine.
 *
 * Scenarios:
 * - Given snapshot items, when they are written and read, then identity, timestamp, and preview
 *   round-trip.
 * - Given no snapshot file, when read, then the store returns an empty projection.
 * - Given a corrupt snapshot file, when read, then the store surfaces corruption for the host to render an unavailable state.
 *
 * Test Change Justification:
 * Reason category: error-state contract correction.
 * Old behavior/assertion being replaced: corrupt cache was indistinguishable from an empty workspace.
 * Why old assertion is no longer correct: cache failure must remain observable.
 * Coverage preserved by: valid and missing snapshot cases plus explicit corruption rejection.
 * Why this is not fitting the test to the implementation: invalid data cannot establish an empty workspace fact.
 *
 * Observable outcomes:
 * - decoded item lists from a real file.
 *
 * TDD proof:
 * - RED before the snapshot store exists because LomoWidget.getRecentMemos was the only widget
 *   read path.
 *
 * Excludes:
 * - Glance layout, JNI, and widget process isolation wiring.
 */
package com.lomo.app.widget

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlin.io.path.createTempDirectory

class WidgetGlanceSnapshotStoreTest : AppFunSpec() {
    init {
        test("given snapshot items when written then a later read recovers them") {
            val directory = createTempDirectory("widget-glance-snapshot").toFile()
            try {
                val store = WidgetGlanceSnapshotStore(directory.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME))
                val items =
                    listOf(
                        WidgetGlanceSnapshotItem(
                            id = "memo-1",
                            timestampMillis = 1_000L,
                            previewText = "first",
                        ),
                        WidgetGlanceSnapshotItem(
                            id = "memo-2",
                            timestampMillis = 2_000L,
                            previewText = "second with unicode 笔记",
                        ),
                    )

                store.write(items)

                store.read() shouldBe items
            } finally {
                directory.deleteRecursively()
            }
        }

        test("given no snapshot file when read then the projection is empty") {
            val directory = createTempDirectory("widget-glance-missing").toFile()
            try {
                WidgetGlanceSnapshotStore(directory.resolve(WIDGET_GLANCE_SNAPSHOT_FILE_NAME)).read() shouldBe emptyList()
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
    }
}
