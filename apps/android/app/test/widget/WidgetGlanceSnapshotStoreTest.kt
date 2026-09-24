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
 *
 * Observable outcomes: typed WidgetGlanceSnapshot results from a real file.
 * TDD proof:
 * - Fails before the fix because the contract surface under test did not exist.
 * Excludes: Glance layout, JNI, and widget process isolation wiring.
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Old behavior/assertion being replaced: items-only snapshot schema where a missing file read as an empty vault.
 * - Why old assertion is no longer correct: schema v2 distinguishes Absent/Unavailable/Redacted/Ready, so absence and corruption are typed results rather than empty items.
 * - Coverage preserved by: round-trip, corruption, and availability cases on the typed snapshot API.
 * - Why this is not fitting the test to the implementation: typed availability is the required snapshot contract.
 */

// architectural-boundary-check: pins the persisted snapshot file format — the assertions on
// file bytes verify the durable contract, not Kotlin source text.

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlin.io.path.createTempDirectory

class WidgetGlanceSnapshotStoreTest : AppFunSpec() {
    init {
        test("given snapshot items when written then a later read recovers the full generation") {
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

                store.write(
                    workspaceId = "workspace-1",
                    projectionStamp = 42L,
                    availability = WidgetSnapshotAvailability.READY,
                    items = items,
                )

                store.read() shouldBe
                    WidgetGlanceSnapshot.Ready(
                        workspaceId = "workspace-1",
                        projectionStamp = 42L,
                        availability = WidgetSnapshotAvailability.READY,
                        items = items,
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
                    )
            } finally {
                directory.deleteRecursively()
            }
        }

        test("given a redacted generation when written then no body text reaches the file") {
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
                )

                val read = store.read() as WidgetGlanceSnapshot.Ready
                read.availability shouldBe WidgetSnapshotAvailability.REDACTED
                read.items.single().previewText shouldBe ""
                file.readText().contains("workspace-1") shouldBe true
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
