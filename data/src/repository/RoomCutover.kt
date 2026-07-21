package com.lomo.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * One-shot P3-10 Room → Rust cutover helpers.
 *
 * Sequence: freeze writes (caller) → [assertMemoOutboxDrainedOrAbsent] → re-scan/rebuild via store →
 * [assertCutoverCompare] (memo/file/attachment counts + digests) → delete legacy Room file.
 * Undrained outbox fails closed (never discarded). Compare mismatch fails closed (never delete).
 *
 * Kotlin never opens SQLite for ongoing production ownership; this uses [SQLiteDatabase] only to
 * inspect the legacy Room file for outbox rows before deleting it. Host tests inject a JDBC reader
 * via [LegacyOutboxReader] so the fail-closed gate is proven without Android stubs.
 */
object RoomCutover {
    const val LEGACY_ROOM_DATABASE_NAME: String = "lomo.db"

    /** Observable residual state of the legacy `MemoFileOutbox` table. */
    data class OutboxTableState(
        val tablePresent: Boolean,
        val rowCount: Long,
    )

    /**
     * Workspace scan vs store projection compare evidence returned by rebuild / host fixtures.
     * Digests are aggregate memo_id+fingerprint hashes; counts must agree before switch.
     */
    data class CutoverCompareEvidence(
        val memoCount: Long,
        val fileCount: Long,
        val attachmentCount: Long,
        val workspaceDigest: String,
        val storeDigest: String,
    )

    /**
     * Reads [OutboxTableState] from a legacy SQLite file.
     * Production uses [AndroidLegacyOutboxReader]; host tests inject JDBC.
     */
    fun interface LegacyOutboxReader {
        fun read(dbFile: File): OutboxTableState
    }

    /** Production reader: one-shot Android platform SQLite open (not an ongoing Room owner). */
    val AndroidLegacyOutboxReader: LegacyOutboxReader =
        LegacyOutboxReader { dbFile ->
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val tableExists =
                    db
                        .rawQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                            arrayOf("MemoFileOutbox"),
                        ).use { cursor -> cursor.moveToFirst() }
                if (!tableExists) {
                    return@LegacyOutboxReader OutboxTableState(tablePresent = false, rowCount = 0L)
                }
                db.rawQuery("SELECT COUNT(*) FROM MemoFileOutbox", null).use { cursor ->
                    check(cursor.moveToFirst()) { "unable to read MemoFileOutbox count" }
                    OutboxTableState(tablePresent = true, rowCount = cursor.getLong(0))
                }
            }
        }

    fun legacyRoomDatabaseFile(context: Context): File =
        context.getDatabasePath(LEGACY_ROOM_DATABASE_NAME)

    fun assertMemoOutboxDrainedOrAbsent(context: Context): Result<Unit> =
        assertMemoOutboxDrainedOrAbsent(legacyRoomDatabaseFile(context))

    /**
     * Fails closed when the legacy Room DB still has memo file outbox rows.
     * Returns success when no legacy DB exists or outbox table is empty/missing.
     */
    fun assertMemoOutboxDrainedOrAbsent(
        dbFile: File,
        reader: LegacyOutboxReader = AndroidLegacyOutboxReader,
    ): Result<Unit> {
        if (!dbFile.exists()) {
            return Result.success(Unit)
        }
        return runCatching {
            assertOutboxDrained(reader.read(dbFile)).getOrThrow()
        }
    }

    /**
     * Pure fail-closed policy for [OutboxTableState].
     * Missing table → success; non-zero rows → failure with never-discard message.
     */
    fun assertOutboxDrained(state: OutboxTableState): Result<Unit> =
        runCatching {
            if (!state.tablePresent) {
                return@runCatching
            }
            check(state.rowCount == 0L) {
                "Room cutover blocked: MemoFileOutbox still has ${state.rowCount} undrained row(s); " +
                    "drain the outbox before switching (fail closed, never discard)"
            }
        }

    /**
     * Fail-closed count/digest compare before discarding legacy Room.
     * Mismatch → failure (never delete / never claim success).
     */
    fun assertCutoverCompare(evidence: CutoverCompareEvidence): Result<Unit> =
        runCatching {
            check(evidence.memoCount == evidence.fileCount) {
                "cutover compare failed: memoCount=${evidence.memoCount} != fileCount=${evidence.fileCount}"
            }
            check(evidence.workspaceDigest.isNotBlank() && evidence.storeDigest.isNotBlank()) {
                "cutover compare failed: blank workspace/store digest"
            }
            check(evidence.workspaceDigest == evidence.storeDigest) {
                "cutover compare failed: workspace digest != store digest"
            }
            check(evidence.attachmentCount >= 0L) {
                "cutover compare failed: negative attachmentCount=${evidence.attachmentCount}"
            }
            check(evidence.memoCount >= 0L) {
                "cutover compare failed: negative memoCount=${evidence.memoCount}"
            }
        }

    /**
     * Fail-closed cutover sequence used by hosts/tests to prove undrained rows and compare
     * mismatch never reach delete. Production [com.lomo.data.local.StoreDatabaseInitializer]
     * follows the same order with rebuild + compare evidence.
     */
    fun cutoverDeleteIfDrained(
        dbFile: File,
        reader: LegacyOutboxReader,
        rebuild: () -> CutoverCompareEvidence,
    ): Result<Unit> =
        runCatching {
            assertMemoOutboxDrainedOrAbsent(dbFile, reader).getOrThrow()
            val evidence = rebuild()
            assertCutoverCompare(evidence).getOrThrow()
            deleteLegacyRoomDatabase(dbFile)
        }

    /** Deletes legacy Room database and sidecars after successful outbox drain + rebuild. */
    fun deleteLegacyRoomDatabase(context: Context) {
        deleteLegacyRoomDatabase(legacyRoomDatabaseFile(context))
    }

    fun deleteLegacyRoomDatabase(base: File) {
        listOf(base, File(base.path + "-wal"), File(base.path + "-shm"), File(base.path + "-journal"))
            .forEach { file ->
                if (file.exists()) {
                    check(file.delete()) { "failed to delete legacy Room artifact ${file.path}" }
                }
            }
    }
}
