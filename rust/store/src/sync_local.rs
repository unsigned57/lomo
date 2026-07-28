//! Unified Direct/SAF local sync ports (P5-04 host slice).
//!
//! All sync-driven user-byte and projection mutations go through the same expected-revision
//! `Store` transaction path as ordinary edits. Snapshot is coarse (path / digest / revision /
//! verified media paths only — never full Markdown bulk).
//!
//! Protocol: `prepare_sync_apply` → platform executor results → `verify_platform_results` →
//! `commit_sync_apply`. Direct runs media FS actions in-process; SAF leaves platform bytes to the
//! Kotlin executor and only commits after every result is verified. SAF projection DB is
//! app-private, generation-bound, and rebuildable from coarse store facts — never a second write
//! authority. Residual OPEN: Kotlin SAF action executor / device wiring (P5-09+).

use std::fs::File;
use std::path::{Path, PathBuf};

use rusqlite::{Connection, OptionalExtension, params};
use sha2::{Digest, Sha256};

use lomo_core::{LomoError, OperationId};
use lomo_workspace::WorkspaceGenerationId;

use crate::content_facts::fingerprint_content;
use crate::error::{conflict, from_sqlite, storage, validation};
use crate::lomo_format::MemoCommandKind;
use crate::transaction::{MemoCommand, MemoCommitResult, WriteGate, apply_memo_command};

/// Coarse local path fact for planner consumption (no body bytes).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SyncLocalPathFact {
    /// Workspace-relative path (e.g. `memos/id.md`).
    pub path: String,
    /// Content digest (sha256 hex of Markdown body).
    pub digest: String,
    /// Per-memo content revision fence.
    pub content_revision: u64,
    /// Memo identity (when the path is a memo body).
    pub memo_id: Option<String>,
    /// Verified attachment relative paths for this memo (media manifest).
    pub media_paths: Vec<String>,
}

/// Coarse local sync snapshot: path/digest/revision/media only.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SyncLocalSnapshot {
    /// Durable workspace generation fence for this store open.
    pub workspace_generation: String,
    /// Core high-water revision at snapshot time.
    pub high_water_revision: u64,
    /// Path facts (memo bodies; media listed under `media_paths`).
    pub entries: Vec<SyncLocalPathFact>,
}

/// One local mutation requested by the sync owner (expected-revision fence).
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum LocalSyncMutation {
    /// Create or replace memo body at `memos/{memo_id}.md`.
    UpsertMemo {
        operation_id: String,
        memo_id: String,
        expected_revision: u64,
        expected_fingerprint: Option<String>,
        content: String,
        tags: Vec<String>,
    },
    /// Delete memo (trash) with expected revision.
    DeleteMemo {
        operation_id: String,
        memo_id: String,
        expected_revision: u64,
        expected_fingerprint: Option<String>,
    },
    /// Ensure a media/attachment path exists with expected digest (Direct host only).
    EnsureMediaPresent {
        relative_path: String,
        expected_digest: String,
        bytes: Vec<u8>,
    },
    /// Ensure a media/attachment path is absent (Direct host only).
    EnsureMediaAbsent { relative_path: String },
}

/// Batch of local sync mutations (same type for Direct; SAF uses prepare/commit scaffolding).
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct LocalSyncMutationBatch {
    pub mutations: Vec<LocalSyncMutation>,
}

/// Result of committing one mutation.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum LocalSyncMutationResult {
    Memo(MemoCommitResult),
    MediaEnsured {
        relative_path: String,
        digest: String,
    },
    MediaRemoved {
        relative_path: String,
    },
}

/// Commit outcome for a full batch.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LocalSyncCommitResult {
    pub results: Vec<LocalSyncMutationResult>,
}

/// Platform action with expected fingerprint (SAF / Direct prepare step).
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum SyncPlatformAction {
    /// Write user bytes at a relative path (SAF executor or Direct FS).
    WriteUserBytes {
        relative_path: String,
        expected_fingerprint: String,
        bytes: Vec<u8>,
    },
    /// Delete user bytes at a relative path.
    DeleteUserBytes {
        relative_path: String,
        expected_fingerprint: Option<String>,
    },
}

/// Prepared apply plan: platform actions first, then store commit mutations.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct PreparedSyncApply {
    pub platform_actions: Vec<SyncPlatformAction>,
    pub commit_mutations: LocalSyncMutationBatch,
    pub expected_workspace_generation: String,
}

/// Platform executor result for one prepared action (verified before commit).
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum SyncPlatformActionResult {
    Applied {
        relative_path: String,
        observed_fingerprint: String,
    },
    Absent {
        relative_path: String,
    },
    Failed {
        relative_path: String,
        code: String,
    },
}

