//! `.lomo/` durable record codec: magic + schema + length + checksum; temp+fsync+rename.

use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::error::{corruption, storage, validation};

/// Magic bytes for every durable `.lomo` record.
pub const LOMO_MAGIC: &[u8; 4] = b"LOMO";

/// Current codec schema for envelope framing.
pub const LOMO_CODEC_SCHEMA: u32 = 1;

/// Layout roots under a workspace.
#[derive(Debug, Clone)]
pub struct LomoPaths {
    pub root: PathBuf,
    pub manifest: PathBuf,
    pub operations: PathBuf,
    pub history: PathBuf,
    pub state: PathBuf,
}

impl LomoPaths {
    #[must_use]
    pub fn for_workspace(workspace_root: &Path) -> Self {
        let root = workspace_root.join(".lomo");
        Self {
            manifest: root.join("manifest.v1"),
            operations: root.join("operations").join("v1"),
            history: root.join("history").join("v1"),
            state: root.join("state").join("v1"),
            root,
        }
    }

    /// Ensures durable directories exist (does not create `SQLite` paths).
    ///
    /// # Errors
    ///
    /// Storage errors when directories cannot be created.
    pub fn ensure_layout(&self) -> Result<(), lomo_core::LomoError> {
        for dir in [&self.root, &self.operations, &self.history, &self.state] {
            fs::create_dir_all(dir).map_err(|err| {
                storage(
                    "lomo_dir_create_failed",
                    &format!("cannot create {}: {err}", dir.display()),
                )
            })?;
        }
        Ok(())
    }
}

/// Kind of durable record payload.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LomoRecordKind {
    Manifest,
    Operation,
    History,
    State,
}

/// High-level durable payload stored inside the framed envelope.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LomoPayload {
    pub kind: LomoRecordKind,
    pub record_id: String,
    pub body_json: String,
}

/// Fully decoded durable record.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LomoRecord {
    pub schema_version: u32,
    pub payload: LomoPayload,
    pub checksum_hex: String,
}

/// Encodes a payload into framed bytes: magic|schema|len|checksum|payload.
///
/// # Errors
///
/// Validation when the payload cannot be serialized or exceeds length limits.
pub fn encode_record(payload: &LomoPayload) -> Result<Vec<u8>, lomo_core::LomoError> {
    let payload_bytes = serde_json::to_vec(payload).map_err(|err| {
        validation(
            "lomo_payload_encode_failed",
            &format!("cannot serialize lomo payload: {err}"),
        )
    })?;
    if payload_bytes.len() > 16 * 1024 * 1024 {
        return Err(validation(
            "lomo_payload_too_large",
            "lomo payload exceeds 16 MiB",
        ));
    }
    let checksum = Sha256::digest(&payload_bytes);
    let len = u32::try_from(payload_bytes.len()).map_err(|_overflow| {
        validation("lomo_payload_too_large", "lomo payload length exceeds u32")
    })?;

    let mut out = Vec::with_capacity(4 + 4 + 4 + 32 + payload_bytes.len());
    out.extend_from_slice(LOMO_MAGIC);
    out.extend_from_slice(&LOMO_CODEC_SCHEMA.to_le_bytes());
    out.extend_from_slice(&len.to_le_bytes());
    out.extend_from_slice(&checksum);
    out.extend_from_slice(&payload_bytes);
    Ok(out)
}

/// Decodes framed bytes fail-closed.
///
/// # Errors
///
/// - `lomo_bad_magic` / `lomo_unknown_schema` / `lomo_checksum_mismatch` / length errors.
///
/// Never auto-deletes durable trees.
pub fn decode_record(bytes: &[u8]) -> Result<LomoRecord, lomo_core::LomoError> {
    if bytes.len() < 4 + 4 + 4 + 32 {
        return Err(corruption(
            "lomo_record_truncated",
            "lomo record shorter than envelope header",
        ));
    }
    let magic = bytes
        .get(0..4)
        .ok_or_else(|| corruption("lomo_record_truncated", "missing magic"))?;
    if magic != LOMO_MAGIC.as_slice() {
        return Err(corruption("lomo_bad_magic", "lomo record magic mismatch"));
    }
    let schema_bytes = bytes
        .get(4..8)
        .ok_or_else(|| corruption("lomo_record_truncated", "schema bytes unreadable"))?;
    let schema = u32::from_le_bytes(
        schema_bytes
            .try_into()
            .map_err(|_slice| corruption("lomo_record_truncated", "schema bytes unreadable"))?,
    );
    if schema != LOMO_CODEC_SCHEMA {
        return Err(corruption(
            "lomo_unknown_schema",
            &format!("unsupported lomo schema version {schema}"),
        ));
    }
    let len_bytes = bytes
        .get(8..12)
        .ok_or_else(|| corruption("lomo_record_truncated", "length bytes unreadable"))?;
    let len = usize::try_from(u32::from_le_bytes(len_bytes.try_into().map_err(
        |_slice| corruption("lomo_record_truncated", "length bytes unreadable"),
    )?))
    .map_err(|_overflow| corruption("lomo_record_truncated", "payload length overflow"))?;
    let checksum = bytes
        .get(12..44)
        .ok_or_else(|| corruption("lomo_record_truncated", "checksum bytes unreadable"))?;
    let payload_start: usize = 44;
    let payload_end = payload_start
        .checked_add(len)
        .ok_or_else(|| corruption("lomo_record_truncated", "payload length overflow"))?;
    if bytes.len() != payload_end {
        return Err(corruption(
            "lomo_record_truncated",
            "lomo record length does not match payload",
        ));
    }
    let payload_bytes = bytes
        .get(payload_start..payload_end)
        .ok_or_else(|| corruption("lomo_record_truncated", "payload slice unreadable"))?;
    let actual = Sha256::digest(payload_bytes);
    if actual.as_slice() != checksum {
        return Err(corruption(
            "lomo_checksum_mismatch",
            "lomo record checksum mismatch",
        ));
    }
    let payload: LomoPayload = serde_json::from_slice(payload_bytes).map_err(|err| {
        corruption(
            "lomo_payload_decode_failed",
            &format!("cannot decode lomo payload: {err}"),
        )
    })?;
    Ok(LomoRecord {
        schema_version: schema,
        payload,
        checksum_hex: hex_encode(checksum),
    })
}

