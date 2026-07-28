//! Durable conflict sessions and expected-revision resolution (P5-08 host hermetic).
//!
//! Conflict never silent: both-modified / delete-vs-edit / unproven overlaps become durable
//! sessions. Resolution requires the expected conflict revision fence; stale submissions reject
//! without overwriting newer session state. Binary paths accept only `KeepLocal` / `KeepRemote` /
//! `SkipForNow`. Markdown `MergedBody` is re-parsed through the workspace owner parser + resource
//! limits before acceptance.

use std::fs;
use std::path::Path;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::durable::{
    SyncIdentityFence, SyncPaths, decode_sync_record, encode_sync_record, write_sync_record_atomic,
};
use crate::error::{conflict, corrupt_state, resource_limit, storage, validation};
use crate::limits::{
    MAX_CONFLICT_ARTIFACT_BYTES, MAX_CONFLICT_PAGE_ITEMS, MAX_DURABLE_RECORD_BYTES,
    SYNC_DURABLE_SCHEMA,
};
use crate::pipeline::{
    BatchAtomicity, ContentDigest, PathPublishStatus, PreparedRemoteBatch, ProviderNeutralIntent,
    PublishReceipt, RemoteSnapshot, SyncPath, VerifiedRemoteState, VerifyStatus,
};
use crate::ports::RemoteSyncPort;
use lomo_core::LomoError;
use lomo_workspace::{ResourceBudget, SourceBytes, parse_workspace_document};
use std::collections::BTreeMap;

/// Content kind for resolution rules (Markdown may merge; binary may not).
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ConflictContentKind {
    Markdown,
    Binary,
}

/// One durable conflict path fact (digests + artifact refs; no body bytes in the head record).
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ConflictPathRecord {
    pub path: String,
    pub kind: ConflictContentKind,
    /// SHA-256 of local candidate body when present.
    pub local_digest: Option<String>,
    /// SHA-256 of remote candidate body when present.
    pub remote_digest: Option<String>,
    /// Baseline digest when known (three-way base).
    pub baseline_digest: Option<String>,
    /// Remote revision token at conflict open (opaque; never a secret).
    pub remote_token: Option<String>,
    /// Durable artifact id for local candidate bytes (under conflict artifacts dir).
    pub local_artifact_ref: Option<String>,
    pub remote_artifact_ref: Option<String>,
    pub baseline_artifact_ref: Option<String>,
    /// Status of this path within the session.
    pub status: ConflictPathStatus,
}

/// Per-path conflict lifecycle status.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ConflictPathStatus {
    Open,
    ResolvedKeepLocal,
    ResolvedKeepRemote,
    ResolvedMerged,
    SkippedForNow,
}

/// Durable conflict session head (pageable; monotonic revision fence).
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ConflictSession {
    pub schema: u32,
    pub fence: SyncIdentityFence,
    pub session_id: String,
    /// Monotonic conflict revision; every successful resolution or session mutation increments.
    pub conflict_revision: u64,
    pub paths: Vec<ConflictPathRecord>,
}

impl ConflictSession {
    /// Opens a new conflict session with revision 1.
    ///
    /// # Errors
    ///
    /// Validation on empty session id or oversized path set.
    pub fn open(
        fence: SyncIdentityFence,
        session_id: impl Into<String>,
        paths: Vec<ConflictPathRecord>,
    ) -> Result<Self, LomoError> {
        let session_id = session_id.into();
        if session_id.is_empty() || session_id.len() > 128 {
            return Err(validation(
                "invalid_conflict_session_id",
                "conflict session id must be 1..=128 bytes",
            ));
        }
        if paths.len() > MAX_CONFLICT_PAGE_ITEMS * 16 {
            return Err(resource_limit(
                "conflict_session_too_large",
                "conflict session exceeds the durable path ceiling",
            ));
        }
        Ok(Self {
            schema: SYNC_DURABLE_SCHEMA,
            fence,
            session_id,
            conflict_revision: 1,
            paths,
        })
    }

    /// Counts paths still needing user attention (`Open` only; `SkipForNow` is deferred, not open).
    #[must_use]
    pub fn open_count(&self) -> usize {
        self.paths
            .iter()
            .filter(|path| matches!(path.status, ConflictPathStatus::Open))
            .count()
    }

    /// Returns a page of conflict paths (stable path order).
    ///
    /// # Errors
    ///
    /// Resource-limit when limit is zero or exceeds the page ceiling.
    pub fn page(&self, cursor: usize, limit: usize) -> Result<ConflictPage, LomoError> {
        if limit == 0 || limit > MAX_CONFLICT_PAGE_ITEMS {
            return Err(resource_limit(
                "conflict_page_limit_invalid",
                "conflict page limit must be 1..=100",
            ));
        }
        let slice: Vec<ConflictPathRecord> = self
            .paths
            .iter()
            .skip(cursor)
            .take(limit)
            .cloned()
            .collect();
        let next_cursor = cursor.saturating_add(slice.len());
        let has_more = next_cursor < self.paths.len();
        Ok(ConflictPage {
            session_id: self.session_id.clone(),
            conflict_revision: self.conflict_revision,
            items: slice,
            next_cursor: has_more.then_some(next_cursor),
        })
    }
}