/// Binding for SAF projection DB (app-private, generation-bound, rebuildable cache).
///
/// User bytes and durable `.lomo` facts still mutate only via `PlatformActionBatch` +
/// `commit_sync_apply`. This cache never becomes a second write authority.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SafProjectionBinding {
    pub workspace_generation: String,
    /// App-private absolute path of the projection database file.
    pub projection_db_path: PathBuf,
}

impl SafProjectionBinding {
    /// Builds a generation-bound SAF projection binding under an app-private root.
    ///
    /// # Errors
    ///
    /// Validation when generation is empty.
    pub fn new(
        app_private_root: impl AsRef<Path>,
        workspace_generation: &WorkspaceGenerationId,
    ) -> Result<Self, LomoError> {
        let generation = workspace_generation.as_str();
        if generation.is_empty() {
            return Err(validation(
                "saf_projection_generation_empty",
                "SAF projection binding requires a non-empty WorkspaceGenerationId",
            ));
        }
        let projection_db_path = app_private_root
            .as_ref()
            .join("saf-projection")
            .join(generation)
            .join("projection.sqlite");
        Ok(Self {
            workspace_generation: generation.to_owned(),
            projection_db_path,
        })
    }

    /// Rejects a binding whose generation does not match the live workspace fence.
    ///
    /// # Errors
    ///
    /// Validation `saf_projection_generation_mismatch` when fences differ.
    pub fn require_generation(&self, live: &WorkspaceGenerationId) -> Result<(), LomoError> {
        if self.workspace_generation != live.as_str() {
            return Err(validation(
                "saf_projection_generation_mismatch",
                "SAF projection DB is bound to a different WorkspaceGenerationId; rebuild required",
            ));
        }
        Ok(())
    }

    /// Rebuilds the app-private SAF projection atomically from coarse owner facts.
    ///
    /// The cache contains only path/digest/revision/media-manifest data. Markdown/media bytes and
    /// durable `.lomo` facts remain in the SAF workspace and can recreate this file at any time.
    ///
    /// # Errors
    ///
    /// Generation mismatch, invalid snapshot facts, `SQLite`, or atomic publish failures.
    pub fn rebuild_from_snapshot(&self, snapshot: &SyncLocalSnapshot) -> Result<(), LomoError> {
        if snapshot.workspace_generation != self.workspace_generation {
            return Err(validation(
                "saf_projection_generation_mismatch",
                "SAF projection snapshot belongs to a different WorkspaceGenerationId",
            ));
        }
        let parent = self.projection_db_path.parent().ok_or_else(|| {
            validation(
                "saf_projection_path_invalid",
                "SAF projection path must have an app-private parent",
            )
        })?;
        std::fs::create_dir_all(parent).map_err(|err| {
            storage(
                "saf_projection_dir_create_failed",
                &format!("cannot create SAF projection directory: {err}"),
            )
        })?;
        let pending = self.projection_db_path.with_extension("sqlite.pending");
        remove_projection_pending(&pending)?;
        let mut connection = Connection::open(&pending).map_err(|err| from_sqlite(&err))?;
        connection
            .execute_batch(
                "PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL; \
                 CREATE TABLE projection_meta( \
                   singleton INTEGER PRIMARY KEY CHECK(singleton = 1), \
                   workspace_generation TEXT NOT NULL, high_water_revision INTEGER NOT NULL \
                 ); \
                 CREATE TABLE path_fact( \
                   path TEXT PRIMARY KEY, digest TEXT NOT NULL, content_revision INTEGER NOT NULL, \
                   memo_id TEXT, media_paths_json TEXT NOT NULL \
                 );",
            )
            .map_err(|err| from_sqlite(&err))?;
        let high_water_revision =
            i64::try_from(snapshot.high_water_revision).map_err(|_overflow| {
                validation(
                    "saf_projection_revision_overflow",
                    "SAF projection high-water revision exceeds SQLite i64",
                )
            })?;
        let transaction = connection.transaction().map_err(|err| from_sqlite(&err))?;
        transaction
            .execute(
                "INSERT INTO projection_meta(singleton, workspace_generation, high_water_revision) \
                 VALUES(1, ?1, ?2)",
                params![snapshot.workspace_generation, high_water_revision],
            )
            .map_err(|err| from_sqlite(&err))?;
        for entry in &snapshot.entries {
            validate_projection_fact(entry)?;
            let content_revision = i64::try_from(entry.content_revision).map_err(|_overflow| {
                validation(
                    "saf_projection_revision_overflow",
                    "SAF projection content revision exceeds SQLite i64",
                )
            })?;
            let media_paths_json = serde_json::to_string(&entry.media_paths).map_err(|err| {
                validation(
                    "saf_projection_media_encode_failed",
                    &format!("cannot encode SAF projection media paths: {err}"),
                )
            })?;
            transaction
                .execute(
                    "INSERT INTO path_fact(path, digest, content_revision, memo_id, media_paths_json) \
                     VALUES(?1, ?2, ?3, ?4, ?5)",
                    params![
                        entry.path,
                        entry.digest,
                        content_revision,
                        entry.memo_id,
                        media_paths_json
                    ],
                )
                .map_err(|err| from_sqlite(&err))?;
        }
        transaction.commit().map_err(|err| from_sqlite(&err))?;
        connection
            .execute_batch("PRAGMA optimize")
            .map_err(|err| from_sqlite(&err))?;
        drop(connection);
        File::open(&pending)
            .and_then(|file| file.sync_all())
            .map_err(|err| {
                storage(
                    "saf_projection_fsync_failed",
                    &format!("cannot fsync rebuilt SAF projection: {err}"),
                )
            })?;
        std::fs::rename(&pending, &self.projection_db_path).map_err(|err| {
            storage(
                "saf_projection_publish_failed",
                &format!("cannot atomically publish rebuilt SAF projection: {err}"),
            )
        })?;
        if let Ok(directory) = File::open(parent) {
            // behavior-contract: silent-result-ok: directory fsync is best-effort by filesystem.
            drop(directory.sync_all());
        }
        Ok(())
    }

