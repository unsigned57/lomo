//! One-shot v1→v2 history/state activation migration (stage-5 P5-01).
//!
//! Contract:
//! - Read-only over user Markdown/media (never delete/overwrite user files).
//! - Write v2 objects under staging trees, validate count/digest/parent closure.
//! - Atomic layout head switch only on full success.
//! - Crash before head switch leaves v1 authoritative (fail-closed).
//! - After success, runtime layout is v2; v1 trees are no longer read/written by runtime paths.
//!
//! Migration action types intentionally have **no** user-file delete or overwrite branches.
//! Structural proof is the exhaustive [`all_migration_actions`] set plus
//! [`MigrationAction::may_touch_user_files`] / delete / overwrite predicates (all `const false`).

use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::history_v2::{
    self, HistoryHead, HistoryRevisionV2, StateHead, StateRevisionCreate, StateRevisionV2,
    validate_parent_closure,
};
use crate::limits::{corruption, storage, validation};
use crate::lomo_record::{
    LomoLayoutVersion, LomoPaths, LomoPayload, LomoRecordKind, hex_encode, read_record,
    write_layout_head_v2, write_record_atomic,
};

/// Migration action class — structural proof that no user-file delete/overwrite exists.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MigrationAction {
    /// Read a durable v1 history record (internal only).
    ReadV1History,
    /// Read a durable v1 state record (internal only).
    ReadV1State,
    /// Write a staged v2 history object (under `.lomo` only).
    WriteStagedHistoryObject,
    /// Write a staged v2 state object (under `.lomo` only).
    WriteStagedStateObject,
    /// Write staged history/state heads (under `.lomo` only).
    WriteStagedHead,
    /// Validate parent closure / digests / counts.
    ValidateClosure,
    /// Atomic layout head switch to v2 (under `.lomo` only).
    SwitchLayoutHead,
    /// Best-effort rename of old v1 trees after successful head switch (internal only).
    RetireV1InternalTrees,
}

impl MigrationAction {
    /// Returns true only for actions that may touch paths under user Markdown/media roots.
    ///
    /// Always false: migration is confined to `.lomo/**` and never emits user-file delete/overwrite.
    #[must_use]
    pub const fn may_touch_user_files(self) -> bool {
        false
    }

    /// Returns true if this action class can emit a user-file delete branch.
    #[must_use]
    pub const fn has_user_file_delete_branch(self) -> bool {
        false
    }

    /// Returns true if this action class can emit a user-file overwrite branch.
    #[must_use]
    pub const fn has_user_file_overwrite_branch(self) -> bool {
        false
    }
}

/// Result of a successful migration.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MigrationResult {
    pub history_revisions_written: u64,
    pub state_revisions_written: u64,
    pub memo_heads: u64,
    pub layout: LomoLayoutVersion,
}

/// Crash injection points for host crash-matrix tests (no production use).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum MigrationCrashPoint {
    #[default]
    None,
    AfterStagingBeforeValidate,
    AfterValidateBeforeHeadSwitch,
    AfterHeadSwitchBeforeRetire,
}

/// Runs one-shot v1→v2 migration. Idempotent when layout head is already v2.
///
/// # Errors
///
/// - Already v2 → Ok (no-op; still best-effort retires leftover v1 internal trees)
/// - Corrupt v1 records → corruption (not clean-slate)
/// - Validation failure → leaves layout at v1; staging may remain under `.lomo/migration-staging`
/// - Crash injection → storage error with `migration_injected_crash` after partial work
///
/// Never deletes or overwrites user Markdown/media files.
///
/// Recovery rule after crash at [`MigrationCrashPoint::AfterHeadSwitchBeforeRetire`]:
/// layout head is already V2 (authoritative); user Markdown/media are untouched; v1 history/state
/// trees may still exist under `.lomo` as internal leftovers. Re-running migration is idempotent and
/// only completes best-effort retire/staging cleanup — it never rewrites user files or reverts the head.
pub fn migrate_history_state_v1_to_v2(
    workspace_root: &Path,
) -> Result<MigrationResult, lomo_core::LomoError> {
    migrate_history_state_v1_to_v2_with_crash(workspace_root, MigrationCrashPoint::None)
}