/// Page of conflict path records for Sync Center listing.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ConflictPage {
    pub session_id: String,
    pub conflict_revision: u64,
    pub items: Vec<ConflictPathRecord>,
    pub next_cursor: Option<usize>,
}

/// User-submitted resolution for one conflict path.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ConflictResolution {
    KeepLocal {
        path: String,
    },
    KeepRemote {
        path: String,
    },
    /// Markdown only: merged body text re-parsed via workspace owner before accept.
    MergedBody {
        path: String,
        body: String,
    },
    /// Leave path baseline unchanged; unrelated paths may continue.
    SkipForNow {
        path: String,
    },
}

impl ConflictResolution {
    #[must_use]
    #[expect(
        clippy::missing_const_for_fn,
        reason = "String::as_str is not const on this toolchain; path accessors stay ordinary methods"
    )]
    pub fn path(&self) -> &str {
        match self {
            Self::KeepLocal { path }
            | Self::KeepRemote { path }
            | Self::MergedBody { path, .. }
            | Self::SkipForNow { path } => path.as_str(),
        }
    }
}

/// Outcome of a resolution batch (new revision always returned on success).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ConflictResolveResult {
    pub session: ConflictSession,
    /// Paths that were accepted in this batch.
    pub applied_paths: Vec<String>,
}

/// True when the workspace-relative path is treated as Markdown for merge rules.
#[must_use]
pub fn is_markdown_sync_path(path: &str) -> bool {
    path.as_bytes()
        .windows(3)
        .any(|window| window.eq_ignore_ascii_case(b".md"))
        && path.rsplit(['/', '\\']).next().is_some_and(|name| {
            name.len() > 3
                && name
                    .as_bytes()
                    .get(name.len() - 3..)
                    .is_some_and(|suffix| suffix.eq_ignore_ascii_case(b".md"))
        })
}

/// Builds a conflict path record from planner digests (artifacts may be filled later).
///
/// # Errors
///
/// Validation when path is empty.
pub fn conflict_path_from_open(
    path: &SyncPath,
    local_digest: Option<&ContentDigest>,
    remote_digest: Option<&ContentDigest>,
    baseline_digest: Option<&ContentDigest>,
    remote_token: Option<&str>,
) -> Result<ConflictPathRecord, LomoError> {
    let path_s = path.as_str().to_owned();
    if path_s.is_empty() {
        return Err(validation(
            "invalid_conflict_path",
            "conflict path must be non-empty",
        ));
    }
    let kind = if is_markdown_sync_path(&path_s) {
        ConflictContentKind::Markdown
    } else {
        ConflictContentKind::Binary
    };
    Ok(ConflictPathRecord {
        path: path_s,
        kind,
        local_digest: local_digest.map(|digest| digest.as_str().to_owned()),
        remote_digest: remote_digest.map(|digest| digest.as_str().to_owned()),
        baseline_digest: baseline_digest.map(|digest| digest.as_str().to_owned()),
        remote_token: remote_token.map(str::to_owned),
        local_artifact_ref: None,
        remote_artifact_ref: None,
        baseline_artifact_ref: None,
        status: ConflictPathStatus::Open,
    })
}

/// Persists conflict candidate body bytes under the session artifacts tree.
///
/// # Errors
///
/// Resource-limit / storage / validation.
pub fn write_conflict_artifact(
    paths: &SyncPaths,
    session_id: &str,
    side: &str,
    path: &str,
    bytes: &[u8],
) -> Result<String, LomoError> {
    if bytes.len() > MAX_CONFLICT_ARTIFACT_BYTES {
        return Err(resource_limit(
            "conflict_artifact_too_large",
            "conflict artifact exceeds the 1 MiB host limit",
        ));
    }
    if side != "local" && side != "remote" && side != "baseline" && side != "merged" {
        return Err(validation(
            "invalid_conflict_artifact_side",
            "conflict artifact side must be local|remote|baseline|merged",
        ));
    }
    let digest = format!("{:x}", Sha256::digest(bytes));
    let safe_name = path.replace(['/', '\\'], "_");
    let relative = format!("{session_id}/{side}/{safe_name}-{digest}");
    let full = paths.conflict_artifacts.join(&relative);
    if let Some(parent) = full.parent() {
        fs::create_dir_all(parent).map_err(|err| {
            storage(
                "conflict_artifact_dir_failed",
                &format!("cannot create conflict artifact dir: {err}"),
            )
        })?;
    }
    fs::write(&full, bytes).map_err(|err| {
        storage(
            "conflict_artifact_write_failed",
            &format!("cannot write conflict artifact: {err}"),
        )
    })?;
    Ok(relative)
}