    /// Reads the rebuildable coarse SAF projection after validating its generation fence.
    ///
    /// # Errors
    ///
    /// Missing/corrupt `SQLite`, generation mismatch, invalid facts, or JSON decode failures.
    pub fn read_snapshot(&self) -> Result<SyncLocalSnapshot, LomoError> {
        let connection =
            Connection::open(&self.projection_db_path).map_err(|err| from_sqlite(&err))?;
        let (workspace_generation, high_water_i64): (String, i64) = connection
            .query_row(
                "SELECT workspace_generation, high_water_revision FROM projection_meta \
                 WHERE singleton = 1",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .map_err(|err| from_sqlite(&err))?;
        if workspace_generation != self.workspace_generation {
            return Err(validation(
                "saf_projection_generation_mismatch",
                "SAF projection DB is bound to a different WorkspaceGenerationId; rebuild required",
            ));
        }
        let high_water_revision = u64::try_from(high_water_i64).map_err(|_overflow| {
            validation(
                "saf_projection_revision_invalid",
                "SAF projection high-water revision is negative",
            )
        })?;
        let mut statement = connection
            .prepare(
                "SELECT path, digest, content_revision, memo_id, media_paths_json \
                 FROM path_fact ORDER BY path",
            )
            .map_err(|err| from_sqlite(&err))?;
        let rows = statement
            .query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, Option<String>>(3)?,
                    row.get::<_, String>(4)?,
                ))
            })
            .map_err(|err| from_sqlite(&err))?;
        let mut entries = Vec::new();
        for row in rows {
            let (path, digest, revision_i64, memo_id, media_paths_json) =
                row.map_err(|err| from_sqlite(&err))?;
            let media_paths: Vec<String> =
                serde_json::from_str(&media_paths_json).map_err(|err| {
                    validation(
                        "saf_projection_media_invalid",
                        &format!("cannot decode SAF projection media paths: {err}"),
                    )
                })?;
            let fact = SyncLocalPathFact {
                path,
                digest,
                content_revision: u64::try_from(revision_i64).map_err(|_overflow| {
                    validation(
                        "saf_projection_revision_invalid",
                        "SAF projection content revision is negative",
                    )
                })?,
                memo_id,
                media_paths,
            };
            validate_projection_fact(&fact)?;
            entries.push(fact);
        }
        Ok(SyncLocalSnapshot {
            workspace_generation,
            high_water_revision,
            entries,
        })
    }
}

fn remove_projection_pending(path: &Path) -> Result<(), LomoError> {
    match std::fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(err) => Err(storage(
            "saf_projection_pending_remove_failed",
            &format!("cannot remove stale SAF projection pending file: {err}"),
        )),
    }
}

fn validate_projection_fact(entry: &SyncLocalPathFact) -> Result<(), LomoError> {
    reject_path_traversal(&entry.path)?;
    validate_sha256_hex(&entry.digest, "saf_projection_digest_invalid")?;
    for path in &entry.media_paths {
        reject_path_traversal(path)?;
    }
    Ok(())
}

fn validate_sha256_hex(raw: &str, code: &'static str) -> Result<(), LomoError> {
    if raw.len() != 64
        || !raw
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(validation(
            code,
            "digest must be 64 lowercase hexadecimal bytes",
        ));
    }
    Ok(())
}

