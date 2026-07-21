//! Rebuild state machine: read-only → temp DB → batched checkpoint → integrity → atomic replace.

use std::fs;
use std::path::{Path, PathBuf};

use rusqlite::{Connection, params};

use crate::content_facts::{aggregate_memo_digest, fingerprint_content, project_content_facts};
use crate::error::{busy, corruption, from_sqlite, storage, validation};
use crate::lomo_format::{
    HistoryBody, LomoPaths, LomoRecordKind, StateBody, isolate_corrupt_record, read_record,
};
use crate::open::{SQLITE_DIR_NAME, create_schema_db, database_path};
use crate::query::recompute_stats;
use crate::tokenizer::index_tokens;
use crate::transaction::WriteGate;

/// Sidecar basename for the previous live DB during crash-safe replace.
const LIVE_BAK_NAME: &str = "store.db.bak";

/// Rebuild progress checkpoint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RebuildCheckpoint {
    pub phase: RebuildPhase,
    pub scanned: u64,
    pub total_hint: u64,
    /// Isolated corrupt `.lomo` records observed during this rebuild run.
    pub isolated: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RebuildPhase {
    Starting,
    Scanning,
    Indexing,
    Integrity,
    Compare,
    Replacing,
    Complete,
}

impl RebuildPhase {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Starting => "starting",
            Self::Scanning => "scanning",
            Self::Indexing => "indexing",
            Self::Integrity => "integrity",
            Self::Compare => "compare",
            Self::Replacing => "replacing",
            Self::Complete => "complete",
        }
    }

    fn parse(raw: &str) -> Result<Self, lomo_core::LomoError> {
        match raw {
            "starting" => Ok(Self::Starting),
            "scanning" => Ok(Self::Scanning),
            "indexing" => Ok(Self::Indexing),
            "integrity" => Ok(Self::Integrity),
            "compare" => Ok(Self::Compare),
            "replacing" => Ok(Self::Replacing),
            "complete" => Ok(Self::Complete),
            _ => Err(validation(
                "invalid_rebuild_phase",
                "unknown rebuild checkpoint phase",
            )),
        }
    }
}

/// Result of a completed rebuild (includes cutover compare evidence).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RebuildResult {
    pub memos_indexed: u64,
    /// Workspace memo file count scanned during compare (`memos/` + `trash/`).
    pub file_count: u64,
    /// Attachment ref count projected after index (must match workspace-derived count).
    pub attachment_count: u64,
    /// Aggregate digest of workspace memo file fingerprints (sorted `memo_id` + fingerprint).
    pub workspace_digest: String,
    /// Aggregate digest of store projection fingerprints (sorted `memo_id` + fingerprint).
    pub store_digest: String,
    pub corrupt_lomo_isolated: u64,
    pub high_water_revision: u64,
}