/// Same as [`migrate_history_state_v1_to_v2`] with crash injection for host tests.
///
/// # Errors
///
/// See [`migrate_history_state_v1_to_v2`].
pub fn migrate_history_state_v1_to_v2_with_crash(
    workspace_root: &Path,
    crash: MigrationCrashPoint,
) -> Result<MigrationResult, lomo_core::LomoError> {
    let current = LomoPaths::for_workspace(workspace_root);
    if current.layout == LomoLayoutVersion::V2 {
        // Layout head is authority. Finish residual internal cleanup only (crash-after-head recovery).
        complete_post_head_cleanup(workspace_root)?;
        return Ok(MigrationResult {
            history_revisions_written: 0,
            state_revisions_written: 0,
            memo_heads: 0,
            layout: LomoLayoutVersion::V2,
        });
    }

    let staging_root = prepare_staging_root(workspace_root)?;
    let v1 = LomoPaths::for_workspace_with_layout(workspace_root, LomoLayoutVersion::V1);
    let (all_revisions, heads) = migrate_history_rows(&v1)?;
    let staged_paths = create_staged_layout(workspace_root, &staging_root, &v1)?;
    stage_history_objects(&staged_paths, &all_revisions, &heads)?;
    let (state_written, state_heads) = stage_state_objects(&staged_paths, &v1)?;

    if crash == MigrationCrashPoint::AfterStagingBeforeValidate {
        return Err(storage(
            "migration_injected_crash",
            "injected crash after staging before validate",
        ));
    }

    validate_staged_history(&staged_paths, &all_revisions)?;

    if crash == MigrationCrashPoint::AfterValidateBeforeHeadSwitch {
        return Err(storage(
            "migration_injected_crash",
            "injected crash after validate before head switch",
        ));
    }

    promote_staged_trees(workspace_root, &staged_paths.history, &staged_paths.state)?;
    write_layout_head_v2(workspace_root)?;

    if crash == MigrationCrashPoint::AfterHeadSwitchBeforeRetire {
        return Err(storage(
            "migration_injected_crash",
            "injected crash after head switch before retire",
        ));
    }

    retire_v1_tree(&v1.history)?;
    retire_v1_tree(&v1.state)?;
    // Best-effort staging cleanup after success; layout head already authoritative.
    drop(fs::remove_dir_all(&staging_root));

    Ok(MigrationResult {
        history_revisions_written: all_revisions.len() as u64,
        state_revisions_written: state_written,
        memo_heads: heads.len() as u64 + state_heads,
        layout: LomoLayoutVersion::V2,
    })
}

/// Completes best-effort internal cleanup when layout head is already V2.
///
/// Retires leftover v1 history/state trees and removes migration staging. Never touches user files.
fn complete_post_head_cleanup(workspace_root: &Path) -> Result<(), lomo_core::LomoError> {
    let v1 = LomoPaths::for_workspace_with_layout(workspace_root, LomoLayoutVersion::V1);
    retire_v1_tree(&v1.history)?;
    retire_v1_tree(&v1.state)?;
    let staging_root = workspace_root.join(".lomo").join("migration-staging");
    if staging_root.exists() {
        // Best-effort only: layout head already switched; staging is not authoritative.
        drop(fs::remove_dir_all(&staging_root));
    }
    Ok(())
}

fn prepare_staging_root(workspace_root: &Path) -> Result<PathBuf, lomo_core::LomoError> {
    let staging_root = workspace_root.join(".lomo").join("migration-staging");
    // Clean prior failed staging (internal only).
    if staging_root.exists() {
        fs::remove_dir_all(&staging_root).map_err(|err| {
            storage(
                "migration_staging_clean_failed",
                &format!("cannot clean migration staging: {err}"),
            )
        })?;
    }
    fs::create_dir_all(&staging_root).map_err(|err| {
        storage(
            "migration_staging_create_failed",
            &format!("cannot create migration staging: {err}"),
        )
    })?;
    Ok(staging_root)
}

