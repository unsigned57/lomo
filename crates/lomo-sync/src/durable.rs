//! Durable session / baseline / tombstone models under `.lomo/sync/v1` (P5-03 host slice).
//!
//! No `SQLite` authority. Corrupt schema/checksum/size → `CorruptState` (never clean slate).

use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::error::{corrupt_state, resource_limit, storage, validation};
use crate::limits::{MAX_DURABLE_RECORD_BYTES, SYNC_DURABLE_SCHEMA};
use crate::pipeline::{ContentDigest, SyncPath};
use lomo_core::LomoError;
use lomo_workspace::{RemoteDatasetId, RemoteIdentityDigest, WorkspaceGenerationId};

/// Magic for framed sync durable records.
pub const SYNC_RECORD_MAGIC: &[u8; 4] = b"LSYN";

/// Session kind for first-takeover / migration vs incremental.
///
/// Migration and first-takeover are the **migration-class** action set: they never
/// emit user-file deletes (`EnsureAbsent`). Only [`SessionKind::Incremental`] may
/// participate in proven remote delete derivation when all other gates pass.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SessionKind {
    FirstTakeover,
    /// One-shot migration / upgrade / baseline-rebuild class (no user-file deletes).
    Migration,
    Incremental,
}

impl SessionKind {
    /// True when this session may emit user-file remote deletes under hard gates.
    #[must_use]
    pub const fn may_emit_user_file_delete(self) -> bool {
        matches!(self, Self::Incremental)
    }

    /// True for migration / upgrade / first-takeover action class (no user-file deletes).
    #[must_use]
    pub const fn is_migration_or_takeover_class(self) -> bool {
        matches!(self, Self::FirstTakeover | Self::Migration)
    }
}

/// Durable session identity fence.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct SyncIdentityFence {
    pub workspace_generation: String,
    pub remote_dataset_id: String,
    pub remote_identity_digest: String,
}

impl SyncIdentityFence {
    /// Builds a fence from owner identity types.
    #[must_use]
    pub fn from_parts(
        generation: &WorkspaceGenerationId,
        dataset: &RemoteDatasetId,
        identity: &RemoteIdentityDigest,
    ) -> Self {
        Self {
            workspace_generation: generation.as_str().to_owned(),
            remote_dataset_id: dataset.as_str().to_owned(),
            remote_identity_digest: identity.as_str().to_owned(),
        }
    }

    /// Rejects mismatched durable state (never clean-slate).
    ///
    /// # Errors
    ///
    /// Validation when any component mismatches.
    pub fn matches(
        &self,
        generation: &WorkspaceGenerationId,
        dataset: &RemoteDatasetId,
        identity: &RemoteIdentityDigest,
    ) -> Result<(), LomoError> {
        if self.workspace_generation != generation.as_str()
            || self.remote_dataset_id != dataset.as_str()
            || self.remote_identity_digest != identity.as_str()
        {
            return Err(validation(
                "sync_identity_mismatch",
                "durable sync state does not match WorkspaceGenerationId + RemoteDatasetId + RemoteIdentityDigest",
            ));
        }
        Ok(())
    }
}

/// Durable sync session head (pageable; no secrets).
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct SyncSession {
    pub schema: u32,
    pub fence: SyncIdentityFence,
    pub kind: SessionKind,
    pub session_id: String,
    /// Monotonic session generation for conflict/resolution fences.
    pub session_revision: u64,
}

impl SyncSession {
    /// Creates a new session record.
    ///
    /// # Errors
    ///
    /// Validation on empty session id.
    pub fn new(
        fence: SyncIdentityFence,
        kind: SessionKind,
        session_id: impl Into<String>,
    ) -> Result<Self, LomoError> {
        let session_id = session_id.into();
        if session_id.is_empty() || session_id.len() > 128 {
            return Err(validation(
                "invalid_session_id",
                "session id must be 1..=128 bytes",
            ));
        }
        Ok(Self {
            schema: SYNC_DURABLE_SCHEMA,
            fence,
            kind,
            session_id,
            session_revision: 1,
        })
    }
}