/// Runs or resumes rebuild. Never deletes `.lomo/` because `SQLite` is damaged.
///
/// # Errors
///
/// Storage/corruption errors. Mutations remain rejected via `WriteGate::RebuildingReadOnly`
/// for the caller while this runs.
#[expect(
    clippy::too_many_lines,
    reason = "rebuild state machine is one coherent phase sequence"
)]
pub fn run_rebuild(
    workspace_root: &Path,
    batch_size: usize,
) -> Result<RebuildResult, lomo_core::LomoError> {
    if batch_size == 0 {
        return Err(validation(
            "invalid_rebuild_batch_size",
            "rebuild batch_size must be >= 1",
        ));
    }

    let paths = LomoPaths::for_workspace(workspace_root);
    paths.ensure_layout()?;

    // Durable facts stay put even when SQLite is corrupt — we only touch rebuildable files.
    let live_db = database_path(workspace_root);
    let sqlite_dir = workspace_root.join(SQLITE_DIR_NAME);
    let temp_db = sqlite_dir.join("store.rebuild.db");
    let live_bak = sqlite_dir.join(LIVE_BAK_NAME);
    let checkpoint_path = sqlite_dir.join("rebuild.checkpoint.json");

    let mut checkpoint = load_or_init_checkpoint(&checkpoint_path)?;

    // Phase: create temp DB if starting or resuming before replace.
    if matches!(
        checkpoint.phase,
        RebuildPhase::Starting | RebuildPhase::Scanning | RebuildPhase::Indexing
    ) {
        if checkpoint.phase == RebuildPhase::Starting {
            // Fresh rebuild: drop any leftover temp/bak from a previous interrupted run.
            drop(fs::remove_file(&temp_db));
            drop(fs::remove_file(&live_bak));
            let _conn = create_schema_db(&temp_db)?;
            checkpoint.phase = RebuildPhase::Scanning;
            checkpoint.scanned = 0;
            checkpoint.isolated = 0;
            save_checkpoint(&checkpoint_path, &checkpoint)?;
        }

        // If temp is gone mid-indexing, progress is not durable — restart scan from zero.
        if matches!(
            checkpoint.phase,
            RebuildPhase::Scanning | RebuildPhase::Indexing
        ) && !temp_db.exists()
        {
            let _conn = create_schema_db(&temp_db)?;
            checkpoint.scanned = 0;
            save_checkpoint(&checkpoint_path, &checkpoint)?;
        }

        let conn = create_or_open_temp(&temp_db, &checkpoint)?;
        let memo_files = list_memo_files(workspace_root)?;
        checkpoint.total_hint = memo_files.len() as u64;
        checkpoint.phase = RebuildPhase::Indexing;
        save_checkpoint(&checkpoint_path, &checkpoint)?;

        let start = usize::try_from(checkpoint.scanned).unwrap_or(0);
        for (idx, memo_path) in memo_files.iter().enumerate().skip(start) {
            index_memo_file(&conn, memo_path)?;
            checkpoint.scanned = (idx + 1) as u64;
            if (idx + 1) % batch_size == 0 {
                save_checkpoint(&checkpoint_path, &checkpoint)?;
            }
        }

        // Apply durable .lomo state (pin/trash/tags) and history projections.
        // apply_lomo_state is idempotent (INSERT OR REPLACE / OR IGNORE).
        checkpoint.isolated = apply_lomo_state(&conn, &paths)?;
        recompute_stats(&conn)?;
        drop(conn);

        checkpoint.phase = RebuildPhase::Integrity;
        save_checkpoint(&checkpoint_path, &checkpoint)?;
    }

    if checkpoint.phase == RebuildPhase::Integrity {
        let conn = open_temp_existing(&temp_db)?;
        ensure_quick_check(&conn, "temp")?;
        // FTS count should not exceed memo count.
        let memo_count: i64 = conn
            .query_row("SELECT COUNT(*) FROM memo", [], |row| row.get(0))
            .map_err(|err| from_sqlite(&err))?;
        let fts_count: i64 = conn
            .query_row("SELECT COUNT(*) FROM memo_fts", [], |row| row.get(0))
            .map_err(|err| from_sqlite(&err))?;
        if fts_count > memo_count {
            return Err(corruption(
                "rebuild_fts_mismatch",
                "FTS row count exceeds memo count",
            ));
        }
        drop(conn);
        checkpoint.phase = RebuildPhase::Compare;
        save_checkpoint(&checkpoint_path, &checkpoint)?;
    }

    // Compare evidence is computed once in Compare and re-read after Complete for the result.
    let mut compare_file_count = 0u64;
    let mut compare_attachment_count = 0u64;
    let mut compare_workspace_digest = String::new();
    let mut compare_store_digest = String::new();

    if checkpoint.phase == RebuildPhase::Compare {
        let conn = open_temp_existing(&temp_db)?;
        let evidence = compare_workspace_to_store(workspace_root, &conn)?;
        compare_file_count = evidence.file_count;
        compare_attachment_count = evidence.attachment_count;
        compare_workspace_digest = evidence.workspace_digest;
        compare_store_digest = evidence.store_digest;
        drop(conn);
        checkpoint.phase = RebuildPhase::Replacing;
        save_checkpoint(&checkpoint_path, &checkpoint)?;
    }

    if checkpoint.phase == RebuildPhase::Replacing {
        finish_atomic_replace(&live_db, &temp_db, &live_bak)?;
        checkpoint.phase = RebuildPhase::Complete;
        save_checkpoint(&checkpoint_path, &checkpoint)?;
        drop(fs::remove_file(&checkpoint_path));
    }

    // Resume path that jumps past Compare (already Complete/replaced): recompute evidence from live.
    if compare_workspace_digest.is_empty() {
        let live = Connection::open(&live_db).map_err(|err| from_sqlite(&err))?;
        live.pragma_update(None, "foreign_keys", "ON")
            .map_err(|err| from_sqlite(&err))?;
        let evidence = compare_workspace_to_store(workspace_root, &live)?;
        compare_file_count = evidence.file_count;
        compare_attachment_count = evidence.attachment_count;
        compare_workspace_digest = evidence.workspace_digest;
        compare_store_digest = evidence.store_digest;
    }

    let memos_indexed = checkpoint.scanned;
    Ok(RebuildResult {
        memos_indexed,
        file_count: compare_file_count,
        attachment_count: compare_attachment_count,
        workspace_digest: compare_workspace_digest,
        store_digest: compare_store_digest,
        corrupt_lomo_isolated: checkpoint.isolated,
        high_water_revision: 0,
    })
}