/// Reads a previously written conflict artifact.
///
/// # Errors
///
/// Storage when missing; resource-limit when oversized.
pub fn read_conflict_artifact(paths: &SyncPaths, artifact_ref: &str) -> Result<Vec<u8>, LomoError> {
    if artifact_ref.is_empty()
        || artifact_ref.contains("..")
        || artifact_ref.starts_with('/')
        || artifact_ref.starts_with('\\')
    {
        return Err(validation(
            "invalid_conflict_artifact_ref",
            "conflict artifact ref must be a relative non-traversal path",
        ));
    }
    let full = paths.conflict_artifacts.join(artifact_ref);
    let bytes = fs::read(&full).map_err(|err| {
        storage(
            "conflict_artifact_open_failed",
            &format!("cannot open conflict artifact: {err}"),
        )
    })?;
    if bytes.len() > MAX_CONFLICT_ARTIFACT_BYTES {
        return Err(resource_limit(
            "conflict_artifact_too_large",
            "conflict artifact exceeds the 1 MiB host limit",
        ));
    }
    Ok(bytes)
}

/// Persists a conflict session head.
///
/// # Errors
///
/// Encoding / storage.
pub fn write_conflict_session(
    paths: &SyncPaths,
    session: &ConflictSession,
) -> Result<(), LomoError> {
    paths.ensure_layout()?;
    let body = serde_json::to_string(session).map_err(|err| {
        validation(
            "conflict_session_encode_failed",
            &format!("cannot serialize conflict session: {err}"),
        )
    })?;
    if body.len() > MAX_DURABLE_RECORD_BYTES {
        return Err(resource_limit(
            "conflict_session_too_large",
            "conflict session JSON exceeds durable record limit",
        ));
    }
    write_sync_record_atomic(&paths.conflicts, SYNC_DURABLE_SCHEMA, &body)
}

/// Loads a conflict session; missing file → storage (not empty session).
///
/// # Errors
///
/// Storage when missing; corruption when payload fails decode.
pub fn read_conflict_session(paths: &SyncPaths) -> Result<ConflictSession, LomoError> {
    if !paths.conflicts.exists() {
        return Err(storage(
            "conflict_session_missing",
            "no durable conflict session on disk",
        ));
    }
    let (_schema, body) = crate::durable::read_sync_record(&paths.conflicts)?;
    let session: ConflictSession = serde_json::from_str(&body).map_err(|err| {
        corrupt_state(
            "conflict_session_payload_invalid",
            &format!("cannot decode conflict session payload: {err}"),
        )
    })?;
    if session.schema != SYNC_DURABLE_SCHEMA {
        return Err(corrupt_state(
            "sync_unknown_schema",
            "conflict session schema does not match SYNC_DURABLE_SCHEMA",
        ));
    }
    Ok(session)
}

/// Lists a conflict page from the durable session.
///
/// # Errors
///
/// Session load / page limit errors.
pub fn list_sync_conflicts(
    paths: &SyncPaths,
    cursor: usize,
    limit: usize,
) -> Result<ConflictPage, LomoError> {
    let session = read_conflict_session(paths)?;
    session.page(cursor, limit)
}

/// Re-parses a Markdown merged body through the workspace owner parser + resource budgets.
///
/// # Errors
///
/// Resource-limit / validation / corruption from workspace parse.
pub fn validate_merged_markdown_body(body: &str) -> Result<(), LomoError> {
    ResourceBudget::check_inline_render_bytes(body.len())?;
    ResourceBudget::check_editable_memo_chars(body.chars().count())?;
    let source = SourceBytes::try_from_str(body)?;
    // Filename stem is synthetic: conflict resolution only needs parse/budget success.
    let _document = parse_workspace_document(&source, "conflict_merged")?;
    Ok(())
}

/// Applies resolutions under the expected conflict revision fence.
///
/// Stale expected revision → conflict error; session revision is **not** advanced on stale reject.
/// Successful apply advances revision by one and persists.
///
/// `SkipForNow` marks the path deferred without changing baseline. `KeepLocal` / `KeepRemote` /
/// `MergedBody` mark the path resolved; baseline mutation is the caller's apply step (this function
/// only advances the conflict session fence).
///
/// # Errors
///
/// Stale revision, binary `MergedBody`, missing path, validation, storage.
pub fn resolve_sync_conflicts(
    paths: &SyncPaths,
    expected_revision: u64,
    resolutions: &[ConflictResolution],
) -> Result<ConflictResolveResult, LomoError> {
    let mut session = read_conflict_session(paths)?;
    if session.conflict_revision != expected_revision {
        return Err(conflict(
            "conflict_revision_stale",
            &format!(
                "expected conflict revision {expected_revision} but durable session is at {}",
                session.conflict_revision
            ),
        ));
    }
    if resolutions.is_empty() {
        return Err(validation(
            "conflict_resolution_empty",
            "resolve_sync_conflicts requires at least one resolution",
        ));
    }
    if resolutions.len() > MAX_CONFLICT_PAGE_ITEMS {
        return Err(resource_limit(
            "conflict_resolution_page_too_large",
            "resolution batch exceeds the 100-item conflict page limit",
        ));
    }

    let mut applied = Vec::with_capacity(resolutions.len());
    for resolution in resolutions {
        apply_one_resolution(paths, &mut session, resolution, &mut applied)?;
    }

    session.conflict_revision = session.conflict_revision.checked_add(1).ok_or_else(|| {
        corrupt_state(
            "conflict_revision_overflow",
            "conflict revision counter overflowed",
        )
    })?;
    write_conflict_session(paths, &session)?;
    Ok(ConflictResolveResult {
        session,
        applied_paths: applied,
    })
}