/// Atomically writes a record via temp + fsync + rename.
///
/// # Errors
///
/// Storage errors on I/O failure. Never partially replaces the destination on success path.
pub fn write_record_atomic(path: &Path, payload: &LomoPayload) -> Result<(), lomo_core::LomoError> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|err| {
            storage(
                "lomo_dir_create_failed",
                &format!("cannot create parent for {}: {err}", path.display()),
            )
        })?;
    }
    let bytes = encode_record(payload)?;
    let tmp = path.with_extension("tmp");
    {
        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(&tmp)
            .map_err(|err| {
                storage(
                    "lomo_temp_open_failed",
                    &format!("cannot open temp {}: {err}", tmp.display()),
                )
            })?;
        file.write_all(&bytes).map_err(|err| {
            storage(
                "lomo_temp_write_failed",
                &format!("cannot write temp {}: {err}", tmp.display()),
            )
        })?;
        file.sync_all().map_err(|err| {
            storage(
                "lomo_temp_fsync_failed",
                &format!("cannot fsync temp {}: {err}", tmp.display()),
            )
        })?;
    }
    fs::rename(&tmp, path).map_err(|err| {
        storage(
            "lomo_rename_failed",
            &format!(
                "cannot rename {} -> {}: {err}",
                tmp.display(),
                path.display()
            ),
        )
    })?;
    // Best-effort directory fsync for durability on supporting platforms.
    if let Some(parent) = path.parent()
        && let Ok(dir) = File::open(parent)
    {
        drop(dir.sync_all());
    }
    Ok(())
}

/// Reads and decodes a durable record from disk.
///
/// # Errors
///
/// Propagates decode corruption/validation and storage I/O errors. Does not delete the file.
pub fn read_record(path: &Path) -> Result<LomoRecord, lomo_core::LomoError> {
    let bytes = fs::read(path).map_err(|err| {
        storage(
            "lomo_read_failed",
            &format!("cannot read {}: {err}", path.display()),
        )
    })?;
    decode_record(&bytes)
}

/// Isolates a bad record by renaming it to `*.corrupt` beside the original path.
///
/// # Errors
///
/// Storage errors if rename fails. Never deletes the durable tree.
pub fn isolate_corrupt_record(path: &Path) -> Result<PathBuf, lomo_core::LomoError> {
    let isolated = path.with_extension("corrupt");
    fs::rename(path, &isolated).map_err(|err| {
        storage(
            "lomo_isolate_failed",
            &format!(
                "cannot isolate corrupt record {} -> {}: {err}",
                path.display(),
                isolated.display()
            ),
        )
    })?;
    Ok(isolated)
}

/// Operation intent body (step 2 durable journal).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OperationIntent {
    pub operation_id: String,
    pub command: MemoCommandKind,
    pub memo_id: String,
    pub expected_revision: u64,
    pub expected_fingerprint: Option<String>,
    pub content: Option<String>,
    pub tags: Vec<String>,
    pub pin: Option<bool>,
    pub status: OperationStatus,
    pub content_revision_after: Option<u64>,
    pub file_fingerprint_after: Option<String>,
    /// Durable publish plan: once set, recovery re-applies these exact counters (no double-bump).
    #[serde(default)]
    pub core_revision_after: Option<u64>,
    /// Durable publish plan for the event sequence counter.
    #[serde(default)]
    pub event_sequence_after: Option<u64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MemoCommandKind {
    Create,
    Update,
    Delete,
    Restore,
    Pin,
    Unpin,
    HistoryRestore,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum OperationStatus {
    IntentAppended,
    HistoryAppended,
    FilesCommitted,
    ProjectionCommitted,
    Committed,
}

/// Pin/trash/tag durable state body (`SQLite` projections rehydrate from this).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StateBody {
    pub memo_id: String,
    pub pinned: bool,
    pub trashed: bool,
    pub pinned_at_ms: Option<i64>,
    pub trashed_at_ms: Option<i64>,
    /// Durable tag names for the memo (rebuildable into `tag` / `memo_tag`).
    #[serde(default)]
    pub tags: Vec<String>,
}

/// History full-snapshot body.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HistoryBody {
    pub memo_id: String,
    pub revision: u64,
    pub content: String,
    pub file_fingerprint: String,
}

fn hex_encode(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        match write!(out, "{byte:02x}") {
            Ok(()) | Err(_) => {}
        }
    }
    out
}
