//! Schema constants and DDL for the rebuildable `SQLite` projection.

/// Durable `SQLite` schema version (`PRAGMA user_version` and owner identity).
pub const STORE_SCHEMA_VERSION: u32 = 13;

/// Tokenizer version embedded in FTS projections and `PageCursor`.
pub const TOKENIZER_VERSION: u32 = 1;

/// Default busy timeout applied on every open.
pub const BUSY_TIMEOUT_MS: u32 = 5_000;

/// Logical table names owned solely by this crate.
pub mod tables {
    pub const MEMO: &str = "memo";
    pub const TAG: &str = "tag";
    pub const MEMO_TAG: &str = "memo_tag";
    pub const ATTACHMENT_REF: &str = "attachment_ref";
    pub const MEMO_PIN: &str = "memo_pin";
    pub const MEMO_TRASH: &str = "memo_trash";
    pub const MEMO_FTS: &str = "memo_fts";
    pub const REVISION_INDEX: &str = "revision_index";
    pub const STATS: &str = "stats";
    pub const REBUILD_CHECKPOINT: &str = "rebuild_checkpoint";
    pub const ENGINE_DIAGNOSTIC: &str = "engine_diagnostic";
    pub const LOCAL_JOB: &str = "local_job";
    pub const STORE_META: &str = "store_meta";
    pub const SAF_MUTATION_OPERATION: &str = "saf_mutation_operation";
    pub const FILE_LISTING: &str = "file_listing";
    pub const HISTORY_ATTACHMENT_REF: &str = "history_attachment_ref";
    pub const PURGED_MEMO: &str = "purged_memo";
}