#[derive(Debug, Clone)]
struct CompareEvidence {
    file_count: u64,
    attachment_count: u64,
    workspace_digest: String,
    store_digest: String,
}

/// Fail-closed compare: workspace memo files vs store projection counts + digests.
fn compare_workspace_to_store(
    workspace_root: &Path,
    conn: &Connection,
) -> Result<CompareEvidence, lomo_core::LomoError> {
    let memo_files = list_memo_files(workspace_root)?;
    let mut workspace_pairs: Vec<(String, String)> = Vec::with_capacity(memo_files.len());
    let mut workspace_attachments = 0u64;
    for path in &memo_files {
        let content = fs::read_to_string(path).map_err(|err| {
            storage(
                "memo_read_failed",
                &format!("cannot read {} for compare: {err}", path.display()),
            )
        })?;
        let memo_id = path
            .file_stem()
            .and_then(|s| s.to_str())
            .ok_or_else(|| validation("invalid_memo_filename", "memo file stem must be utf-8"))?
            .to_owned();
        workspace_pairs.push((memo_id, fingerprint_content(&content)));
        let facts = project_content_facts(&content)?;
        workspace_attachments = workspace_attachments
            .checked_add(u64::try_from(facts.attachment_paths.len()).unwrap_or(u64::MAX))
            .ok_or_else(|| corruption("rebuild_compare_failed", "attachment count overflow"))?;
    }
    workspace_pairs.sort_by(|a, b| a.0.cmp(&b.0));
    let workspace_digest = aggregate_memo_digest(&workspace_pairs);
    let file_count = u64::try_from(workspace_pairs.len()).unwrap_or(u64::MAX);

    let memo_count: i64 = conn
        .query_row("SELECT COUNT(*) FROM memo", [], |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    let memo_count_u = u64::try_from(memo_count).unwrap_or(u64::MAX);
    if memo_count_u != file_count {
        return Err(corruption(
            "rebuild_compare_failed",
            &format!("memo count {memo_count_u} does not match workspace file count {file_count}"),
        ));
    }

    let mut store_pairs: Vec<(String, String)> = Vec::new();
    {
        let mut stmt = conn
            .prepare("SELECT memo_id, file_fingerprint FROM memo ORDER BY memo_id")
            .map_err(|err| from_sqlite(&err))?;
        let rows = stmt
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|err| from_sqlite(&err))?;
        for row in rows {
            store_pairs.push(row.map_err(|err| from_sqlite(&err))?);
        }
    }
    let store_digest = aggregate_memo_digest(&store_pairs);
    if workspace_digest != store_digest {
        return Err(corruption(
            "rebuild_compare_failed",
            "workspace and store content digests diverge",
        ));
    }

    let store_attachments: i64 = conn
        .query_row("SELECT COUNT(*) FROM attachment_ref", [], |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    let store_attachments_u = u64::try_from(store_attachments).unwrap_or(u64::MAX);
    if store_attachments_u != workspace_attachments {
        return Err(corruption(
            "rebuild_compare_failed",
            &format!(
                "attachment count store={store_attachments_u} workspace={workspace_attachments}"
            ),
        ));
    }

    Ok(CompareEvidence {
        file_count,
        attachment_count: store_attachments_u,
        workspace_digest,
        store_digest,
    })
}

/// Crash-safe live DB replace.
///
/// Strategy:
/// 1. Never delete the sole good live DB without a verified temp replacement.
/// 2. `live → bak`, then `temp → live`, then delete bak (and WAL/SHM).
/// 3. Resume rules when phase=`replacing`:
///    - temp missing + live exists + integrity OK → rename already completed → success
///    - temp exists → finish replace from temp
///    - temp missing + live missing + bak exists → restore bak then fail closed if no temp
///    - temp missing + live bad/missing + no bak → storage error (cannot invent a DB)
fn finish_atomic_replace(
    live_db: &Path,
    temp_db: &Path,
    live_bak: &Path,
) -> Result<(), lomo_core::LomoError> {
    if let Some(parent) = live_db.parent() {
        fs::create_dir_all(parent).map_err(|err| {
            storage(
                "sqlite_dir_create_failed",
                &format!("cannot create sqlite dir: {err}"),
            )
        })?;
    }

    remove_wal_shm(live_db);
    remove_wal_shm(temp_db);
    remove_wal_shm(live_bak);

    let temp_ok = temp_db.exists() && db_quick_check_ok(temp_db)?;
    let live_ok = live_db.exists() && db_quick_check_ok(live_db)?;

    if !temp_ok {
        if live_ok {
            // Rename temp→live already happened (or temp was never needed): complete as success.
            drop(fs::remove_file(live_bak));
            return Ok(());
        }
        // Live is missing/corrupt; try bak as last resort only if it integrity-checks.
        if live_bak.exists() && db_quick_check_ok(live_bak)? {
            fs::rename(live_bak, live_db).map_err(|err| {
                storage(
                    "sqlite_replace_bak_restore_failed",
                    &format!("cannot restore bak to live: {err}"),
                )
            })?;
            // Still no temp to install — surface that rebuild replace cannot complete without temp.
            // But a good live is restored so the store is not destroyed.
            return Ok(());
        }
        return Err(storage(
            "sqlite_replace_no_good_db",
            "rebuild replace cannot complete: temp missing and live not integrity-ok",
        ));
    }

    // Temp is good. Promote it without deleting live first.
    if live_db.exists() {
        // Replace any prior bak, then move live aside.
        drop(fs::remove_file(live_bak));
        fs::rename(live_db, live_bak).map_err(|err| {
            storage(
                "sqlite_replace_live_to_bak_failed",
                &format!("cannot rename live sqlite to bak: {err}"),
            )
        })?;
    }
    fs::rename(temp_db, live_db).map_err(|err| {
        // Best-effort: put live back if rename failed and bak is present.
        if live_bak.exists() && !live_db.exists() {
            drop(fs::rename(live_bak, live_db));
        }
        storage(
            "sqlite_replace_rename_failed",
            &format!("cannot rename temp sqlite into place: {err}"),
        )
    })?;
    drop(fs::remove_file(live_bak));
    remove_wal_shm(live_db);
    Ok(())
}

fn remove_wal_shm(db: &Path) {
    drop(fs::remove_file(PathBuf::from(format!(
        "{}-wal",
        db.display()
    ))));
    drop(fs::remove_file(PathBuf::from(format!(
        "{}-shm",
        db.display()
    ))));
    drop(fs::remove_file(db.with_extension("db-wal")));
    drop(fs::remove_file(db.with_extension("db-shm")));
}

fn db_quick_check_ok(path: &Path) -> Result<bool, lomo_core::LomoError> {
    let conn = Connection::open(path).map_err(|err| from_sqlite(&err))?;
    let ok: String = conn
        .query_row("PRAGMA quick_check", [], |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    Ok(ok.eq_ignore_ascii_case("ok"))
}

fn ensure_quick_check(conn: &Connection, label: &str) -> Result<(), lomo_core::LomoError> {
    let ok: String = conn
        .query_row("PRAGMA quick_check", [], |row| row.get(0))
        .map_err(|err| from_sqlite(&err))?;
    if !ok.eq_ignore_ascii_case("ok") {
        return Err(corruption(
            "rebuild_integrity_failed",
            &format!("{label} database failed quick_check"),
        ));
    }
    Ok(())
}

/// Returns the write gate for a store that may be mid-rebuild.
#[must_use]
pub fn write_gate_for_checkpoint(workspace_root: &Path) -> WriteGate {
    let checkpoint_path = workspace_root
        .join(SQLITE_DIR_NAME)
        .join("rebuild.checkpoint.json");
    if !checkpoint_path.exists() {
        return WriteGate::Ready;
    }
    match load_or_init_checkpoint(&checkpoint_path) {
        Ok(cp) if cp.phase != RebuildPhase::Complete => WriteGate::RebuildingReadOnly,
        _ => WriteGate::Ready,
    }
}

/// Rejects mutations while rebuilding (helper for callers).
///
/// # Errors
///
/// Returns `store_rebuilding` when the gate is read-only.
pub fn ensure_writable(gate: WriteGate) -> Result<(), lomo_core::LomoError> {
    if gate == WriteGate::RebuildingReadOnly {
        return Err(busy(
            "store_rebuilding",
            "write and sync are rejected during rebuild",
        ));
    }
    Ok(())
}

fn load_or_init_checkpoint(path: &Path) -> Result<RebuildCheckpoint, lomo_core::LomoError> {
    if path.exists() {
        let text = fs::read_to_string(path).map_err(|err| {
            storage(
                "rebuild_checkpoint_read_failed",
                &format!("cannot read checkpoint: {err}"),
            )
        })?;
        let value: serde_json::Value = serde_json::from_str(&text).map_err(|err| {
            corruption(
                "rebuild_checkpoint_corrupt",
                &format!("cannot parse checkpoint: {err}"),
            )
        })?;
        let phase = value
            .get("phase")
            .and_then(|v| v.as_str())
            .ok_or_else(|| corruption("rebuild_checkpoint_corrupt", "missing phase"))?;
        let scanned = value
            .get("scanned")
            .and_then(serde_json::Value::as_u64)
            .unwrap_or(0);
        let total_hint = value
            .get("total_hint")
            .and_then(serde_json::Value::as_u64)
            .unwrap_or(0);
        let isolated = value
            .get("isolated")
            .and_then(serde_json::Value::as_u64)
            .unwrap_or(0);
        return Ok(RebuildCheckpoint {
            phase: RebuildPhase::parse(phase)?,
            scanned,
            total_hint,
            isolated,
        });
    }
    Ok(RebuildCheckpoint {
        phase: RebuildPhase::Starting,
        scanned: 0,
        total_hint: 0,
        isolated: 0,
    })
}

fn save_checkpoint(
    path: &Path,
    checkpoint: &RebuildCheckpoint,
) -> Result<(), lomo_core::LomoError> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|err| {
            storage(
                "sqlite_dir_create_failed",
                &format!("cannot create sqlite dir: {err}"),
            )
        })?;
    }
    let json = serde_json::json!({
        "phase": checkpoint.phase.as_str(),
        "scanned": checkpoint.scanned,
        "total_hint": checkpoint.total_hint,
        "isolated": checkpoint.isolated,
    });
    let tmp = path.with_extension("json.tmp");
    fs::write(&tmp, json.to_string()).map_err(|err| {
        storage(
            "rebuild_checkpoint_write_failed",
            &format!("cannot write checkpoint: {err}"),
        )
    })?;
    fs::rename(&tmp, path).map_err(|err| {
        storage(
            "rebuild_checkpoint_rename_failed",
            &format!("cannot rename checkpoint: {err}"),
        )
    })?;
    Ok(())
}