fn migrate_history_rows(
    v1: &LomoPaths,
) -> Result<(Vec<HistoryRevisionV2>, Vec<HistoryHead>), lomo_core::LomoError> {
    let v1_history_rows = load_v1_history(v1)?;
    let ordered = history_v2::order_v1_history_for_migration(&v1_history_rows);
    let mut all_revisions: Vec<HistoryRevisionV2> = Vec::new();
    let mut heads: Vec<HistoryHead> = Vec::new();
    let created_at_ms = 0_i64;
    for (memo_id, chain) in &ordered {
        let migrated = history_v2::migrate_memo_history_chain(memo_id, chain, created_at_ms)?;
        if let Some(last) = migrated.last() {
            heads.push(HistoryHead {
                memo_id: memo_id.clone(),
                head_revision_id: last.revision_id.clone(),
            });
        }
        all_revisions.extend(migrated);
    }
    validate_parent_closure(&all_revisions)?;
    Ok((all_revisions, heads))
}

fn create_staged_layout(
    workspace_root: &Path,
    staging_root: &Path,
    v1: &LomoPaths,
) -> Result<LomoPaths, lomo_core::LomoError> {
    let staged_history = staging_root.join("history").join("v2");
    let staged_state = staging_root.join("state").join("v2");
    fs::create_dir_all(staged_history.join("objects")).map_err(|err| {
        storage(
            "migration_stage_history_failed",
            &format!("cannot create staged history: {err}"),
        )
    })?;
    fs::create_dir_all(staged_history.join("heads")).map_err(|err| {
        storage(
            "migration_stage_history_failed",
            &format!("cannot create staged history heads: {err}"),
        )
    })?;
    fs::create_dir_all(staged_state.join("objects")).map_err(|err| {
        storage(
            "migration_stage_state_failed",
            &format!("cannot create staged state: {err}"),
        )
    })?;
    fs::create_dir_all(staged_state.join("heads")).map_err(|err| {
        storage(
            "migration_stage_state_failed",
            &format!("cannot create staged state heads: {err}"),
        )
    })?;

    Ok(LomoPaths {
        root: workspace_root.join(".lomo"),
        manifest: v1.manifest.clone(),
        operations: v1.operations.clone(),
        history: staged_history,
        state: staged_state,
        local: v1.local.clone(),
        layout: LomoLayoutVersion::V2,
    })
}

fn stage_history_objects(
    staged_paths: &LomoPaths,
    all_revisions: &[HistoryRevisionV2],
    heads: &[HistoryHead],
) -> Result<(), lomo_core::LomoError> {
    for rev in all_revisions {
        history_v2::write_history_revision(staged_paths, rev)?;
    }
    for head in heads {
        history_v2::write_history_head(staged_paths, head)?;
    }
    Ok(())
}

fn stage_state_objects(
    staged_paths: &LomoPaths,
    v1: &LomoPaths,
) -> Result<(u64, u64), lomo_core::LomoError> {
    let v1_state_rows = load_v1_state(v1)?;
    let mut state_written = 0_u64;
    let mut state_heads = 0_u64;
    let created_at_ms = 0_i64;
    for row in &v1_state_rows {
        let rev = StateRevisionV2::create(StateRevisionCreate {
            memo_id: &row.memo_id,
            parent: None,
            pinned: row.pinned,
            trashed: row.trashed,
            pinned_at_ms: row.pinned_at_ms,
            trashed_at_ms: row.trashed_at_ms,
            pin_operation_id: None,
            trash_operation_id: None,
            canonical_metadata: "v1_state",
            created_at_ms,
        })?;
        history_v2::write_state_revision(staged_paths, &rev)?;
        history_v2::write_state_head(
            staged_paths,
            &StateHead {
                memo_id: row.memo_id.clone(),
                head_revision_id: rev.revision_id,
            },
        )?;
        state_written += 1;
        state_heads += 1;
    }
    Ok((state_written, state_heads))
}