fn apply_one_resolution(
    paths: &SyncPaths,
    session: &mut ConflictSession,
    resolution: &ConflictResolution,
    applied: &mut Vec<String>,
) -> Result<(), LomoError> {
    let path_key = resolution.path().to_owned();
    let record = session
        .paths
        .iter_mut()
        .find(|entry| entry.path == path_key)
        .ok_or_else(|| {
            validation(
                "conflict_path_unknown",
                "resolution path is not part of the durable conflict session",
            )
        })?;
    if !matches!(
        record.status,
        ConflictPathStatus::Open | ConflictPathStatus::SkippedForNow
    ) {
        return Err(validation(
            "conflict_path_already_resolved",
            "conflict path is already resolved and cannot be re-resolved in this session",
        ));
    }

    match resolution {
        ConflictResolution::KeepLocal { .. } => {
            if record.local_digest.is_none() {
                return Err(validation(
                    "conflict_keep_local_missing",
                    "KeepLocal requires a durable local candidate",
                ));
            }
            record.status = ConflictPathStatus::ResolvedKeepLocal;
        }
        ConflictResolution::KeepRemote { .. } => {
            if record.remote_digest.is_none() {
                return Err(validation(
                    "conflict_keep_remote_missing",
                    "KeepRemote requires a durable remote candidate",
                ));
            }
            record.status = ConflictPathStatus::ResolvedKeepRemote;
        }
        ConflictResolution::MergedBody { body, .. } => {
            if !matches!(record.kind, ConflictContentKind::Markdown) {
                return Err(validation(
                    "conflict_merged_body_binary_forbidden",
                    "binary conflict paths only allow KeepLocal, KeepRemote, or SkipForNow",
                ));
            }
            validate_merged_markdown_body(body)?;
            let merged_digest = format!("{:x}", Sha256::digest(body.as_bytes()));
            let artifact = write_conflict_artifact(
                paths,
                &session.session_id,
                "merged",
                &path_key,
                body.as_bytes(),
            )?;
            record.local_digest = Some(merged_digest);
            record.local_artifact_ref = Some(artifact);
            record.status = ConflictPathStatus::ResolvedMerged;
        }
        ConflictResolution::SkipForNow { .. } => {
            record.status = ConflictPathStatus::SkippedForNow;
        }
    }
    applied.push(path_key);
    Ok(())
}

/// Returns true when a `SkipForNow` / still-open path must keep its baseline entry unchanged.
#[must_use]
pub fn baseline_must_hold_for_path(session: &ConflictSession, path: &str) -> bool {
    session.paths.iter().any(|entry| {
        entry.path == path
            && matches!(
                entry.status,
                ConflictPathStatus::Open | ConflictPathStatus::SkippedForNow
            )
    })
}

/// Removes conflict session file (recovery / identity reset). Does not touch user files.
///
/// # Errors
///
/// Storage when delete fails (missing is ok).
pub fn clear_conflict_session(paths: &SyncPaths) -> Result<(), LomoError> {
    if paths.conflicts.exists() {
        fs::remove_file(&paths.conflicts).map_err(|err| {
            storage(
                "conflict_session_remove_failed",
                &format!("cannot remove conflict session: {err}"),
            )
        })?;
    }
    Ok(())
}

/// Decodes raw framed conflict session bytes (for corrupt-state contracts).
///
/// # Errors
///
/// Corruption codes from the durable envelope.
pub fn decode_conflict_session_bytes(bytes: &[u8]) -> Result<ConflictSession, LomoError> {
    let (_schema, body) = decode_sync_record(bytes)?;
    serde_json::from_str(&body).map_err(|err| {
        corrupt_state(
            "conflict_session_payload_invalid",
            &format!("cannot decode conflict session payload: {err}"),
        )
    })
}

/// Encodes a session to framed bytes (test helper surface).
///
/// # Errors
///
/// Encoding errors.
pub fn encode_conflict_session(session: &ConflictSession) -> Result<Vec<u8>, LomoError> {
    let body = serde_json::to_string(session).map_err(|err| {
        validation(
            "conflict_session_encode_failed",
            &format!("cannot serialize conflict session: {err}"),
        )
    })?;
    encode_sync_record(SYNC_DURABLE_SCHEMA, &body)
}

/// Resolves conflict artifact directory for a workspace (test/inspect).
#[must_use]
pub fn conflict_artifacts_dir(workspace_root: &Path) -> std::path::PathBuf {
    SyncPaths::for_workspace(workspace_root).conflict_artifacts
}

/// Candidate body bytes for materializing durable conflict artifacts (local/remote/baseline).
///
/// Bodies are host-supplied by the caller (store/local FS + remote download). When a body is
/// supplied for a side that also has a planner digest, SHA-256(body) must equal that digest
/// (fail-closed). Artifact refs store the verified bytes.
#[derive(Clone, Debug, Default)]
pub struct ConflictBodySource {
    /// path → (local, remote, baseline) optional bodies
    entries: BTreeMap<String, ConflictCandidateBodies>,
}

/// One path's candidate body triple for materialization.
#[derive(Clone, Debug, Default)]
pub struct ConflictCandidateBodies {
    pub local: Option<Vec<u8>>,
    pub remote: Option<Vec<u8>>,
    pub baseline: Option<Vec<u8>>,
}

