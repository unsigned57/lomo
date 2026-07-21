package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: RoomCutover outbox drain gate and cutover count/digest compare.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: refuse to discard a legacy Room DB while MemoFileOutbox rows remain, and only
 *   delete after drain plus matching rebuild count/digest evidence.
 *
 * Scenarios:
 * - Given no legacy Room DB file, when assertMemoOutboxDrainedOrAbsent runs, then success.
 * - Given a missing file, when deleteLegacyRoomDatabase runs, then it is a no-op success.
 * - Given a SQLite file with MemoFileOutbox rows, when cutover runs, then fail closed with a
 *   never-discard message and delete never runs.
 * - Given an empty or missing MemoFileOutbox table, when assert runs, then success.
 * - Given matching counts/digests after rebuild, when cutoverDeleteIfDrained runs, then delete runs.
 * - Given count or digest mismatch after rebuild, when cutoverDeleteIfDrained runs, then fail closed
 *   and never delete.
 *
 * Observable outcomes:
 * - Result success/failure messages, rebuild invocation, and on-disk DB presence after cutover.
 *
 * TDD proof:
 * - Fails before undrained-row and compare-mismatch paths refuse delete.
 *
 * Excludes:
 * - Android SQLiteDatabase.openDatabase on device (production reader path).
 */

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.sql.DriverManager

class RoomCutoverTest : FunSpec({
    test("missing legacy Room DB is treated as drained success") {
        val missing = File("/tmp/lomo-no-such-room-db-${System.nanoTime()}")
        RoomCutover.assertMemoOutboxDrainedOrAbsent(missing, jdbcOutboxReader()).isSuccess shouldBe true
    }

    test("deleteLegacyRoomDatabase is no-op when files are absent") {
        val missing = File("/tmp/lomo-no-such-room-db-delete-${System.nanoTime()}")
        RoomCutover.deleteLegacyRoomDatabase(missing)
        missing.exists() shouldBe false
    }

    test("pure policy fails closed when MemoFileOutbox has undrained rows") {
        val result =
            RoomCutover.assertOutboxDrained(
                RoomCutover.OutboxTableState(tablePresent = true, rowCount = 2L),
            )
        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldContain "never discard"
        result.exceptionOrNull()?.message shouldContain "2 undrained"
    }

    test("pure policy succeeds when outbox table is missing or empty") {
        RoomCutover
            .assertOutboxDrained(RoomCutover.OutboxTableState(tablePresent = false, rowCount = 0L))
            .isSuccess shouldBe true
        RoomCutover
            .assertOutboxDrained(RoomCutover.OutboxTableState(tablePresent = true, rowCount = 0L))
            .isSuccess shouldBe true
    }

    test("undrained MemoFileOutbox SQLite rows fail closed and never delete") {
        val dbFile = File.createTempFile("lomo-room-outbox-undrained-", ".db")
        dbFile.deleteOnExit()
        createLegacyRoomDbWithOutboxRows(dbFile, rowCount = 1)

        var rebuilt = false
        val result =
            RoomCutover.cutoverDeleteIfDrained(
                dbFile = dbFile,
                reader = jdbcOutboxReader(),
                rebuild = {
                    rebuilt = true
                    matchingCompareEvidence()
                },
            )

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldContain "never discard"
        rebuilt shouldBe false
        dbFile.exists() shouldBe true
    }

    test("empty MemoFileOutbox SQLite table allows cutover delete sequence") {
        val dbFile = File.createTempFile("lomo-room-outbox-empty-", ".db")
        dbFile.deleteOnExit()
        createLegacyRoomDbWithOutboxRows(dbFile, rowCount = 0)

        var rebuilt = false
        val result =
            RoomCutover.cutoverDeleteIfDrained(
                dbFile = dbFile,
                reader = jdbcOutboxReader(),
                rebuild = {
                    rebuilt = true
                    matchingCompareEvidence()
                },
            )

        result.isSuccess shouldBe true
        rebuilt shouldBe true
        dbFile.exists() shouldBe false
    }

    test("missing MemoFileOutbox table is treated as drained success") {
        val dbFile = File.createTempFile("lomo-room-no-outbox-table-", ".db")
        dbFile.deleteOnExit()
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE unrelated(id INTEGER PRIMARY KEY)")
            }
        }
        RoomCutover
            .assertMemoOutboxDrainedOrAbsent(dbFile, jdbcOutboxReader())
            .isSuccess shouldBe true
    }

    test("pure compare policy fails closed on count or digest mismatch") {
        RoomCutover
            .assertCutoverCompare(
                RoomCutover.CutoverCompareEvidence(
                    memoCount = 2,
                    fileCount = 3,
                    attachmentCount = 0,
                    workspaceDigest = "a",
                    storeDigest = "a",
                ),
            ).isFailure shouldBe true
        RoomCutover
            .assertCutoverCompare(
                RoomCutover.CutoverCompareEvidence(
                    memoCount = 2,
                    fileCount = 2,
                    attachmentCount = 1,
                    workspaceDigest = "ws",
                    storeDigest = "store",
                ),
            ).isFailure shouldBe true
        RoomCutover
            .assertCutoverCompare(matchingCompareEvidence())
            .isSuccess shouldBe true
    }

    test("digest mismatch after rebuild fails closed and never deletes") {
        val dbFile = File.createTempFile("lomo-room-outbox-mismatch-", ".db")
        dbFile.deleteOnExit()
        createLegacyRoomDbWithOutboxRows(dbFile, rowCount = 0)

        var rebuilt = false
        val result =
            RoomCutover.cutoverDeleteIfDrained(
                dbFile = dbFile,
                reader = jdbcOutboxReader(),
                rebuild = {
                    rebuilt = true
                    RoomCutover.CutoverCompareEvidence(
                        memoCount = 5,
                        fileCount = 5,
                        attachmentCount = 0,
                        workspaceDigest = "workspace-only",
                        storeDigest = "store-only",
                    )
                },
            )

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldContain "cutover compare failed"
        rebuilt shouldBe true
        dbFile.exists() shouldBe true
    }
})

