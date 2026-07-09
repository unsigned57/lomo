package com.lomo.data.local

import androidx.sqlite.SQLiteConnection

/**
 * Memo identity changed from a content-derived hash id (`dateKey_timePart_contentHash`) to a
 * stable, content-independent positional id (`dateKey_timePart_ordinal`). Existing rows still
 * carry the legacy hash ids, so this migration clears the file-projection caches and lets the
 * next reconcile re-import every memo from the source `.md` files (the source of truth) under
 * the new id scheme.
 *
 * A clean re-import is used instead of an in-place re-key because databases afflicted by the
 * old scheme can already contain duplicate `(date, timestamp)` rows (the "edited memo split into
 * two" bug), which would collide on the new id and crash the migration.
 *
 * Local-only state keyed by the legacy memo id (pins, version history) cannot be remapped once
 * the legacy ids disappear on re-import, so it is cleared too. The notes themselves live in the
 * source files and are never touched.
 */
internal fun resetMemoProjectionForPositionalIds(db: SQLiteConnection) {
    // File-projection caches: the next reconcile rebuilds these from the source files.
    db.execSQL("DELETE FROM `MemoTagCrossRef`")
    db.execSQL("DELETE FROM `MemoImageAttachment`")
    db.execSQL("DELETE FROM `$TRASH_MEMO_TABLE`")
    db.execSQL("DELETE FROM `$MEMO_TABLE`")
    // Force a full reconcile: every shard is treated as new on the next refresh.
    db.execSQL("DELETE FROM `$LOCAL_FILE_STATE_TABLE`")
    // In-flight file mutations reference legacy ids/content; drop them.
    db.execSQL("DELETE FROM `MemoFileOutbox`")
    // Local-only state keyed by the legacy memo id (cannot be remapped).
    db.execSQL("DELETE FROM `MemoPin`")
    db.execSQL("DELETE FROM `memo_revision_asset`")
    db.execSQL("DELETE FROM `memo_revision`")
    db.execSQL("DELETE FROM `memo_version_blob`")
    db.execSQL("DELETE FROM `version_commit`")
    // Keep the external-content FTS index consistent with the now-empty memo table.
    rebuildMemoFtsExternalContentInfrastructure(db)
}
