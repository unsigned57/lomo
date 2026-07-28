//! Generic `.lomo` durable record codec and layout roots (stage-5 P5-01 owner: `lomo-workspace`).
//!
//! Owns magic + schema + length + checksum framing and temp+fsync+rename atomic writes.
//! Transaction / projection semantics remain in `lomo-store`.

use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::limits::{corruption, storage, validation};

/// Magic bytes for every durable `.lomo` record.
pub const LOMO_MAGIC: &[u8; 4] = b"LOMO";

/// Current codec schema for envelope framing.
pub const LOMO_CODEC_SCHEMA: u32 = 1;

/// Maximum payload size accepted by the codec (16 MiB).
pub const LOMO_MAX_PAYLOAD_BYTES: usize = 16 * 1024 * 1024;

/// Active durable layout version for history/state trees.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LomoLayoutVersion {
    /// Pre-P5-01 mutable single-file state + `memoId-rN` history.
    V1,
    /// Content-addressed history/state revision DAG (sync-safe).
    V2,
}

/// Layout roots under a workspace.
#[derive(Debug, Clone)]
pub struct LomoPaths {
    pub root: PathBuf,
    pub manifest: PathBuf,
    pub operations: PathBuf,
    pub history: PathBuf,
    pub state: PathBuf,
    /// App-local identity (never synced / never archived).
    pub local: PathBuf,
    /// Active history/state layout after migration head is read.
    pub layout: LomoLayoutVersion,
}

impl LomoPaths {
    /// Resolves layout paths for `workspace_root`, reading the migration head when present.
    #[must_use]
    pub fn for_workspace(workspace_root: &Path) -> Self {
        let layout = read_layout_version(workspace_root).unwrap_or(LomoLayoutVersion::V1);
        Self::for_workspace_with_layout(workspace_root, layout)
    }

    /// Resolves layout paths for an explicit layout version (tests / migration staging).
    #[must_use]
    pub fn for_workspace_with_layout(workspace_root: &Path, layout: LomoLayoutVersion) -> Self {
        let root = workspace_root.join(".lomo");
        let (history_seg, state_seg) = match layout {
            LomoLayoutVersion::V1 => ("v1", "v1"),
            LomoLayoutVersion::V2 => ("v2", "v2"),
        };
        Self {
            manifest: root.join("manifest.v1"),
            operations: root.join("operations").join("v1"),
            history: root.join("history").join(history_seg),
            state: root.join("state").join(state_seg),
            local: root.join("local").join("v1"),
            root,
            layout,
        }
    }

    /// Absolute path of the durable layout head record.
    #[must_use]
    pub fn layout_head_path(workspace_root: &Path) -> PathBuf {
        workspace_root.join(".lomo").join("layout_head.rec")
    }

    /// Absolute path of the durable workspace generation record.
    #[must_use]
    pub fn generation_record_path(workspace_root: &Path) -> PathBuf {
        workspace_root
            .join(".lomo")
            .join("local")
            .join("v1")
            .join("generation.rec")
    }

    /// Ensures durable directories for the active layout exist (does not create `SQLite` paths).
    ///
    /// # Errors
    ///
    /// Storage errors when directories cannot be created.
    pub fn ensure_layout(&self) -> Result<(), lomo_core::LomoError> {
        for dir in [
            &self.root,
            &self.operations,
            &self.history,
            &self.state,
            &self.local,
        ] {
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
    /// Durable workspace generation identity (local only).
    Generation,
    /// Layout head (v1 vs v2 history/state trees).
    LayoutHead,
    /// Permanent prune tombstone for a history revision.
    HistoryTombstone,
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
    if payload_bytes.len() > LOMO_MAX_PAYLOAD_BYTES {
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

/// Writes the layout head to V2 only after a successful migration validation.
///
/// # Errors
///
/// Storage/encode failures. Never deletes user Markdown/media.
pub fn write_layout_head_v2(workspace_root: &Path) -> Result<(), lomo_core::LomoError> {
    let path = LomoPaths::layout_head_path(workspace_root);
    let body = LayoutHeadBody {
        layout: LomoLayoutVersion::V2,
    };
    let body_json = serde_json::to_string(&body).map_err(|err| {
        validation(
            "layout_head_encode_failed",
            &format!("cannot encode layout head: {err}"),
        )
    })?;
    write_record_atomic(
        &path,
        &LomoPayload {
            kind: LomoRecordKind::LayoutHead,
            record_id: "layout".into(),
            body_json,
        },
    )
}

fn read_layout_version(workspace_root: &Path) -> Result<LomoLayoutVersion, lomo_core::LomoError> {
    let path = LomoPaths::layout_head_path(workspace_root);
    if !path.exists() {
        return Ok(LomoLayoutVersion::V1);
    }
    let record = read_record(&path)?;
    if record.payload.kind != LomoRecordKind::LayoutHead {
        return Err(corruption(
            "layout_head_kind_mismatch",
            "layout head path does not contain a LayoutHead record",
        ));
    }
    let body: LayoutHeadBody = serde_json::from_str(&record.payload.body_json).map_err(|err| {
        corruption(
            "layout_head_payload_invalid",
            &format!("cannot decode layout head: {err}"),
        )
    })?;
    Ok(body.layout)
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
struct LayoutHeadBody {
    layout: LomoLayoutVersion,
}

/// Hex-encodes bytes (lowercase).
#[must_use]
pub fn hex_encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(nibble_hex(byte >> 4));
        out.push(nibble_hex(byte & 0x0f));
    }
    out
}

const fn nibble_hex(nibble: u8) -> char {
    match nibble {
        0 => '0',
        1 => '1',
        2 => '2',
        3 => '3',
        4 => '4',
        5 => '5',
        6 => '6',
        7 => '7',
        8 => '8',
        9 => '9',
        10 => 'a',
        11 => 'b',
        12 => 'c',
        13 => 'd',
        14 => 'e',
        // Only 0..=15 are passed; any other value is treated as 'f' for total function.
        _ => 'f',
    }
}