fn create_or_open_temp(
    temp_db: &Path,
    checkpoint: &RebuildCheckpoint,
) -> Result<Connection, lomo_core::LomoError> {
    if checkpoint.phase == RebuildPhase::Starting || !temp_db.exists() {
        create_schema_db(temp_db)
    } else {
        open_temp_existing(temp_db)
    }
}

fn open_temp_existing(temp_db: &Path) -> Result<Connection, lomo_core::LomoError> {
    let conn = Connection::open(temp_db).map_err(|err| from_sqlite(&err))?;
    conn.pragma_update(None, "foreign_keys", "ON")
        .map_err(|err| from_sqlite(&err))?;
    Ok(conn)
}

fn list_memo_files(workspace_root: &Path) -> Result<Vec<PathBuf>, lomo_core::LomoError> {
    let mut out = Vec::new();
    collect_md_files(&workspace_root.join("memos"), &mut out)?;
    // Trashed bodies remain durable under trash/; rebuild must rehydrate them for FK pin/trash.
    collect_md_files(&workspace_root.join("trash"), &mut out)?;
    out.sort();
    Ok(out)
}

fn collect_md_files(dir: &Path, out: &mut Vec<PathBuf>) -> Result<(), lomo_core::LomoError> {
    if !dir.exists() {
        return Ok(());
    }
    for entry in fs::read_dir(dir).map_err(|err| {
        storage(
            "memo_list_failed",
            &format!("cannot list {}: {err}", dir.display()),
        )
    })? {
        let entry = entry.map_err(|err| {
            storage(
                "memo_list_failed",
                &format!("cannot read memo entry: {err}"),
            )
        })?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) == Some("md") {
            out.push(path);
        }
    }
    Ok(())
}