/// Loads a coarse local sync snapshot from an open store connection + workspace root.
///
/// # Errors
///
/// Storage / generation load failures. Fingerprint mismatch → conflict (fail closed).
pub fn snapshot_sync_view(
    connection: &Connection,
    workspace_root: &Path,
    high_water_revision: u64,
) -> Result<SyncLocalSnapshot, LomoError> {
    let generation = lomo_workspace::load_or_mint_workspace_generation(workspace_root)?;
    let mut stmt = connection
        .prepare(
            "SELECT memo_id, source_path, file_fingerprint, content_revision \
             FROM memo ORDER BY source_path",
        )
        .map_err(|err| from_sqlite(&err))?;
    let rows = stmt
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, i64>(3)?,
            ))
        })
        .map_err(|err| from_sqlite(&err))?;

    let mut entries = Vec::new();
    for row in rows {
        let (memo_id, source_path, fingerprint, content_revision_i64) =
            row.map_err(|err| from_sqlite(&err))?;
        let content_revision = u64::try_from(content_revision_i64).map_err(|_overflow| {
            validation(
                "invalid_content_revision",
                "content_revision out of u64 in sync snapshot",
            )
        })?;
        let media_paths = load_media_paths(connection, &memo_id)?;
        let body_path = workspace_root.join(&source_path);
        if body_path.exists() {
            let body = std::fs::read_to_string(&body_path).map_err(|err| {
                storage(
                    "sync_snapshot_body_read_failed",
                    &format!(
                        "cannot read {} for sync snapshot: {err}",
                        body_path.display()
                    ),
                )
            })?;
            let live = fingerprint_content(&body);
            if live != fingerprint {
                return Err(conflict(
                    "sync_snapshot_fingerprint_mismatch",
                    "projection file_fingerprint does not match live workspace bytes",
                ));
            }
        }
        entries.push(SyncLocalPathFact {
            path: source_path,
            digest: fingerprint,
            content_revision,
            memo_id: Some(memo_id),
            media_paths,
        });
    }

    Ok(SyncLocalSnapshot {
        workspace_generation: generation.as_str().to_owned(),
        high_water_revision,
        entries,
    })
}

fn load_media_paths(connection: &Connection, memo_id: &str) -> Result<Vec<String>, LomoError> {
    let mut stmt = connection
        .prepare(
            "SELECT relative_path FROM attachment_ref WHERE memo_id = ?1 ORDER BY relative_path",
        )
        .map_err(|err| from_sqlite(&err))?;
    let rows = stmt
        .query_map(params![memo_id], |row| row.get::<_, String>(0))
        .map_err(|err| from_sqlite(&err))?;
    let mut paths = Vec::new();
    for row in rows {
        paths.push(row.map_err(|err| from_sqlite(&err))?);
    }
    Ok(paths)
}

/// Prepares a sync apply: platform actions carry expected fingerprints; commit mutations are
/// deferred until every platform result is verified.
///
/// # Errors
///
/// Validation when media bytes do not match `expected_digest`.
pub fn prepare_sync_apply(
    workspace_root: &Path,
    batch: &LocalSyncMutationBatch,
) -> Result<PreparedSyncApply, LomoError> {
    let generation = lomo_workspace::load_or_mint_workspace_generation(workspace_root)?;
    let mut platform_actions = Vec::new();
    let mut commit_mutations = LocalSyncMutationBatch {
        mutations: Vec::new(),
    };

    for mutation in &batch.mutations {
        append_prepared_mutation(
            workspace_root,
            mutation,
            &mut platform_actions,
            &mut commit_mutations,
        )?;
    }

    Ok(PreparedSyncApply {
        platform_actions,
        commit_mutations,
        expected_workspace_generation: generation.as_str().to_owned(),
    })
}