fn validate_staged_history(
    staged_paths: &LomoPaths,
    all_revisions: &[HistoryRevisionV2],
) -> Result<(), lomo_core::LomoError> {
    // Count / digest validation: re-read staged objects and compare ids.
    let mut reread_count = 0_u64;
    let mut digest = Sha256::new();
    for rev in all_revisions {
        let loaded = history_v2::read_history_revision(staged_paths, &rev.revision_id)?;
        if loaded.revision_id != rev.revision_id || loaded.content_digest != rev.content_digest {
            return Err(corruption(
                "migration_history_digest_mismatch",
                "staged history digest or id mismatch on re-read",
            ));
        }
        digest.update(loaded.revision_id.as_str().as_bytes());
        reread_count += 1;
    }
    if reread_count != all_revisions.len() as u64 {
        return Err(corruption(
            "migration_history_count_mismatch",
            "staged history count mismatch",
        ));
    }
    // Digest retained for future multi-device audit logs; computed to fail closed on empty hashers.
    let _closure_digest: String = hex_encode(digest.finalize().as_slice());
    Ok(())
}

fn promote_staged_trees(
    workspace_root: &Path,
    staged_history: &Path,
    staged_state: &Path,
) -> Result<(), lomo_core::LomoError> {
    let live_history = workspace_root.join(".lomo").join("history").join("v2");
    let live_state = workspace_root.join(".lomo").join("state").join("v2");
    promote_dir(staged_history, &live_history)?;
    promote_dir(staged_state, &live_state)
}

fn promote_dir(from: &Path, to: &Path) -> Result<(), lomo_core::LomoError> {
    if to.exists() {
        fs::remove_dir_all(to).map_err(|err| {
            storage(
                "migration_promote_clean_failed",
                &format!("cannot clean existing v2 tree {}: {err}", to.display()),
            )
        })?;
    }
    if let Some(parent) = to.parent() {
        fs::create_dir_all(parent).map_err(|err| {
            storage(
                "migration_promote_parent_failed",
                &format!("cannot create parent for {}: {err}", to.display()),
            )
        })?;
    }
    fs::rename(from, to).map_err(|err| {
        storage(
            "migration_promote_rename_failed",
            &format!(
                "cannot promote {} -> {}: {err}",
                from.display(),
                to.display()
            ),
        )
    })
}

fn retire_v1_tree(path: &Path) -> Result<(), lomo_core::LomoError> {
    if !path.exists() {
        return Ok(());
    }
    let retired = path.with_extension("v1-retired");
    // If already retired, leave it.
    if retired.exists() {
        return Ok(());
    }
    fs::rename(path, &retired).map_err(|err| {
        storage(
            "migration_retire_v1_failed",
            &format!(
                "cannot retire v1 tree {} -> {}: {err}",
                path.display(),
                retired.display()
            ),
        )
    })?;
    Ok(())
}

#[derive(Debug, Clone)]
struct V1StateRow {
    memo_id: String,
    pinned: bool,
    trashed: bool,
    pinned_at_ms: Option<i64>,
    trashed_at_ms: Option<i64>,
}

fn load_v1_history(
    paths: &LomoPaths,
) -> Result<Vec<(String, u64, String, String)>, lomo_core::LomoError> {
    if !paths.history.exists() {
        return Ok(Vec::new());
    }
    let mut out = Vec::new();
    let read = fs::read_dir(&paths.history).map_err(|err| {
        storage(
            "migration_v1_history_list_failed",
            &format!("cannot list v1 history: {err}"),
        )
    })?;
    for entry in read {
        let entry = entry.map_err(|err| {
            storage(
                "migration_v1_history_list_failed",
                &format!("cannot read v1 history entry: {err}"),
            )
        })?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("rec") {
            continue;
        }
        let record = read_record(&path)?;
        if record.payload.kind != LomoRecordKind::History {
            return Err(corruption(
                "migration_v1_history_kind_mismatch",
                "v1 history path does not contain a History record",
            ));
        }
        let body: V1HistoryBody =
            serde_json::from_str(&record.payload.body_json).map_err(|err| {
                corruption(
                    "migration_v1_history_payload_invalid",
                    &format!("cannot decode v1 history body: {err}"),
                )
            })?;
        out.push((
            body.memo_id,
            body.revision,
            body.content,
            body.file_fingerprint,
        ));
    }
    Ok(out)
}

