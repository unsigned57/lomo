//! Schema constants and DDL for the rebuildable `SQLite` projection.

/// Durable `SQLite` schema version (`PRAGMA user_version` and owner identity).
pub const STORE_SCHEMA_VERSION: u32 = 3;

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
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    body_preview TEXT NOT NULL DEFAULT '',
    search_content TEXT NOT NULL DEFAULT '',
    reminders_json TEXT NOT NULL DEFAULT '[]',
    content_revision INTEGER NOT NULL DEFAULT 0
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
    trashed_at_ms INTEGER NOT NULL
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
    PRIMARY KEY (memo_id, revision)
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
    file_fingerprint TEXT NOT NULL
);

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