impl ConflictBodySource {
    /// Empty body map (materialize of any `OpenConflict` will fail closed).
    #[must_use]
    pub const fn empty() -> Self {
        Self {
            entries: BTreeMap::new(),
        }
    }

    /// Builds a body source from `(path, local, remote, baseline)` tuples.
    #[must_use]
    pub fn from_entries<I, S>(entries: I) -> Self
    where
        I: IntoIterator<Item = (S, Option<Vec<u8>>, Option<Vec<u8>>, Option<Vec<u8>>)>,
        S: Into<String>,
    {
        let mut map = BTreeMap::new();
        for (path, local, remote, baseline) in entries {
            map.insert(
                path.into(),
                ConflictCandidateBodies {
                    local,
                    remote,
                    baseline,
                },
            );
        }
        Self { entries: map }
    }

    /// Inserts or replaces bodies for one path.
    pub fn insert(
        &mut self,
        path: impl Into<String>,
        local: Option<Vec<u8>>,
        remote: Option<Vec<u8>>,
        baseline: Option<Vec<u8>>,
    ) {
        self.entries.insert(
            path.into(),
            ConflictCandidateBodies {
                local,
                remote,
                baseline,
            },
        );
    }

    #[must_use]
    pub fn get(&self, path: &str) -> Option<&ConflictCandidateBodies> {
        self.entries.get(path)
    }
}

/// Materializes durable `ConflictSession` + candidate artifacts from planner `OpenConflict` intents.
///
/// Requires candidate bodies for each open path (at least one of local/remote must be present for
/// KeepLocal/KeepRemote later; both digests from the plan are recorded). When body bytes are
/// supplied for a side that also carries a planner digest, SHA-256(body) must equal that digest
/// (fail-closed). Writes session head only when every open path has durable artifact refs where
/// bodies were supplied.
///
/// Returns `Ok(None)` when the batch has no `OpenConflict` intents (no session written).
///
/// # Errors
///
/// Validation when a candidate body is missing or body/digest mismatch; storage/resource-limit.
pub fn materialize_conflicts_from_plan(
    paths: &SyncPaths,
    fence: &SyncIdentityFence,
    session_id: &str,
    batch: &PreparedRemoteBatch,
    remote_snapshot: &RemoteSnapshot,
    bodies: &ConflictBodySource,
) -> Result<Option<ConflictSession>, LomoError> {
    let open_intents: Vec<&ProviderNeutralIntent> = batch
        .intents
        .iter()
        .filter(|intent| matches!(intent, ProviderNeutralIntent::OpenConflict { .. }))
        .collect();
    if open_intents.is_empty() {
        return Ok(None);
    }

    let remote_tokens: BTreeMap<&str, &str> = remote_snapshot
        .entries
        .iter()
        .map(|entry| (entry.path.as_str(), entry.revision_token.as_str()))
        .collect();

    let mut records = Vec::with_capacity(open_intents.len());
    for intent in open_intents {
        let ProviderNeutralIntent::OpenConflict {
            path,
            local_digest,
            remote_digest,
            baseline_digest,
        } = intent
        else {
            continue;
        };
        let path_s = path.as_str();
        let candidate = bodies.get(path_s).ok_or_else(|| {
            validation(
                "conflict_candidate_body_missing",
                "OpenConflict requires durable candidate bodies before the session is open",
            )
        })?;
        // At least one side body is required so KeepLocal/KeepRemote/Merged can proceed later.
        if candidate.local.is_none() && candidate.remote.is_none() {
            return Err(validation(
                "conflict_candidate_body_missing",
                "OpenConflict requires local and/or remote candidate body bytes",
            ));
        }

        if let Some(bytes) = candidate.local.as_deref() {
            assert_body_matches_digest(bytes, local_digest, "local")?;
        }
        if let Some(bytes) = candidate.remote.as_deref() {
            assert_body_matches_digest(bytes, remote_digest, "remote")?;
        }
        if let (Some(bytes), Some(expected)) =
            (candidate.baseline.as_deref(), baseline_digest.as_ref())
        {
            assert_body_matches_digest(bytes, expected, "baseline")?;
        }

        let mut record = conflict_path_from_open(
            path,
            Some(local_digest),
            Some(remote_digest),
            baseline_digest.as_ref(),
            remote_tokens.get(path_s).copied(),
        )?;

        if let Some(bytes) = candidate.local.as_deref() {
            let artifact = write_conflict_artifact(paths, session_id, "local", path_s, bytes)?;
            record.local_artifact_ref = Some(artifact);
        }
        if let Some(bytes) = candidate.remote.as_deref() {
            let artifact = write_conflict_artifact(paths, session_id, "remote", path_s, bytes)?;
            record.remote_artifact_ref = Some(artifact);
        }
        if let Some(bytes) = candidate.baseline.as_deref() {
            let artifact = write_conflict_artifact(paths, session_id, "baseline", path_s, bytes)?;
            record.baseline_artifact_ref = Some(artifact);
        }
        records.push(record);
    }

    let session = ConflictSession::open(fence.clone(), session_id, records)?;
    write_conflict_session(paths, &session)?;
    Ok(Some(session))
}