fn append_prepared_mutation(
    workspace_root: &Path,
    mutation: &LocalSyncMutation,
    platform_actions: &mut Vec<SyncPlatformAction>,
    commit_mutations: &mut LocalSyncMutationBatch,
) -> Result<(), LomoError> {
    match mutation {
        LocalSyncMutation::UpsertMemo {
            operation_id,
            memo_id,
            expected_revision,
            expected_fingerprint,
            content,
            tags,
        } => {
            validate_memo_id_for_sync(memo_id)?;
            if let Some(expected) = expected_fingerprint.as_deref() {
                verify_memo_file_fingerprint(workspace_root, memo_id, expected)?;
            }
            let digest = fingerprint_content(content);
            platform_actions.push(SyncPlatformAction::WriteUserBytes {
                relative_path: format!("memos/{memo_id}.md"),
                expected_fingerprint: digest,
                bytes: content.as_bytes().to_vec(),
            });
            commit_mutations
                .mutations
                .push(LocalSyncMutation::UpsertMemo {
                    operation_id: operation_id.clone(),
                    memo_id: memo_id.clone(),
                    expected_revision: *expected_revision,
                    expected_fingerprint: expected_fingerprint.clone(),
                    content: content.clone(),
                    tags: tags.clone(),
                });
        }
        LocalSyncMutation::DeleteMemo {
            operation_id,
            memo_id,
            expected_revision,
            expected_fingerprint,
        } => {
            validate_memo_id_for_sync(memo_id)?;
            if let Some(expected) = expected_fingerprint.as_deref() {
                verify_memo_file_fingerprint(workspace_root, memo_id, expected)?;
            }
            platform_actions.push(SyncPlatformAction::DeleteUserBytes {
                relative_path: format!("memos/{memo_id}.md"),
                expected_fingerprint: expected_fingerprint.clone(),
            });
            commit_mutations
                .mutations
                .push(LocalSyncMutation::DeleteMemo {
                    operation_id: operation_id.clone(),
                    memo_id: memo_id.clone(),
                    expected_revision: *expected_revision,
                    expected_fingerprint: expected_fingerprint.clone(),
                });
        }
        LocalSyncMutation::EnsureMediaPresent {
            relative_path,
            expected_digest,
            bytes,
        } => {
            let live = hex_digest(bytes);
            if live != *expected_digest {
                return Err(validation(
                    "sync_media_digest_mismatch",
                    "EnsureMediaPresent bytes do not match expected_digest",
                ));
            }
            platform_actions.push(SyncPlatformAction::WriteUserBytes {
                relative_path: relative_path.clone(),
                expected_fingerprint: expected_digest.clone(),
                bytes: bytes.clone(),
            });
            commit_mutations
                .mutations
                .push(LocalSyncMutation::EnsureMediaPresent {
                    relative_path: relative_path.clone(),
                    expected_digest: expected_digest.clone(),
                    bytes: bytes.clone(),
                });
        }
        LocalSyncMutation::EnsureMediaAbsent { relative_path } => {
            platform_actions.push(SyncPlatformAction::DeleteUserBytes {
                relative_path: relative_path.clone(),
                expected_fingerprint: None,
            });
            commit_mutations
                .mutations
                .push(LocalSyncMutation::EnsureMediaAbsent {
                    relative_path: relative_path.clone(),
                });
        }
    }
    Ok(())
}

/// Verifies platform action results against the prepared plan (generation + fingerprints).
///
/// # Errors
///
/// Generation mismatch, count/path/fingerprint mismatch, or any `Failed` result.
pub fn verify_platform_results(
    workspace_root: &Path,
    prepared: &PreparedSyncApply,
    results: &[SyncPlatformActionResult],
) -> Result<(), LomoError> {
    let live = lomo_workspace::load_or_mint_workspace_generation(workspace_root)?;
    if live.as_str() != prepared.expected_workspace_generation {
        return Err(validation(
            "sync_apply_generation_mismatch",
            "workspace generation changed between prepare_sync_apply and commit; refuse apply",
        ));
    }
    if results.len() != prepared.platform_actions.len() {
        return Err(validation(
            "sync_platform_result_count_mismatch",
            "platform result count does not match prepared action count",
        ));
    }
    for (action, result) in prepared.platform_actions.iter().zip(results.iter()) {
        match (action, result) {
            (
                SyncPlatformAction::WriteUserBytes {
                    relative_path,
                    expected_fingerprint,
                    ..
                },
                SyncPlatformActionResult::Applied {
                    relative_path: observed_path,
                    observed_fingerprint,
                },
            ) => {
                if relative_path != observed_path {
                    return Err(validation(
                        "sync_platform_result_mismatch",
                        "platform write result path does not match prepared action",
                    ));
                }
                if expected_fingerprint != observed_fingerprint {
                    return Err(conflict(
                        "sync_platform_fingerprint_mismatch",
                        "platform write observed fingerprint does not match expected",
                    ));
                }
            }
            (
                SyncPlatformAction::DeleteUserBytes { relative_path, .. },
                SyncPlatformActionResult::Absent {
                    relative_path: observed_path,
                },
            ) => {
                if relative_path != observed_path {
                    return Err(validation(
                        "sync_platform_result_mismatch",
                        "platform delete result path does not match prepared action",
                    ));
                }
            }
            (
                _,
                SyncPlatformActionResult::Failed {
                    relative_path,
                    code,
                },
            ) => {
                return Err(storage(
                    "sync_platform_action_failed",
                    &format!("platform action failed for {relative_path}: {code}"),
                ));
            }
            _ => {
                return Err(validation(
                    "sync_platform_result_mismatch",
                    "platform result kind does not match prepared action",
                ));
            }
        }
    }
    Ok(())
}