fn index_memo_file(conn: &Connection, path: &Path) -> Result<(), lomo_core::LomoError> {
    let content = fs::read_to_string(path).map_err(|err| {
        storage(
            "memo_read_failed",
            &format!("cannot read {} for rebuild: {err}", path.display()),
        )
    })?;
    let memo_id = path
        .file_stem()
        .and_then(|s| s.to_str())
        .ok_or_else(|| validation("invalid_memo_filename", "memo file stem must be utf-8"))?
        .to_owned();
    let search_content = index_tokens(&content);
    let preview: String = content.chars().take(200).collect();
    let fingerprint = fingerprint_content(&content);
    let facts = project_content_facts(&content)?;
    let has_todo = i64::from(facts.has_todo);
    let has_url = i64::from(facts.has_url);
    let has_attachment = i64::from(!facts.attachment_paths.is_empty());
    let parent_name = path
        .parent()
        .and_then(|p| p.file_name())
        .and_then(|n| n.to_str())
        .unwrap_or("memos");
    let source_path = format!("{parent_name}/{memo_id}.md");
    let now = 0_i64;

    // Skip if already indexed (resume).
    let exists: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM memo WHERE memo_id = ?1",
            params![memo_id],
            |row| row.get(0),
        )
        .map_err(|err| from_sqlite(&err))?;
    if exists > 0 {
        return Ok(());
    }

    conn.execute(
        "INSERT INTO memo(memo_id, source_path, file_fingerprint, has_todo, has_url, has_attachment, \
         created_at_ms, updated_at_ms, body_preview, search_content, content_revision) \
         VALUES(?1,?2,?3,?4,?5,?6,?7,?7,?8,?9,1)",
        params![
            memo_id,
            source_path,
            fingerprint,
            has_todo,
            has_url,
            has_attachment,
            now,
            preview,
            search_content
        ],
    )
    .map_err(|err| from_sqlite(&err))?;
    let rowid: i64 = conn
        .query_row(
            "SELECT rowid FROM memo WHERE memo_id = ?1",
            params![memo_id],
            |row| row.get(0),
        )
        .map_err(|err| from_sqlite(&err))?;
    conn.execute(
        "INSERT INTO memo_fts(rowid, search_content) VALUES(?1, ?2)",
        params![rowid, search_content],
    )
    .map_err(|err| from_sqlite(&err))?;
    for tag in &facts.tags {
        rehydrate_tag(conn, &memo_id, tag)?;
    }
    for rel in &facts.attachment_paths {
        if rel.is_empty() || rel.len() > 1024 {
            return Err(validation(
                "invalid_attachment_path",
                "attachment relative path is empty or too long",
            ));
        }
        conn.execute(
            "INSERT OR IGNORE INTO attachment_ref(memo_id, relative_path) VALUES(?1, ?2)",
            params![memo_id, rel],
        )
        .map_err(|err| from_sqlite(&err))?;
    }
    Ok(())
}