fn assert_body_matches_digest(
    bytes: &[u8],
    expected: &ContentDigest,
    side: &str,
) -> Result<(), LomoError> {
    let actual = format!("{:x}", Sha256::digest(bytes));
    if actual != expected.as_str() {
        return Err(validation(
            "conflict_candidate_body_digest_mismatch",
            &format!("conflict {side} candidate body SHA-256 does not match the planner digest"),
        ));
    }
    Ok(())
}

/// Filters baseline upsert/remove so open / `SkipForNow` conflict paths never advance.
///
/// When no conflict session is loaded (or the path is not held), returns true (may advance).
#[must_use]
pub fn may_advance_baseline_for_path(
    conflict_session: Option<&ConflictSession>,
    path: &str,
) -> bool {
    conflict_session.is_none_or(|session| !baseline_must_hold_for_path(session, path))
}

/// Outcome of applying resolved conflict paths to remote (`KeepLocal` / Merged) with verify-before-baseline.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ConflictApplyRemoteResult {
    pub batch: PreparedRemoteBatch,
    pub receipt: Option<PublishReceipt>,
    pub verified: Option<VerifiedRemoteState>,
    pub baseline_advanced: bool,
    pub baseline: crate::durable::BaselineHead,
    /// Path → body bytes that were digest-verified from durable conflict artifacts for `EnsurePresent`.
    /// Callers / adapters use this map as the `ObjectSource` for the publish step.
    pub publish_bodies: crate::ports::MapRemoteObjectSource,
}

/// Loads `KeepLocal` / Merged publish bodies from durable conflict artifacts.
///
/// For each resolved path that must `EnsurePresent` on remote:
/// - `local_artifact_ref` (or merged artifact stored as local) must exist;
/// - SHA-256(body) must equal the durable `local_digest`.
///
/// Fail-closed on missing artifact, missing digest, or digest mismatch.
///
/// # Errors
///
/// Validation / storage when the body wire is incomplete or corrupted relative to the session head.
pub fn collect_resolved_present_bodies(
    paths: &SyncPaths,
    session: &ConflictSession,
) -> Result<crate::ports::MapRemoteObjectSource, LomoError> {
    let mut objects = crate::ports::MapRemoteObjectSource::empty();
    for record in &session.paths {
        match record.status {
            ConflictPathStatus::ResolvedKeepLocal | ConflictPathStatus::ResolvedMerged => {
                let digest_hex = record.local_digest.as_deref().ok_or_else(|| {
                    validation(
                        "conflict_apply_local_digest_missing",
                        "KeepLocal/Merged apply requires a durable local digest",
                    )
                })?;
                let artifact_ref = record.local_artifact_ref.as_deref().ok_or_else(|| {
                    validation(
                        "conflict_apply_local_artifact_missing",
                        "KeepLocal/Merged apply requires a durable local_artifact_ref body wire",
                    )
                })?;
                let bytes = read_conflict_artifact(paths, artifact_ref)?;
                let actual = format!("{:x}", Sha256::digest(&bytes));
                if actual != digest_hex {
                    return Err(validation(
                        "conflict_apply_body_digest_mismatch",
                        "KeepLocal/Merged artifact body SHA-256 does not match the session digest",
                    ));
                }
                // Re-parse to reject non-hex session digests before insert.
                ContentDigest::parse(digest_hex)?;
                objects.insert(record.path.clone(), bytes);
            }
            ConflictPathStatus::ResolvedKeepRemote
            | ConflictPathStatus::Open
            | ConflictPathStatus::SkippedForNow => {}
        }
    }
    Ok(objects)
}