/// Commits after platform results are verified.
///
/// Memo mutations use the nine-step [`apply_memo_command`] path (same revision fence as user edits).
/// Media mutations verify on-disk state left by the platform executor (no second write authority).
///
/// # Errors
///
/// Generation mismatch, stale revision, storage, or validation from the memo machine.
pub fn commit_sync_apply(
    workspace_root: &Path,
    connection: &Connection,
    gate: WriteGate,
    high_water_revision: &mut u64,
    event_sequence: &mut u64,
    prepared: &PreparedSyncApply,
    platform_results: &[SyncPlatformActionResult],
) -> Result<LocalSyncCommitResult, LomoError> {
    verify_platform_results(workspace_root, prepared, platform_results)?;
    // The platform phase must be staging/conditional. Re-read every expected memo source before
    // touching the store projection so an external SAF/user edit cannot be overwritten by a
    // prepared result. This also makes a replayed final-path result fail closed.
    for mutation in &prepared.commit_mutations.mutations {
        match mutation {
            LocalSyncMutation::UpsertMemo {
                memo_id,
                expected_fingerprint: Some(expected),
                ..
            }
            | LocalSyncMutation::DeleteMemo {
                memo_id,
                expected_fingerprint: Some(expected),
                ..
            } => verify_memo_file_fingerprint(workspace_root, memo_id, expected)?,
            LocalSyncMutation::UpsertMemo { .. }
            | LocalSyncMutation::DeleteMemo { .. }
            | LocalSyncMutation::EnsureMediaPresent { .. }
            | LocalSyncMutation::EnsureMediaAbsent { .. } => {}
        }
    }
    let mut results = Vec::with_capacity(prepared.commit_mutations.mutations.len());
    for mutation in &prepared.commit_mutations.mutations {
        results.push(commit_one_mutation(
            workspace_root,
            connection,
            gate,
            high_water_revision,
            event_sequence,
            mutation,
        )?);
    }
    Ok(LocalSyncCommitResult { results })
}

fn commit_one_mutation(
    workspace_root: &Path,
    connection: &Connection,
    gate: WriteGate,
    high_water_revision: &mut u64,
    event_sequence: &mut u64,
    mutation: &LocalSyncMutation,
) -> Result<LocalSyncMutationResult, LomoError> {
    match mutation {
        LocalSyncMutation::UpsertMemo {
            operation_id,
            memo_id,
            expected_revision,
            expected_fingerprint,
            content,
            tags,
        } => {
            // The live projection, not the caller's revision number, decides whether this is a
            // create or update. An existing memo with expected_revision=0 must surface the
            // ordinary stale_snapshot conflict rather than the weaker "already exists" error.
            let kind = if memo_content_revision(connection, memo_id)?.is_none() {
                MemoCommandKind::Create
            } else {
                MemoCommandKind::Update
            };
            let command = MemoCommand {
                operation_id: OperationId::parse(operation_id)?,
                kind,
                memo_id: memo_id.clone(),
                expected_revision: *expected_revision,
                expected_fingerprint: expected_fingerprint.clone(),
                content: Some(content.clone()),
                tags: tags.clone(),
                pin: None,
                pending_promotes: Vec::new(),
            };
            commit_sync_memo_command(
                workspace_root,
                connection,
                gate,
                high_water_revision,
                event_sequence,
                &command,
            )
        }
        LocalSyncMutation::DeleteMemo {
            operation_id,
            memo_id,
            expected_revision,
            expected_fingerprint,
        } => {
            let command = MemoCommand {
                operation_id: OperationId::parse(operation_id)?,
                kind: MemoCommandKind::Delete,
                memo_id: memo_id.clone(),
                expected_revision: *expected_revision,
                expected_fingerprint: expected_fingerprint.clone(),
                content: None,
                tags: Vec::new(),
                pin: None,
                pending_promotes: Vec::new(),
            };
            commit_sync_memo_command(
                workspace_root,
                connection,
                gate,
                high_water_revision,
                event_sequence,
                &command,
            )
        }
        LocalSyncMutation::EnsureMediaPresent {
            relative_path,
            expected_digest,
            ..
        } => verify_media_present(workspace_root, relative_path, expected_digest),
        LocalSyncMutation::EnsureMediaAbsent { relative_path } => {
            verify_media_absent(workspace_root, relative_path)
        }
    }
}

fn commit_sync_memo_command(
    workspace_root: &Path,
    connection: &Connection,
    gate: WriteGate,
    high_water_revision: &mut u64,
    event_sequence: &mut u64,
    command: &MemoCommand,
) -> Result<LocalSyncMutationResult, LomoError> {
    let commit = apply_memo_command(
        workspace_root,
        connection,
        gate,
        command,
        high_water_revision,
        event_sequence,
        None,
    )?;
    Ok(LocalSyncMutationResult::Memo(commit))
}