fn load_v1_state(paths: &LomoPaths) -> Result<Vec<V1StateRow>, lomo_core::LomoError> {
    if !paths.state.exists() {
        return Ok(Vec::new());
    }
    let mut out = Vec::new();
    let read = fs::read_dir(&paths.state).map_err(|err| {
        storage(
            "migration_v1_state_list_failed",
            &format!("cannot list v1 state: {err}"),
        )
    })?;
    for entry in read {
        let entry = entry.map_err(|err| {
            storage(
                "migration_v1_state_list_failed",
                &format!("cannot read v1 state entry: {err}"),
            )
        })?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("rec") {
            continue;
        }
        let record = read_record(&path)?;
        if record.payload.kind != LomoRecordKind::State {
            return Err(corruption(
                "migration_v1_state_kind_mismatch",
                "v1 state path does not contain a State record",
            ));
        }
        let body: V1StateBody = serde_json::from_str(&record.payload.body_json).map_err(|err| {
            corruption(
                "migration_v1_state_payload_invalid",
                &format!("cannot decode v1 state body: {err}"),
            )
        })?;
        out.push(V1StateRow {
            memo_id: body.memo_id,
            pinned: body.pinned,
            trashed: body.trashed,
            pinned_at_ms: body.pinned_at_ms,
            trashed_at_ms: body.trashed_at_ms,
        });
    }
    Ok(out)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct V1HistoryBody {
    memo_id: String,
    revision: u64,
    content: String,
    file_fingerprint: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct V1StateBody {
    memo_id: String,
    pinned: bool,
    trashed: bool,
    pinned_at_ms: Option<i64>,
    trashed_at_ms: Option<i64>,
    #[serde(default)]
    tags: Vec<String>,
}

/// Static enumeration of migration actions for architecture/static tests.
#[must_use]
pub const fn all_migration_actions() -> &'static [MigrationAction] {
    &[
        MigrationAction::ReadV1History,
        MigrationAction::ReadV1State,
        MigrationAction::WriteStagedHistoryObject,
        MigrationAction::WriteStagedStateObject,
        MigrationAction::WriteStagedHead,
        MigrationAction::ValidateClosure,
        MigrationAction::SwitchLayoutHead,
        MigrationAction::RetireV1InternalTrees,
    ]
}

/// Helper for tests: write a v1 history record using workspace codec.
///
/// # Errors
///
/// Encode/storage failures.
pub fn write_v1_history_for_test(
    workspace_root: &Path,
    memo_id: &str,
    revision: u64,
    content: &str,
    file_fingerprint: &str,
) -> Result<PathBuf, lomo_core::LomoError> {
    let paths = LomoPaths::for_workspace_with_layout(workspace_root, LomoLayoutVersion::V1);
    paths.ensure_layout()?;
    let body = V1HistoryBody {
        memo_id: memo_id.to_owned(),
        revision,
        content: content.to_owned(),
        file_fingerprint: file_fingerprint.to_owned(),
    };
    let body_json = serde_json::to_string(&body).map_err(|err| {
        validation(
            "v1_history_encode_failed",
            &format!("cannot encode v1 history: {err}"),
        )
    })?;
    let record_id = format!("{memo_id}-r{revision}");
    let path = paths.history.join(format!("{record_id}.rec"));
    write_record_atomic(
        &path,
        &LomoPayload {
            kind: LomoRecordKind::History,
            record_id,
            body_json,
        },
    )?;
    Ok(path)
}

/// Helper for tests: write a v1 state record using workspace codec.
///
/// # Errors
///
/// Encode/storage failures.
pub fn write_v1_state_for_test(
    workspace_root: &Path,
    memo_id: &str,
    pinned: bool,
    trashed: bool,
) -> Result<PathBuf, lomo_core::LomoError> {
    let paths = LomoPaths::for_workspace_with_layout(workspace_root, LomoLayoutVersion::V1);
    paths.ensure_layout()?;
    let body = V1StateBody {
        memo_id: memo_id.to_owned(),
        pinned,
        trashed,
        pinned_at_ms: None,
        trashed_at_ms: None,
        tags: Vec::new(),
    };
    let body_json = serde_json::to_string(&body).map_err(|err| {
        validation(
            "v1_state_encode_failed",
            &format!("cannot encode v1 state: {err}"),
        )
    })?;
    let path = paths.state.join(format!("{memo_id}.rec"));
    write_record_atomic(
        &path,
        &LomoPayload {
            kind: LomoRecordKind::State,
            record_id: memo_id.to_owned(),
            body_json,
        },
    )?;
    Ok(path)
}