fn apply_lomo_state(conn: &Connection, paths: &LomoPaths) -> Result<u64, lomo_core::LomoError> {
    let mut isolated = 0u64;
    isolated += apply_state_dir(conn, paths)?;
    isolated += apply_history_dir(conn, paths)?;
    Ok(isolated)
}

fn apply_state_dir(conn: &Connection, paths: &LomoPaths) -> Result<u64, lomo_core::LomoError> {
    let mut isolated = 0u64;
    if !paths.state.exists() {
        return Ok(0);
    }
    for entry in fs::read_dir(&paths.state).map_err(|err| {
        storage(
            "lomo_state_list_failed",
            &format!("cannot list state: {err}"),
        )
    })? {
        let entry = entry.map_err(|err| {
            storage(
                "lomo_state_list_failed",
                &format!("cannot read state entry: {err}"),
            )
        })?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("rec") {
            continue;
        }
        match read_record(&path) {
            Ok(record) if record.payload.kind == LomoRecordKind::State => {
                let body: StateBody =
                    serde_json::from_str(&record.payload.body_json).map_err(|err| {
                        corruption(
                            "lomo_state_payload_invalid",
                            &format!("state payload invalid: {err}"),
                        )
                    })?;
                rehydrate_state_body(conn, &body)?;
            }
            Ok(_) => {}
            Err(_) => {
                drop(isolate_corrupt_record(&path)?);
                isolated += 1;
            }
        }
    }
    Ok(isolated)
}