fn verify_media_present(
    workspace_root: &Path,
    relative_path: &str,
    expected_digest: &str,
) -> Result<LocalSyncMutationResult, LomoError> {
    reject_path_traversal(relative_path)?;
    let destination = workspace_root.join(relative_path);
    let bytes = std::fs::read(&destination).map_err(|err| {
        storage(
            "sync_media_missing_after_platform",
            &format!(
                "media missing after platform apply {}: {err}",
                destination.display()
            ),
        )
    })?;
    let live = hex_digest(&bytes);
    if live != expected_digest {
        return Err(conflict(
            "sync_media_digest_mismatch_after_platform",
            "media digest after platform apply does not match expected",
        ));
    }
    Ok(LocalSyncMutationResult::MediaEnsured {
        relative_path: relative_path.to_owned(),
        digest: expected_digest.to_owned(),
    })
}

fn verify_media_absent(
    workspace_root: &Path,
    relative_path: &str,
) -> Result<LocalSyncMutationResult, LomoError> {
    reject_path_traversal(relative_path)?;
    if workspace_root.join(relative_path).exists() {
        return Err(conflict(
            "sync_media_still_present_after_platform",
            "media still present after platform delete",
        ));
    }
    Ok(LocalSyncMutationResult::MediaRemoved {
        relative_path: relative_path.to_owned(),
    })
}

/// Direct convenience: prepare → execute platform media writes → synthesize memo results → commit.
///
/// Memo body writes remain owned by the nine-step memo machine (identical to user edits).
/// Media platform actions execute on the Direct filesystem before commit verifies them.
///
/// # Errors
///
/// Propagates prepare / platform / verify / commit failures; stale revision rejects.
pub fn apply_local_sync_batch_direct(
    workspace_root: &Path,
    connection: &Connection,
    gate: WriteGate,
    high_water_revision: &mut u64,
    event_sequence: &mut u64,
    batch: &LocalSyncMutationBatch,
) -> Result<LocalSyncCommitResult, LomoError> {
    let prepared = prepare_sync_apply(workspace_root, batch)?;
    let platform_results = execute_direct_platform_actions(workspace_root, &prepared)?;
    commit_sync_apply(
        workspace_root,
        connection,
        gate,
        high_water_revision,
        event_sequence,
        &prepared,
        &platform_results,
    )
}

/// Executes Direct platform actions: media FS write/delete; memo actions synthesize success
/// because the memo transaction machine owns the Markdown write on commit.
fn execute_direct_platform_actions(
    workspace_root: &Path,
    prepared: &PreparedSyncApply,
) -> Result<Vec<SyncPlatformActionResult>, LomoError> {
    let mut platform_results = Vec::with_capacity(prepared.platform_actions.len());
    for action in &prepared.platform_actions {
        platform_results.push(execute_one_direct_action(workspace_root, action)?);
    }
    Ok(platform_results)
}

fn execute_one_direct_action(
    workspace_root: &Path,
    action: &SyncPlatformAction,
) -> Result<SyncPlatformActionResult, LomoError> {
    match action {
        SyncPlatformAction::WriteUserBytes {
            relative_path,
            expected_fingerprint,
            bytes: _,
        } if is_memo_markdown_path(relative_path) => {
            // Memo body write is owned by apply_memo_command on commit.
            Ok(SyncPlatformActionResult::Applied {
                relative_path: relative_path.clone(),
                observed_fingerprint: expected_fingerprint.clone(),
            })
        }
        SyncPlatformAction::DeleteUserBytes {
            relative_path,
            expected_fingerprint: _,
        } if is_memo_markdown_path(relative_path) => Ok(SyncPlatformActionResult::Absent {
            relative_path: relative_path.clone(),
        }),
        SyncPlatformAction::WriteUserBytes {
            relative_path,
            expected_fingerprint,
            bytes,
        } => {
            reject_path_traversal(relative_path)?;
            let observed = hex_digest(bytes);
            if observed != *expected_fingerprint {
                return Err(conflict(
                    "sync_platform_fingerprint_mismatch",
                    "Direct media write digest mismatch",
                ));
            }
            // Conditional: never unconditional-overwrite concurrent local media with a different
            // digest. Matching digest is idempotent (process-death replay / already-applied).
            let dest = workspace_root.join(relative_path);
            if dest.exists() {
                let live_bytes = std::fs::read(&dest).map_err(|err| {
                    storage(
                        "sync_media_precondition_read_failed",
                        &format!("cannot read existing media {}: {err}", dest.display()),
                    )
                })?;
                let live = hex_digest(&live_bytes);
                if live != *expected_fingerprint {
                    return Err(conflict(
                        "sync_media_precondition_failed",
                        "existing media digest differs from EnsureMediaPresent; refuse overwrite",
                    ));
                }
                return Ok(SyncPlatformActionResult::Applied {
                    relative_path: relative_path.clone(),
                    observed_fingerprint: live,
                });
            }
            write_bytes_atomic(workspace_root, relative_path, bytes)?;
            Ok(SyncPlatformActionResult::Applied {
                relative_path: relative_path.clone(),
                observed_fingerprint: observed,
            })
        }
        SyncPlatformAction::DeleteUserBytes {
            relative_path,
            expected_fingerprint: _,
        } => {
            reject_path_traversal(relative_path)?;
            let dest = workspace_root.join(relative_path);
            if dest.exists() {
                std::fs::remove_file(&dest).map_err(|err| {
                    storage(
                        "sync_media_delete_failed",
                        &format!("cannot delete media {}: {err}", dest.display()),
                    )
                })?;
            }
            Ok(SyncPlatformActionResult::Absent {
                relative_path: relative_path.clone(),
            })
        }
    }
}