/// Full live schema DDL applied on create (and rebuild temp databases).
#[must_use]
#[expect(clippy::too_many_lines, reason = "DDL is a single schema document")]
pub fn schema_v1_ddl() -> String {
    format!(
        r"
CREATE TABLE {memo} (
    rowid INTEGER PRIMARY KEY NOT NULL,
    memo_id TEXT NOT NULL UNIQUE,
    source_path TEXT NOT NULL,
    source_start INTEGER NOT NULL DEFAULT 0,
    source_end INTEGER NOT NULL DEFAULT 0,
    file_fingerprint TEXT NOT NULL,
    has_todo INTEGER NOT NULL DEFAULT 0,
    has_url INTEGER NOT NULL DEFAULT 0,
    has_attachment INTEGER NOT NULL DEFAULT 0,
    -- Materialized lifecycle bits.  The companion tables retain timestamps/history, while these
    -- columns are the indexed read projection used by every bounded query.
    is_pinned INTEGER NOT NULL DEFAULT 0,
    is_trashed INTEGER NOT NULL DEFAULT 0,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    body_preview TEXT NOT NULL DEFAULT '',
    body TEXT,
    search_content TEXT NOT NULL DEFAULT '',
    word_count INTEGER NOT NULL DEFAULT 0,
    char_count INTEGER NOT NULL DEFAULT 0,
    reminders_json TEXT NOT NULL DEFAULT '[]',
    content_revision INTEGER NOT NULL DEFAULT 0,
    pending_operation_id TEXT
);

CREATE TABLE {tag} (
    id INTEGER PRIMARY KEY NOT NULL,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE {memo_tag} (
    memo_id TEXT NOT NULL REFERENCES {memo}(memo_id) ON DELETE CASCADE,
    tag_id INTEGER NOT NULL REFERENCES {tag}(id) ON DELETE CASCADE,
    PRIMARY KEY (memo_id, tag_id)
);

CREATE TABLE {attachment_ref} (
    id INTEGER PRIMARY KEY NOT NULL,
    memo_id TEXT NOT NULL REFERENCES {memo}(memo_id) ON DELETE CASCADE,
    relative_path TEXT NOT NULL,
    UNIQUE (memo_id, relative_path)
);

CREATE TABLE {memo_pin} (
    memo_id TEXT PRIMARY KEY NOT NULL REFERENCES {memo}(memo_id) ON DELETE CASCADE,
    pinned_at_ms INTEGER NOT NULL
);

CREATE TABLE {memo_trash} (
    memo_id TEXT PRIMARY KEY NOT NULL REFERENCES {memo}(memo_id) ON DELETE CASCADE,
    trashed_at_ms INTEGER NOT NULL,
    -- Canonical digest over the durable record's recoverable facts (body, claimed
    -- fingerprint, chronology, tags, body-extracted attachment keys, flags, reminders,
    -- timestamp). The reconcile gate compares it so a rewritten record can never
    -- certify as unchanged under a stable claimed fingerprint.
    record_digest TEXT NOT NULL DEFAULT ''
);

CREATE VIRTUAL TABLE {memo_fts} USING fts5(
    search_content,
    content='{memo}',
    content_rowid='rowid',
    tokenize='unicode61'
);

CREATE TABLE {revision_index} (
    memo_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    history_record_id TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    content TEXT,
    file_fingerprint TEXT,
    PRIMARY KEY (memo_id, history_record_id)
);

CREATE TABLE {stats} (
    key TEXT PRIMARY KEY NOT NULL,
    value_i64 INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE {rebuild_checkpoint} (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    phase TEXT NOT NULL,
    scanned INTEGER NOT NULL DEFAULT 0,
    total_hint INTEGER NOT NULL DEFAULT 0,
    payload_json TEXT NOT NULL DEFAULT '{{}}',
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE {engine_diagnostic} (
    id INTEGER PRIMARY KEY NOT NULL,
    code TEXT NOT NULL,
    detail TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL
);

CREATE TABLE {local_job} (
    job_id TEXT PRIMARY KEY NOT NULL,
    kind TEXT NOT NULL,
    state TEXT NOT NULL,
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE {store_meta} (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
);

CREATE TABLE {saf_mutation_operation} (
    operation_id TEXT PRIMARY KEY NOT NULL,
    mutation_digest TEXT NOT NULL,
    memo_id TEXT NOT NULL,
    core_revision INTEGER NOT NULL,
    event_sequence INTEGER NOT NULL,
    content_revision INTEGER NOT NULL,
    file_fingerprint TEXT NOT NULL,
    -- Present for batch permanent-delete operations; NULL means the operation predates the
    -- replay-facts contract and must not be replayed as if it had an empty reminder set.
    reminder_ids_json TEXT
);

-- Verified listing rows committed alongside the projection facts they describe. The next
-- reconcile diffs the current listing against this snapshot to scope work to changed paths.
CREATE TABLE {file_listing} (
    path TEXT PRIMARY KEY NOT NULL,
    digest TEXT NOT NULL
);

-- Attachment references materialized from durable history revisions. Orphan protection reads
-- these rows instead of re-parsing retained revision bodies on every observation.
CREATE TABLE {history_attachment_ref} (
    memo_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    relative_path TEXT NOT NULL,
    PRIMARY KEY (memo_id, revision, relative_path)
);

-- Durable purge tombstones admit trash-record suppression and owner resolution without decoding
-- every `.lomo/purged` record on each reconcile.
CREATE TABLE {purged_memo} (
    memo_id TEXT PRIMARY KEY NOT NULL
);

-- Query order and lifecycle predicates are served by the projection itself.  Keeping the
-- lifecycle bit first lets SQLite seek the active/pinned run before applying the keyset bound.
CREATE INDEX idx_memo_active_pinned_created
    ON {memo}(is_trashed, is_pinned DESC, created_at_ms DESC, memo_id DESC);
CREATE INDEX idx_memo_active_pinned_updated
    ON {memo}(is_trashed, is_pinned DESC, updated_at_ms DESC, created_at_ms DESC, memo_id DESC);
CREATE INDEX idx_memo_source_path ON {memo}(source_path, memo_id);
CREATE INDEX idx_memo_created ON {memo}(created_at_ms, memo_id);
CREATE INDEX idx_memo_updated ON {memo}(updated_at_ms, memo_id);
CREATE INDEX idx_revision_record ON {revision_index}(history_record_id);

INSERT INTO {stats}(key, value_i64) VALUES
    ('memo_count', 0),
    ('pinned_count', 0),
    ('trashed_count', 0),
    ('tag_count', 0);

INSERT INTO {store_meta}(key, value) VALUES
    ('tokenizer_version', '{tokenizer_version}'),
    ('high_water_revision', '0'),
    ('event_sequence', '0');
",
        memo = tables::MEMO,
        tag = tables::TAG,
        memo_tag = tables::MEMO_TAG,
        attachment_ref = tables::ATTACHMENT_REF,
        memo_pin = tables::MEMO_PIN,
        memo_trash = tables::MEMO_TRASH,
        memo_fts = tables::MEMO_FTS,
        revision_index = tables::REVISION_INDEX,
        stats = tables::STATS,
        rebuild_checkpoint = tables::REBUILD_CHECKPOINT,
        engine_diagnostic = tables::ENGINE_DIAGNOSTIC,
        local_job = tables::LOCAL_JOB,
        store_meta = tables::STORE_META,
        saf_mutation_operation = tables::SAF_MUTATION_OPERATION,
        file_listing = tables::FILE_LISTING,
        history_attachment_ref = tables::HISTORY_ATTACHMENT_REF,
        purged_memo = tables::PURGED_MEMO,
        tokenizer_version = TOKENIZER_VERSION,
    )
}

/// Additive v1 -> v2 migration for durable SAF projection mutation replay.
pub const MIGRATE_V1_TO_V2_DDL: &str = r"
CREATE TABLE saf_mutation_operation (
    operation_id TEXT PRIMARY KEY NOT NULL,
    mutation_digest TEXT NOT NULL,
    memo_id TEXT NOT NULL,
    core_revision INTEGER NOT NULL,
    event_sequence INTEGER NOT NULL,
    content_revision INTEGER NOT NULL,
    file_fingerprint TEXT NOT NULL
);
PRAGMA user_version = 2;
";

/// Additive v2 -> v3 migration for Rust-parsed reminder projection facts.
pub const MIGRATE_V2_TO_V3_DDL: &str = r"
ALTER TABLE memo ADD COLUMN reminders_json TEXT NOT NULL DEFAULT '[]';
PRAGMA user_version = 3;
";

/// Additive v3 -> v4 migration for complete app-private SAF memo snapshots.
///
/// Existing rows deliberately remain `NULL`: the old schema never persisted the source bytes, so
/// inventing an empty body would turn an incomplete projection into false readable state. Resetting
/// the projection revision to zero prevents read admission until the next verified SAF rebuild
/// fills every row and publishes one complete revision.
pub const MIGRATE_V3_TO_V4_DDL: &str = r"
ALTER TABLE memo ADD COLUMN body TEXT;
UPDATE store_meta SET value = '0' WHERE key = 'high_water_revision';
PRAGMA user_version = 4;
";

/// Additive v4 -> v5 migration for complete bounded history pages from the local projection.
/// Existing rows stay incomplete instead of inventing an empty revision body.
pub const MIGRATE_V4_TO_V5_DDL: &str = r"
ALTER TABLE revision_index ADD COLUMN content TEXT;
ALTER TABLE revision_index ADD COLUMN file_fingerprint TEXT;
PRAGMA user_version = 5;
";

/// Additive v5 -> v6 migration for pending create projections published before durable I/O.
///
/// `pending_operation_id` marks a memo row whose workspace bytes are not yet durable. It is owned
/// by the begin/complete/rollback lifecycle and is swept on open, so the column carries no durable
/// facts and needs no backfill.
pub const MIGRATE_V5_TO_V6_DDL: &str = r"
ALTER TABLE memo ADD COLUMN pending_operation_id TEXT;
PRAGMA user_version = 6;
";

/// Additive v6 -> v7 migration for materialized memo word/character statistics.
///
/// Existing rows cannot invent counts from bodies the row no longer holds, so the columns default
/// to zero and `high_water_revision` resets to force one verified rebuild that backfills every row
/// before the projection is admitted again (same contract as the v3 -> v4 body backfill).
pub const MIGRATE_V6_TO_V7_DDL: &str = r"
ALTER TABLE memo ADD COLUMN word_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE memo ADD COLUMN char_count INTEGER NOT NULL DEFAULT 0;
UPDATE store_meta SET value = '0' WHERE key = 'high_water_revision';
PRAGMA user_version = 7;
";

/// Additive v7 -> v8 migration for indexed lifecycle projection bits.
///
/// The pin/trash relation tables remain durable timestamp records; their membership is copied into
/// the memo row so query predicates and ordering never execute per-row correlated EXISTS clauses.
/// Resetting the high-water revision forces a verified rebuild if a partially migrated database is
/// encountered before the backfill completes.
pub const MIGRATE_V7_TO_V8_DDL: &str = r"
ALTER TABLE memo ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0;
ALTER TABLE memo ADD COLUMN is_trashed INTEGER NOT NULL DEFAULT 0;
UPDATE memo SET is_pinned = CASE WHEN EXISTS(SELECT 1 FROM memo_pin WHERE memo_pin.memo_id = memo.memo_id) THEN 1 ELSE 0 END;
UPDATE memo SET is_trashed = CASE WHEN EXISTS(SELECT 1 FROM memo_trash WHERE memo_trash.memo_id = memo.memo_id) THEN 1 ELSE 0 END;
CREATE INDEX idx_memo_active_pinned_created
    ON memo(is_trashed, is_pinned DESC, created_at_ms DESC, memo_id DESC);
CREATE INDEX idx_memo_active_pinned_updated
    ON memo(is_trashed, is_pinned DESC, updated_at_ms DESC, created_at_ms DESC, memo_id DESC);
CREATE INDEX idx_memo_source_path ON memo(source_path, memo_id);
CREATE INDEX idx_memo_created ON memo(created_at_ms, memo_id);
CREATE INDEX idx_memo_updated ON memo(updated_at_ms, memo_id);
PRAGMA user_version = 8;
";

/// Additive v8 -> v9 migration for durable batch-delete reminder replay facts.
///
/// Historical SAF operation rows remain `NULL`: their reminder set was never persisted, so a
/// replay cannot safely claim that no alarms need cancellation. New batch rows always write a
/// structured JSON array before the projection transaction commits.
pub const MIGRATE_V8_TO_V9_DDL: &str = r"
ALTER TABLE saf_mutation_operation ADD COLUMN reminder_ids_json TEXT;
PRAGMA user_version = 9;
";

/// History generations order revisions; content-addressed IDs distinguish concurrent branches.
pub const MIGRATE_V9_TO_V10_DDL: &str = r"
ALTER TABLE revision_index RENAME TO revision_index_v9;
CREATE TABLE revision_index (
    memo_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    history_record_id TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    content TEXT,
    file_fingerprint TEXT,
    PRIMARY KEY (memo_id, history_record_id)
);
INSERT INTO revision_index SELECT * FROM revision_index_v9;
DROP TABLE revision_index_v9;
PRAGMA user_version = 10;
";

/// Additive v10 -> v11 migration for reconcile-scoping derived tables.
///
/// `file_listing` snapshots start empty on upgraded databases, so the persisted listing digest
/// is discarded: the next reconcile must rescan once to repopulate the snapshot rather than
/// diffing an unchanged listing against a baseline that does not exist.
pub const MIGRATE_V10_TO_V11_DDL: &str = r"
CREATE TABLE file_listing (
    path TEXT PRIMARY KEY NOT NULL,
    digest TEXT NOT NULL
);
CREATE TABLE history_attachment_ref (
    memo_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    relative_path TEXT NOT NULL,
    PRIMARY KEY (memo_id, revision, relative_path)
);
CREATE TABLE purged_memo (
    memo_id TEXT PRIMARY KEY NOT NULL
);
CREATE INDEX idx_revision_record ON revision_index(history_record_id);
DELETE FROM store_meta WHERE key = 'workspace_listing_digest';
PRAGMA user_version = 11;
";

/// Additive v11 -> v12 migration for trash-record content attestation.
///
/// Existing `memo_trash` rows predate the digest column and keep `''`: the reconcile gate's
/// attestation compare can never match an empty digest, so the next gated pass materializes
/// once and repopulates the real digest — a certified stale membership row is impossible.
pub const MIGRATE_V11_TO_V12_DDL: &str = r"
ALTER TABLE memo_trash ADD COLUMN record_digest TEXT NOT NULL DEFAULT '';
PRAGMA user_version = 12;
";

/// Additive v12 -> v13 migration: re-verification of every committed state-head listing row.
///
/// `file_listing` rows under the state-heads directory are dropped — not just rows that can
/// be proven non-canonical, because the committed table stores path and digest only, never
/// the head body's claimed identity. Every head therefore re-diffs once and passes the
/// naming-authority gate the reconcile now enforces: a duplicate head an older build
/// absorbed into the baseline is evicted instead of staying invisible after the tip it
/// shadows moves. The persisted listing digest goes with them so the next reconcile cannot
/// short-circuit on a baseline whose rows are no longer there.
pub const MIGRATE_V12_TO_V13_DDL: &str = r"
DELETE FROM file_listing WHERE path LIKE '.lomo/state/v2/heads/%';
DELETE FROM store_meta WHERE key = 'workspace_listing_digest';
PRAGMA user_version = 13;
";