/// One baseline path fact (immutable file content; head is atomic).
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct BaselineEntry {
    pub path: String,
    pub digest: String,
    pub remote_token: String,
}

/// Baseline head: map of path → entry (sharded on disk by first digest byte in later packages).
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
pub struct BaselineHead {
    pub schema: u32,
    pub fence: Option<SyncIdentityFence>,
    pub entries: Vec<BaselineEntry>,
}

impl BaselineHead {
    /// Empty baseline with no fence (not yet established).
    #[must_use]
    pub const fn empty() -> Self {
        Self {
            schema: SYNC_DURABLE_SCHEMA,
            fence: None,
            entries: Vec::new(),
        }
    }

    /// Looks up a path.
    #[must_use]
    pub fn get(&self, path: &str) -> Option<&BaselineEntry> {
        self.entries.iter().find(|entry| entry.path == path)
    }

    /// True when baseline has been established for this fence.
    #[must_use]
    pub const fn is_established(&self) -> bool {
        self.fence.is_some()
    }

    /// Inserts or replaces a verified path fact.
    pub fn upsert(&mut self, path: &SyncPath, digest: &ContentDigest, remote_token: String) {
        let path_s = path.as_str().to_owned();
        if let Some(existing) = self.entries.iter_mut().find(|entry| entry.path == path_s) {
            digest.as_str().clone_into(&mut existing.digest);
            existing.remote_token = remote_token;
        } else {
            self.entries.push(BaselineEntry {
                path: path_s,
                digest: digest.as_str().to_owned(),
                remote_token,
            });
        }
    }

    /// Removes a path from baseline (only after verified absence).
    pub fn remove(&mut self, path: &str) {
        self.entries.retain(|entry| entry.path != path);
    }
}

/// Permanent tombstone bound to `RemoteDatasetId`.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct TombstoneEntry {
    pub path: String,
    pub remote_dataset_id: String,
    pub content_digest: String,
}

/// Tombstone set for a dataset.
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
pub struct TombstoneSet {
    pub schema: u32,
    pub entries: Vec<TombstoneEntry>,
}

impl TombstoneSet {
    #[must_use]
    pub const fn empty() -> Self {
        Self {
            schema: SYNC_DURABLE_SCHEMA,
            entries: Vec::new(),
        }
    }

    #[must_use]
    pub fn contains_path(&self, path: &str) -> bool {
        self.entries.iter().any(|entry| entry.path == path)
    }

    /// Looks up a tombstone by workspace-relative path.
    #[must_use]
    pub fn get(&self, path: &str) -> Option<&TombstoneEntry> {
        self.entries.iter().find(|entry| entry.path == path)
    }

    /// True when the path is tombstoned with the given content digest (same-bytes reappear).
    #[must_use]
    pub fn matches_digest(&self, path: &str, digest: &str) -> bool {
        self.get(path)
            .is_some_and(|entry| entry.content_digest == digest)
    }

    /// Inserts or replaces a permanent tombstone for a path under a dataset.
    pub fn upsert(&mut self, path: &str, remote_dataset_id: &str, content_digest: &str) {
        if let Some(existing) = self.entries.iter_mut().find(|entry| entry.path == path) {
            remote_dataset_id.clone_into(&mut existing.remote_dataset_id);
            content_digest.clone_into(&mut existing.content_digest);
        } else {
            self.entries.push(TombstoneEntry {
                path: path.to_owned(),
                remote_dataset_id: remote_dataset_id.to_owned(),
                content_digest: content_digest.to_owned(),
            });
        }
    }
}

/// Layout under a workspace root for sync durable trees.
#[derive(Clone, Debug)]
pub struct SyncPaths {
    pub root: PathBuf,
    pub session: PathBuf,
    pub baseline: PathBuf,
    pub tombstones: PathBuf,
    /// Conflict session head (`conflicts.rec`).
    pub conflicts: PathBuf,
    /// Conflict candidate artifacts (`artifacts/`).
    pub conflict_artifacts: PathBuf,
}