fn is_memo_markdown_path(relative_path: &str) -> bool {
    relative_path.starts_with("memos/")
        && Path::new(relative_path)
            .extension()
            .is_some_and(|extension| extension.eq_ignore_ascii_case("md"))
}

fn write_bytes_atomic(
    workspace_root: &Path,
    relative_path: &str,
    bytes: &[u8],
) -> Result<(), LomoError> {
    let dest = workspace_root.join(relative_path);
    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent).map_err(|err| {
            storage(
                "sync_media_dir_create_failed",
                &format!("cannot create media parent {}: {err}", parent.display()),
            )
        })?;
    }
    let temp = dest.with_extension("lomo-sync-tmp");
    std::fs::write(&temp, bytes).map_err(|err| {
        storage(
            "sync_media_write_failed",
            &format!("cannot write media {}: {err}", temp.display()),
        )
    })?;
    std::fs::rename(&temp, &dest).map_err(|err| {
        storage(
            "sync_media_rename_failed",
            &format!("cannot rename media into place {}: {err}", dest.display()),
        )
    })?;
    Ok(())
}

fn reject_path_traversal(relative_path: &str) -> Result<(), LomoError> {
    if relative_path.is_empty()
        || relative_path.starts_with('/')
        || relative_path.starts_with('\\')
        || relative_path
            .split(['/', '\\'])
            .any(|seg| seg == ".." || seg.is_empty())
    {
        return Err(validation(
            "sync_path_traversal",
            "sync media path must be workspace-relative without parent segments",
        ));
    }
    Ok(())
}

fn validate_memo_id_for_sync(memo_id: &str) -> Result<(), LomoError> {
    if memo_id.is_empty()
        || memo_id.len() > 128
        || memo_id.contains('/')
        || memo_id.contains('\\')
        || memo_id == "."
        || memo_id == ".."
    {
        return Err(validation(
            "sync_memo_id_invalid",
            "sync memo id must be a single workspace-relative path segment",
        ));
    }
    Ok(())
}

fn verify_memo_file_fingerprint(
    workspace_root: &Path,
    memo_id: &str,
    expected: &str,
) -> Result<(), LomoError> {
    validate_memo_id_for_sync(memo_id)?;
    let path = workspace_root.join("memos").join(format!("{memo_id}.md"));
    let content = std::fs::read_to_string(&path).map_err(|err| {
        conflict(
            "sync_expected_fingerprint_mismatch",
            &format!(
                "expected memo source {} is not readable: {err}",
                path.display()
            ),
        )
    })?;
    let observed = fingerprint_content(&content);
    if observed != expected {
        return Err(conflict(
            "sync_expected_fingerprint_mismatch",
            "memo source changed after sync snapshot; re-plan before applying",
        ));
    }
    Ok(())
}

fn hex_digest(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    let digest = Sha256::digest(bytes);
    let mut out = String::with_capacity(digest.len() * 2);
    for byte in &digest {
        match write!(out, "{byte:02x}") {
            Ok(()) | Err(_) => {}
        }
    }
    out
}

/// Reads current `content_revision` for a memo (`None` when absent).
///
/// # Errors
///
/// Storage errors from `SQLite`.
pub fn memo_content_revision(
    connection: &Connection,
    memo_id: &str,
) -> Result<Option<u64>, LomoError> {
    let row: Option<i64> = connection
        .query_row(
            "SELECT content_revision FROM memo WHERE memo_id = ?1",
            params![memo_id],
            |row| row.get(0),
        )
        .optional()
        .map_err(|err| from_sqlite(&err))?;
    match row {
        None => Ok(None),
        Some(rev) => Ok(Some(u64::try_from(rev).map_err(|_overflow| {
            validation("invalid_content_revision", "content_revision out of u64")
        })?)),
    }
}

/// Fail-closed helper used by architecture locks: sync must never invent a second write authority.
#[must_use]
pub const fn sync_local_write_authority() -> &'static str {
    "lomo-store expected-revision LocalSyncMutationBatch"
}
