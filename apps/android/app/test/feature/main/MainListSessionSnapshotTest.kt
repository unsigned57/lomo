package com.lomo.app.feature.main

import androidx.compose.runtime.saveable.SaverScope
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/*
 * Behavior Contract:
 * - Unit under test: MainListSessionSnapshot, mainListSessionSnapshotSaver, resolveMainListSessionRestore
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: persist the main list session (query, structural filter, viewport anchor) bound to
 *   one workspace identity and decide whether a restored snapshot applies, resets, or waits.
 *
 * Scenarios:
 * - Given a populated session snapshot, when it is saved and restored through the saver, then every
 *   session field including the workspace identity round-trips.
 * - Given a restored snapshot whose workspace matches the mounted one, when restore is resolved,
 *   then the saved query/filter/anchor apply as one session.
 * - Given a restored snapshot whose workspace differs from the mounted one, when restore is
 *   resolved, then the session resets instead of showing a stale page under a new workspace.
 * - Given the mount has not published a workspace location, when restore is resolved, then restore
 *   waits instead of guessing.
 *
 * Observable outcomes:
 * - Round-tripped MainListSessionSnapshot equality.
 * - MainListSessionRestoreAction subtype and payload.
 *
 * TDD proof:
 * - Fails while the main list session state is split across independently persisted fields that
 *   cannot represent workspace identity or a single ordered restore decision.
 *
 * Excludes:
 * - Compose effects, LazyListState scrolling, and process recreation.
 */
class MainListSessionSnapshotTest : FunSpec({
    test("saver round-trips a session bound to a workspace identity") {
        val snapshot =
            MainListSessionSnapshot(
                workspacePath = "/workspace/a",
                searchQuery = "design notes",
                filter =
                    MemoListFilter(
                        sortOption = MemoSortOption.UPDATED_TIME,
                        sortAscending = true,
                        startDate = LocalDate.of(2026, 3, 1),
                        endDate = LocalDate.of(2026, 3, 31),
                        hasTodo = true,
                        hasAttachment = false,
                        hasUrl = true,
                    ),
                anchorIndex = 42,
                anchorOffset = 7,
            )

        val saved =
            with(mainListSessionSnapshotSaver) { SaverScope { true }.save(snapshot) }!!
        mainListSessionSnapshotSaver.restore(saved) shouldBe snapshot
    }

    test("an unbound session round-trips without a workspace identity") {
        val saved =
            with(mainListSessionSnapshotSaver) {
                SaverScope { true }.save(MainListSessionSnapshot.Empty)
            }!!
        mainListSessionSnapshotSaver.restore(saved) shouldBe MainListSessionSnapshot.Empty
    }

    test("restore on the same workspace applies the saved query, filter, and anchor as one session") {
        val snapshot =
            MainListSessionSnapshot(
                workspacePath = "/workspace/a",
                searchQuery = "design",
                filter = MemoListFilter(hasTodo = true),
                anchorIndex = 5,
                anchorOffset = 3,
            )

        resolveMainListSessionRestore(snapshot, "/workspace/a") shouldBe
            MainListSessionRestoreAction.Apply(snapshot)
    }

    test("restore on a different workspace resets instead of showing the stale page") {
        val snapshot =
            MainListSessionSnapshot(
                workspacePath = "/workspace/a",
                searchQuery = "design",
                filter = MemoListFilter(hasTodo = true),
                anchorIndex = 5,
                anchorOffset = 3,
            )

        resolveMainListSessionRestore(snapshot, "/workspace/b") shouldBe
            MainListSessionRestoreAction.Reset
    }

    test("restore waits while the mount has no workspace location") {
        resolveMainListSessionRestore(
            MainListSessionSnapshot(
                workspacePath = "/workspace/a",
                searchQuery = "design",
                filter = MemoListFilter(),
                anchorIndex = 5,
                anchorOffset = 0,
            ),
            workspacePath = null,
        ) shouldBe MainListSessionRestoreAction.Wait
    }

    test("a session saved without workspace identity binds the mounted workspace as a reset") {
        resolveMainListSessionRestore(MainListSessionSnapshot.Empty, "/workspace/a") shouldBe
            MainListSessionRestoreAction.Reset
    }
})