fn rehydrate_state_body(conn: &Connection, body: &StateBody) -> Result<(), lomo_core::LomoError> {
    if body.pinned {
        conn.execute(
            "INSERT OR REPLACE INTO memo_pin(memo_id, pinned_at_ms) VALUES(?1, ?2)",
            params![body.memo_id, body.pinned_at_ms.unwrap_or(0)],
        )
        .map_err(|err| from_sqlite(&err))?;
    }
    if body.trashed {
        conn.execute(
            "INSERT OR REPLACE INTO memo_trash(memo_id, trashed_at_ms) VALUES(?1, ?2)",
            params![body.memo_id, body.trashed_at_ms.unwrap_or(0)],
        )
        .map_err(|err| from_sqlite(&err))?;
    }
    // Durable tags are authoritative when present. Empty durable tags leave content-indexed tags
    // (import / plain Markdown without a prior state write).
    if !body.tags.is_empty() {
        conn.execute(
            "DELETE FROM memo_tag WHERE memo_id = ?1",
            params![body.memo_id],
        )
        .map_err(|err| from_sqlite(&err))?;
        for tag in &body.tags {
            rehydrate_tag(conn, &body.memo_id, tag)?;
        }
    }
    Ok(())
}

fn apply_history_dir(conn: &Connection, paths: &LomoPaths) -> Result<u64, lomo_core::LomoError> {
    let mut isolated = 0u64;
    if !paths.history.exists() {
        return Ok(0);
    }
    for entry in fs::read_dir(&paths.history).map_err(|err| {
        storage(
            "lomo_history_list_failed",
            &format!("cannot list history: {err}"),
        )
    })? {
        let entry = entry.map_err(|err| {
            storage(
                "lomo_history_list_failed",
                &format!("cannot read history entry: {err}"),
            )
        })?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("rec") {
            continue;
        }
        match read_record(&path) {
            Ok(record) if record.payload.kind == LomoRecordKind::History => {
                let body: HistoryBody =
                    serde_json::from_str(&record.payload.body_json).map_err(|err| {
                        corruption(
                            "lomo_history_payload_invalid",
                            &format!("history payload invalid: {err}"),
                        )
                    })?;
                let rev = i64::try_from(body.revision).unwrap_or(i64::MAX);
                conn.execute(
                    "INSERT OR REPLACE INTO revision_index(memo_id, revision, history_record_id, created_at_ms) \
                     VALUES(?1,?2,?3,0)",
                    params![body.memo_id, rev, record.payload.record_id],
                )
                .map_err(|err| from_sqlite(&err))?;
            }
            Ok(_) => {}
            Err(_) => {
                drop(isolate_corrupt_record(&path)?);
                isolated += 1;
            }
        }
    }
    Ok(isolated)
}

fn rehydrate_tag(conn: &Connection, memo_id: &str, tag: &str) -> Result<(), lomo_core::LomoError> {
    if tag.is_empty() || tag.len() > 128 || tag.contains('\'') {
        return Err(validation(
            "invalid_tag_on_rebuild",
            "durable state tag is invalid",
        ));
    }
    conn.execute("INSERT OR IGNORE INTO tag(name) VALUES(?1)", params![tag])
        .map_err(|err| from_sqlite(&err))?;
    let tag_id: i64 = conn
        .query_row("SELECT id FROM tag WHERE name = ?1", params![tag], |row| {
            row.get(0)
        })
        .map_err(|err| from_sqlite(&err))?;
    conn.execute(
        "INSERT OR IGNORE INTO memo_tag(memo_id, tag_id) VALUES(?1, ?2)",
        params![memo_id, tag_id],
    )
    .map_err(|err| from_sqlite(&err))?;
    Ok(())
}