/// Applies resolved conflict choices that require **remote** mutation via expected-revision tokens.
///
/// - `ResolvedKeepLocal` / `ResolvedMerged` → load durable `local_artifact_ref` body, require
///   SHA-256(body) == session `local_digest`, then `EnsurePresent` with that digest and expected
///   remote token; verify; then baseline. The verified body map is returned as `publish_bodies` so
///   adapters / `FakeRemotePort::with_objects` can bind an `ObjectSource` to the same bytes.
/// - `ResolvedKeepRemote` → no remote write and **no** baseline advance here. Local store
///   expected-revision apply uses [`collect_resolved_local_pull_mutations`] + host
///   `LocalSyncMutationBatch`, then [`advance_baseline_after_local_pull`]. Session status alone is
///   not applied user-byte state.
/// - `Open` / `SkippedForNow` → excluded; baseline for those paths is held
///   (`baseline_must_hold_for_path` / `may_advance_baseline_for_path`).
///
/// Baseline advances only for verified remote apply paths that are **not** held by open/skip.
///
/// # Errors
///
/// Stale expected revision, missing session, missing artifact / digest mismatch (fail-closed body
/// wire), port/storage errors.
pub fn apply_resolved_conflicts_remote(
    paths: &SyncPaths,
    expected_revision: u64,
    remote: &dyn RemoteSyncPort,
    mut baseline: crate::durable::BaselineHead,
) -> Result<ConflictApplyRemoteResult, LomoError> {
    let session = read_conflict_session(paths)?;
    if session.conflict_revision != expected_revision {
        return Err(conflict(
            "conflict_revision_stale",
            &format!(
                "expected conflict revision {expected_revision} but durable session is at {}",
                session.conflict_revision
            ),
        ));
    }

    // Body wire first: fail closed before any remote publish when KeepLocal/Merged artifacts are
    // missing or digest-mismatched.
    let publish_bodies = collect_resolved_present_bodies(paths, &session)?;

    let mut intents = Vec::new();
    for record in &session.paths {
        match record.status {
            ConflictPathStatus::ResolvedKeepLocal | ConflictPathStatus::ResolvedMerged => {
                let digest_hex = record.local_digest.as_deref().ok_or_else(|| {
                    validation(
                        "conflict_apply_local_digest_missing",
                        "KeepLocal/Merged apply requires a durable local digest",
                    )
                })?;
                let digest = ContentDigest::parse(digest_hex)?;
                let path = SyncPath::parse(&record.path)?;
                // Structural re-check: ObjectSource load must succeed for this intent.
                publish_bodies.load_bytes(&path, &digest)?;
                intents.push(ProviderNeutralIntent::EnsurePresent {
                    path,
                    digest,
                    expected_remote_token: record.remote_token.clone(),
                });
            }
            ConflictPathStatus::ResolvedKeepRemote
            | ConflictPathStatus::Open
            | ConflictPathStatus::SkippedForNow => {}
        }
    }

    if intents.is_empty() {
        return Ok(ConflictApplyRemoteResult {
            batch: PreparedRemoteBatch::new(BatchAtomicity::PerPath, Vec::new())?,
            receipt: None,
            verified: None,
            baseline_advanced: false,
            baseline,
            publish_bodies,
        });
    }

    let batch = PreparedRemoteBatch::new(BatchAtomicity::PerPath, intents)?;
    let receipt = remote.publish(&batch)?;
    let mut verify_paths = Vec::new();
    for (path, status) in &receipt.path_results {
        if matches!(status, PathPublishStatus::Applied { .. }) {
            verify_paths.push(path.clone());
        }
    }
    let verified = if verify_paths.is_empty() {
        VerifiedRemoteState {
            results: Vec::new(),
        }
    } else {
        remote.verify(&verify_paths)?
    };

    let mut baseline_advanced = false;
    if verified.all_verified() {
        for result in &verified.results {
            match result {
                VerifyStatus::Verified {
                    path,
                    digest,
                    remote_token,
                } => {
                    if may_advance_baseline_for_path(Some(&session), path.as_str()) {
                        baseline.upsert(path, digest, remote_token.clone());
                        baseline_advanced = true;
                    }
                }
                VerifyStatus::AbsentVerified { path } => {
                    if may_advance_baseline_for_path(Some(&session), path.as_str()) {
                        baseline.remove(path.as_str());
                        baseline_advanced = true;
                    }
                }
                VerifyStatus::Failed { .. } => {}
            }
        }
        if baseline_advanced {
            crate::durable::write_baseline(paths, &baseline)?;
        }
    }

    Ok(ConflictApplyRemoteResult {
        batch,
        receipt: Some(receipt),
        verified: Some(verified),
        baseline_advanced,
        baseline,
        publish_bodies,
    })
}

/// One resolved path whose **local** workspace must receive candidate body bytes via the store
/// expected-revision port (`LocalSyncMutationBatch` at the host/FFI edge).
///
/// - `ResolvedKeepRemote` → durable remote candidate body (SHA-256 == `remote_digest`)
/// - `ResolvedMerged` → durable merged body stored as `local_artifact_ref` (SHA-256 == `local_digest`)
///
/// This type is intentionally store-agnostic: `lomo-sync` never opens `SQLite` or writes user
/// Markdown; the host maps `path` + `body` into `lomo-store::LocalSyncMutationBatch`.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ResolvedLocalPullMutation {
    pub path: String,
    pub body: Vec<u8>,
    pub content_digest: String,
    /// Opaque remote revision token at conflict open (for baseline after local apply).
    pub remote_token: Option<String>,
    pub status: ConflictPathStatus,
}