private fun matchingCompareEvidence(): RoomCutover.CutoverCompareEvidence =
    RoomCutover.CutoverCompareEvidence(
        memoCount = 2,
        fileCount = 2,
        attachmentCount = 1,
        workspaceDigest = "same-digest",
        storeDigest = "same-digest",
    )

private fun jdbcOutboxReader(): RoomCutover.LegacyOutboxReader =
    RoomCutover.LegacyOutboxReader { dbFile ->
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val tablePresent =
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='MemoFileOutbox'",
                    ).use { rs -> rs.next() }
                }
            if (!tablePresent) {
                return@LegacyOutboxReader RoomCutover.OutboxTableState(
                    tablePresent = false,
                    rowCount = 0L,
                )
            }
            val count =
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM MemoFileOutbox").use { rs ->
                        check(rs.next()) { "unable to read MemoFileOutbox count" }
                        rs.getLong(1)
                    }
                }
            RoomCutover.OutboxTableState(tablePresent = true, rowCount = count)
        }
    }

/**
 * Builds a minimal legacy-shaped SQLite file with [MemoFileOutbox] rows for host fail-closed tests.
 * Schema is intentionally minimal; cutover only inspects table presence + COUNT(*).
 */
private fun createLegacyRoomDbWithOutboxRows(
    dbFile: File,
    rowCount: Int,
) {
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE MemoFileOutbox (
                    id INTEGER PRIMARY KEY NOT NULL,
                    payload TEXT NOT NULL
                )
                """.trimIndent(),
            )
            repeat(rowCount) { index ->
                st.executeUpdate(
                    "INSERT INTO MemoFileOutbox(id, payload) VALUES (${index + 1}, 'pending')",
                )
            }
        }
    }
}