impl SyncPaths {
    /// Resolves `.lomo/sync/v1` roots.
    #[must_use]
    pub fn for_workspace(workspace_root: &Path) -> Self {
        let root = workspace_root.join(".lomo").join("sync").join("v1");
        Self {
            session: root.join("session.rec"),
            baseline: root.join("baseline.rec"),
            tombstones: root.join("tombstones.rec"),
            conflicts: root.join("conflicts.rec"),
            conflict_artifacts: root.join("artifacts"),
            root,
        }
    }

    /// Ensures directories exist.
    ///
    /// # Errors
    ///
    /// Storage when directories cannot be created.
    pub fn ensure_layout(&self) -> Result<(), LomoError> {
        fs::create_dir_all(&self.root).map_err(|err| {
            storage(
                "sync_dir_create_failed",
                &format!("cannot create {}: {err}", self.root.display()),
            )
        })?;
        fs::create_dir_all(&self.conflict_artifacts).map_err(|err| {
            storage(
                "sync_conflict_artifacts_dir_failed",
                &format!("cannot create {}: {err}", self.conflict_artifacts.display()),
            )
        })?;
        Ok(())
    }
}

/// Encodes a JSON body into framed LSYN bytes: magic|schema|len|checksum|payload.
///
/// # Errors
///
/// Validation when serialization or size limits fail.
pub fn encode_sync_record(schema: u32, body_json: &str) -> Result<Vec<u8>, LomoError> {
    let payload_bytes = body_json.as_bytes();
    if payload_bytes.len() > MAX_DURABLE_RECORD_BYTES {
        return Err(resource_limit(
            "sync_record_too_large",
            "sync durable record exceeds the 1 MiB limit",
        ));
    }
    let checksum = Sha256::digest(payload_bytes);
    let len = u32::try_from(payload_bytes.len()).map_err(|_overflow| {
        resource_limit(
            "sync_record_too_large",
            "sync durable record length exceeds u32",
        )
    })?;
    let mut out = Vec::with_capacity(4 + 4 + 4 + 32 + payload_bytes.len());
    out.extend_from_slice(SYNC_RECORD_MAGIC);
    out.extend_from_slice(&schema.to_le_bytes());
    out.extend_from_slice(&len.to_le_bytes());
    out.extend_from_slice(&checksum);
    out.extend_from_slice(payload_bytes);
    Ok(out)
}