/// Loads `KeepRemote` / Merged bodies that must be applied to the **local** workspace.
///
/// Fail-closed on missing artifact, missing digest, or digest mismatch. Does not mutate store,
/// baseline, or remote.
///
/// # Errors
///
/// Validation / storage when the body wire is incomplete or corrupted relative to the session head.
pub fn collect_resolved_local_pull_mutations(
    paths: &SyncPaths,
    session: &ConflictSession,
) -> Result<Vec<ResolvedLocalPullMutation>, LomoError> {
    let mut out = Vec::new();
    for record in &session.paths {
        match record.status {
            ConflictPathStatus::ResolvedKeepRemote => {
                let digest_hex = record.remote_digest.as_deref().ok_or_else(|| {
                    validation(
                        "conflict_apply_remote_digest_missing",
                        "KeepRemote local apply requires a durable remote digest",
                    )
                })?;
                let artifact_ref = record.remote_artifact_ref.as_deref().ok_or_else(|| {
                    validation(
                        "conflict_apply_remote_artifact_missing",
                        "KeepRemote local apply requires a durable remote_artifact_ref body wire",
                    )
                })?;
                let bytes = read_conflict_artifact(paths, artifact_ref)?;
                let actual = format!("{:x}", Sha256::digest(&bytes));
                if actual != digest_hex {
                    return Err(validation(
                        "conflict_apply_body_digest_mismatch",
                        "KeepRemote artifact body SHA-256 does not match the session remote digest",
                    ));
                }
                ContentDigest::parse(digest_hex)?;
                out.push(ResolvedLocalPullMutation {
                    path: record.path.clone(),
                    body: bytes,
                    content_digest: digest_hex.to_owned(),
                    remote_token: record.remote_token.clone(),
                    status: ConflictPathStatus::ResolvedKeepRemote,
                });
            }
            ConflictPathStatus::ResolvedMerged => {
                // Merged body was re-parsed and stored as local_artifact_ref at resolve time.
                let digest_hex = record.local_digest.as_deref().ok_or_else(|| {
                    validation(
                        "conflict_apply_local_digest_missing",
                        "Merged local apply requires a durable merged (local) digest",
                    )
                })?;
                let artifact_ref = record.local_artifact_ref.as_deref().ok_or_else(|| {
                    validation(
                        "conflict_apply_local_artifact_missing",
                        "Merged local apply requires a durable local_artifact_ref body wire",
                    )
                })?;
                let bytes = read_conflict_artifact(paths, artifact_ref)?;
                let actual = format!("{:x}", Sha256::digest(&bytes));
                if actual != digest_hex {
                    return Err(validation(
                        "conflict_apply_body_digest_mismatch",
                        "Merged artifact body SHA-256 does not match the session local digest",
                    ));
                }
                ContentDigest::parse(digest_hex)?;
                out.push(ResolvedLocalPullMutation {
                    path: record.path.clone(),
                    body: bytes,
                    content_digest: digest_hex.to_owned(),
                    remote_token: record.remote_token.clone(),
                    status: ConflictPathStatus::ResolvedMerged,
                });
            }
            ConflictPathStatus::ResolvedKeepLocal
            | ConflictPathStatus::Open
            | ConflictPathStatus::SkippedForNow => {}
        }
    }
    Ok(out)
}

/// Advances baseline for `KeepRemote` / Merged paths **after** the host has committed local
/// expected-revision mutations with the digest-coupled bodies from
/// [`collect_resolved_local_pull_mutations`].
///
/// Does not write user files. Requires the durable conflict session to still match
/// `expected_revision`. Each applied path must be `KeepRemote` or Merged in the session, and
/// `content_digest` must match the session side digest. Baseline upsert uses the session remote
/// token when present.
///
/// # Errors
///
/// Stale revision, path/status/digest mismatch, or baseline write failure.
pub fn advance_baseline_after_local_pull(
    paths: &SyncPaths,
    expected_revision: u64,
    mut baseline: crate::durable::BaselineHead,
    applied: &[ResolvedLocalPullMutation],
) -> Result<crate::durable::BaselineHead, LomoError> {
    let session = read_conflict_session(paths)?;
    if session.conflict_revision != expected_revision {
        return Err(conflict(
            "conflict_revision_stale",
            &format!(
                "expected conflict revision {expected_revision} but durable session is at {}",
                session.conflict_revision
            ),
        ));
    }
    for mutation in applied {
        let record = session
            .paths
            .iter()
            .find(|entry| entry.path == mutation.path)
            .ok_or_else(|| {
                validation(
                    "conflict_local_pull_path_unknown",
                    "local pull path is not part of the durable conflict session",
                )
            })?;
        match record.status {
            ConflictPathStatus::ResolvedKeepRemote => {
                if record.remote_digest.as_deref() != Some(mutation.content_digest.as_str()) {
                    return Err(validation(
                        "conflict_local_pull_digest_mismatch",
                        "local pull digest does not match durable remote digest for KeepRemote",
                    ));
                }
            }
            ConflictPathStatus::ResolvedMerged => {
                if record.local_digest.as_deref() != Some(mutation.content_digest.as_str()) {
                    return Err(validation(
                        "conflict_local_pull_digest_mismatch",
                        "local pull digest does not match durable merged digest",
                    ));
                }
            }
            ConflictPathStatus::ResolvedKeepLocal
            | ConflictPathStatus::Open
            | ConflictPathStatus::SkippedForNow => {
                return Err(validation(
                    "conflict_local_pull_status_invalid",
                    "local pull baseline advance only applies to KeepRemote or Merged paths",
                ));
            }
        }
        if !may_advance_baseline_for_path(Some(&session), mutation.path.as_str()) {
            return Err(validation(
                "conflict_local_pull_baseline_held",
                "local pull path is held by open/skip and cannot advance baseline",
            ));
        }
        let digest = ContentDigest::parse(&mutation.content_digest)?;
        let path = SyncPath::parse(&mutation.path)?;
        let Some(token) = mutation
            .remote_token
            .clone()
            .or_else(|| record.remote_token.clone())
        else {
            return Err(validation(
                "conflict_local_pull_remote_token_missing",
                "local pull baseline advance requires a durable remote token for the path",
            ));
        };
        baseline.upsert(&path, &digest, token);
    }
    if !applied.is_empty() {
        crate::durable::write_baseline(paths, &baseline)?;
    }
    Ok(baseline)
}