/// Decodes framed LSYN bytes fail-closed.
///
/// # Errors
///
/// Corruption codes for magic/schema/checksum/length failures — never clean slate.
pub fn decode_sync_record(bytes: &[u8]) -> Result<(u32, String), LomoError> {
    if bytes.len() < 4 + 4 + 4 + 32 {
        return Err(corrupt_state(
            "sync_record_truncated",
            "sync durable record shorter than envelope header",
        ));
    }
    let magic = bytes
        .get(0..4)
        .ok_or_else(|| corrupt_state("sync_record_truncated", "missing magic"))?;
    if magic != SYNC_RECORD_MAGIC.as_slice() {
        return Err(corrupt_state(
            "sync_bad_magic",
            "sync durable record magic mismatch",
        ));
    }
    let schema_bytes = bytes
        .get(4..8)
        .ok_or_else(|| corrupt_state("sync_record_truncated", "schema bytes unreadable"))?;
    let schema = u32::from_le_bytes(
        schema_bytes
            .try_into()
            .map_err(|_slice| corrupt_state("sync_record_truncated", "schema bytes unreadable"))?,
    );
    if schema != SYNC_DURABLE_SCHEMA {
        return Err(corrupt_state(
            "sync_unknown_schema",
            "unsupported sync durable schema version",
        ));
    }
    let len_bytes = bytes
        .get(8..12)
        .ok_or_else(|| corrupt_state("sync_record_truncated", "length bytes unreadable"))?;
    let len = usize::try_from(u32::from_le_bytes(len_bytes.try_into().map_err(
        |_slice| corrupt_state("sync_record_truncated", "length bytes unreadable"),
    )?))
    .map_err(|_overflow| corrupt_state("sync_record_truncated", "payload length overflow"))?;
    if len > MAX_DURABLE_RECORD_BYTES {
        return Err(corrupt_state(
            "sync_record_too_large",
            "sync durable payload claims size above hard limit",
        ));
    }
    let checksum = bytes
        .get(12..44)
        .ok_or_else(|| corrupt_state("sync_record_truncated", "checksum bytes unreadable"))?;
    let payload_start: usize = 44;
    let payload_end = payload_start
        .checked_add(len)
        .ok_or_else(|| corrupt_state("sync_record_truncated", "payload length overflow"))?;
    if bytes.len() != payload_end {
        return Err(corrupt_state(
            "sync_record_truncated",
            "sync durable record length does not match payload",
        ));
    }
    let payload_bytes = bytes
        .get(payload_start..payload_end)
        .ok_or_else(|| corrupt_state("sync_record_truncated", "payload slice unreadable"))?;
    let actual = Sha256::digest(payload_bytes);
    if &actual[..] != checksum {
        return Err(corrupt_state(
            "sync_checksum_mismatch",
            "sync durable record checksum mismatch",
        ));
    }
    let body = String::from_utf8(payload_bytes.to_vec()).map_err(|_utf8| {
        corrupt_state(
            "sync_payload_not_utf8",
            "sync durable payload is not valid UTF-8",
        )
    })?;
    Ok((schema, body))
}

/// Atomically writes a framed record via temp + fsync + rename.
///
/// # Errors
///
/// Storage / validation errors.
pub fn write_sync_record_atomic(
    path: &Path,
    schema: u32,
    body_json: &str,
) -> Result<(), LomoError> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|err| {
            storage(
                "sync_dir_create_failed",
                &format!("cannot create parent for {}: {err}", path.display()),
            )
        })?;
    }
    let bytes = encode_sync_record(schema, body_json)?;
    let tmp = path.with_extension("tmp");
    {
        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(&tmp)
            .map_err(|err| {
                storage(
                    "sync_temp_open_failed",
                    &format!("cannot open temp {}: {err}", tmp.display()),
                )
            })?;
        file.write_all(&bytes).map_err(|err| {
            storage(
                "sync_temp_write_failed",
                &format!("cannot write temp {}: {err}", tmp.display()),
            )
        })?;
        file.sync_all().map_err(|err| {
            storage(
                "sync_temp_fsync_failed",
                &format!("cannot fsync temp {}: {err}", tmp.display()),
            )
        })?;
    }
    fs::rename(&tmp, path).map_err(|err| {
        storage(
            "sync_rename_failed",
            &format!("cannot rename temp onto {}: {err}", path.display()),
        )
    })?;
    if let Some(parent) = path.parent()
        && let Ok(dir) = File::open(parent)
    {
        // behavior-contract: silent-result-ok: directory fsync is best-effort on supporting platforms.
        drop(dir.sync_all());
    }
    Ok(())
}

/// Reads a framed record from disk.
///
/// # Errors
///
/// Storage when missing; corruption when bytes fail decode.
pub fn read_sync_record(path: &Path) -> Result<(u32, String), LomoError> {
    let bytes = fs::read(path).map_err(|err| {
        storage(
            "sync_record_open_failed",
            &format!("cannot open {}: {err}", path.display()),
        )
    })?;
    if bytes.len() > MAX_DURABLE_RECORD_BYTES + 64 {
        return Err(corrupt_state(
            "sync_record_too_large",
            "sync durable file exceeds hard size limit",
        ));
    }
    decode_sync_record(&bytes)
}

/// Persists a session.
///
/// # Errors
///
/// Encoding / storage errors.
pub fn write_session(paths: &SyncPaths, session: &SyncSession) -> Result<(), LomoError> {
    paths.ensure_layout()?;
    let body = serde_json::to_string(session).map_err(|err| {
        validation(
            "session_encode_failed",
            &format!("cannot serialize session: {err}"),
        )
    })?;
    write_sync_record_atomic(&paths.session, SYNC_DURABLE_SCHEMA, &body)
}

/// Loads a session.
///
/// # Errors
///
/// Storage / corruption / validation.
pub fn read_session(paths: &SyncPaths) -> Result<SyncSession, LomoError> {
    let (_schema, body) = read_sync_record(&paths.session)?;
    let session: SyncSession = serde_json::from_str(&body).map_err(|err| {
        corrupt_state(
            "session_payload_invalid",
            &format!("cannot decode session payload: {err}"),
        )
    })?;
    if session.schema != SYNC_DURABLE_SCHEMA {
        return Err(corrupt_state(
            "sync_unknown_schema",
            "session schema does not match SYNC_DURABLE_SCHEMA",
        ));
    }
    Ok(session)
}

/// Persists baseline head.
///
/// # Errors
///
/// Encoding / storage errors.
pub fn write_baseline(paths: &SyncPaths, baseline: &BaselineHead) -> Result<(), LomoError> {
    paths.ensure_layout()?;
    let body = serde_json::to_string(baseline).map_err(|err| {
        validation(
            "baseline_encode_failed",
            &format!("cannot serialize baseline: {err}"),
        )
    })?;
    write_sync_record_atomic(&paths.baseline, SYNC_DURABLE_SCHEMA, &body)
}

/// Loads baseline head; missing file → empty baseline (not corruption).
///
/// # Errors
///
/// Corruption when present bytes fail decode.
pub fn read_baseline(paths: &SyncPaths) -> Result<BaselineHead, LomoError> {
    if !paths.baseline.exists() {
        return Ok(BaselineHead::empty());
    }
    let (_schema, body) = read_sync_record(&paths.baseline)?;
    let baseline: BaselineHead = serde_json::from_str(&body).map_err(|err| {
        corrupt_state(
            "baseline_payload_invalid",
            &format!("cannot decode baseline payload: {err}"),
        )
    })?;
    if baseline.schema != SYNC_DURABLE_SCHEMA {
        return Err(corrupt_state(
            "sync_unknown_schema",
            "baseline schema does not match SYNC_DURABLE_SCHEMA",
        ));
    }
    Ok(baseline)
}

/// Persists tombstones.
///
/// # Errors
///
/// Encoding / storage errors.
pub fn write_tombstones(paths: &SyncPaths, set: &TombstoneSet) -> Result<(), LomoError> {
    paths.ensure_layout()?;
    let body = serde_json::to_string(set).map_err(|err| {
        validation(
            "tombstone_encode_failed",
            &format!("cannot serialize tombstones: {err}"),
        )
    })?;
    write_sync_record_atomic(&paths.tombstones, SYNC_DURABLE_SCHEMA, &body)
}

/// Loads tombstones; missing → empty.
///
/// # Errors
///
/// Corruption when present bytes fail decode.
pub fn read_tombstones(paths: &SyncPaths) -> Result<TombstoneSet, LomoError> {
    if !paths.tombstones.exists() {
        return Ok(TombstoneSet::empty());
    }
    let (_schema, body) = read_sync_record(&paths.tombstones)?;
    let set: TombstoneSet = serde_json::from_str(&body).map_err(|err| {
        corrupt_state(
            "tombstone_payload_invalid",
            &format!("cannot decode tombstone payload: {err}"),
        )
    })?;
    if set.schema != SYNC_DURABLE_SCHEMA {
        return Err(corrupt_state(
            "sync_unknown_schema",
            "tombstone schema does not match SYNC_DURABLE_SCHEMA",
        ));
    }
    Ok(set)
}
