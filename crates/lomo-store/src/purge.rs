//! Durable permanent-delete tombstones.
//!
//! Soft delete writes `.lomo/trash/v1/<sha256(memo_id)>.rec`; permanent delete removes that record
//! but must leave durable evidence that the memo identity was purged, or a stray/peer-delivered
//! copy of the trash record resurrects the memo on the next rebuild. The tombstone is one framed
//! `.lomo` record per memo under `.lomo/purged/v1/`, hash-addressed exactly like the trash record
//! it retires. Rebuild treats the tombstone set as suppression authority: a purged memo id is
//! never re-projected from durable records, regardless of which physical files reappear.
//!
//! The operation journal cannot be this authority (`cleanup_expired_operations` prunes committed
//! intents), and neither can the SQLite projection (it is rebuilt, not durable truth). The
//! tombstone file is the same durability and sync class as the trash record it supersedes.

use std::collections::BTreeSet;
use std::path::Path;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use lomo_core::LomoError;
use lomo_workspace::WorkspaceRelativePath;

use crate::error::{storage, validation};
use crate::lomo_format::{
    LomoPayload, LomoRecordKind, decode_record, encode_record, read_record, write_record_atomic,
};

/// Durable tombstone directory for permanently deleted memo identities.
pub const PURGE_RECORD_DIRECTORY: &str = ".lomo/purged/v1";

/// One durable permanent-delete tombstone: this memo identity may never re-project.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "PurgeRecordV1Json")]
pub struct PurgeRecordV1 {
    pub memo_id: String,
    /// The operation that committed the purge, for diagnostics and sync evidence.
    pub operation_id: String,
    pub purged_at_ms: i64,
}

#[derive(Deserialize)]
struct PurgeRecordV1Json {
    memo_id: String,
    operation_id: String,
    purged_at_ms: i64,
}

impl TryFrom<PurgeRecordV1Json> for PurgeRecordV1 {
    type Error = LomoError;

    fn try_from(json: PurgeRecordV1Json) -> Result<Self, LomoError> {
        let record = Self {
            memo_id: json.memo_id,
            operation_id: json.operation_id,
            purged_at_ms: json.purged_at_ms,
        };
        record.validate()?;
        Ok(record)
    }
}

impl PurgeRecordV1 {
    /// Validates one tombstone before it can be persisted or projected.
    ///
    /// # Errors
    ///
    /// Validation when the memo or operation identity is empty/oversized/carries controls or
    /// path separators, or the purge timestamp is not a positive epoch millisecond.
    pub fn validate(&self) -> Result<(), LomoError> {
        if self.memo_id.is_empty()
            || self.memo_id.len() > 512
            || self.memo_id.chars().any(char::is_control)
            || self.memo_id.contains('/')
            || self.memo_id.contains('\\')
        {
            return Err(validation(
                "invalid_purge_memo_id",
                "purge tombstone memo identity is empty, oversized, or unsafe",
            ));
        }
        if self.operation_id.is_empty() || self.operation_id.len() > 512 {
            return Err(validation(
                "invalid_purge_operation_id",
                "purge tombstone operation identity is empty or oversized",
            ));
        }
        if self.purged_at_ms <= 0 {
            return Err(validation(
                "invalid_purge_timestamp",
                "purge timestamp must be a positive epoch millisecond",
            ));
        }
        Ok(())
    }
}

/// Returns the canonical, identity-hiding relative path for one memo's purge tombstone.
///
/// The path mirrors the trash-record scheme: the file name is the SHA-256 of the memo
/// identity, so a tombstone can only ever claim the memo it was written for and the on-disk
/// name leaks no user content.
///
/// # Errors
///
/// Returns a validation error when the memo identity or resulting workspace-relative path is
/// invalid.
pub fn purge_record_relative_path(memo_id: &str) -> Result<WorkspaceRelativePath, LomoError> {
    let digest = Sha256::digest(memo_id.as_bytes());
    WorkspaceRelativePath::parse(&format!(
        "{PURGE_RECORD_DIRECTORY}/{}.rec",
        crate::content_facts::hex_encode(&digest)
    ))
}

/// Encodes one tombstone into the workspace's framed checksum envelope.
///
/// Tombstones carry the `Trash` payload kind: they are the terminal record of the trash
/// lifecycle, and no other `LomoRecordKind` admits a memo-scoped suppression fact.
///
/// # Errors
///
/// Validation when the record is invalid or cannot be serialized.
pub fn encode_purge_record(record: &PurgeRecordV1) -> Result<Vec<u8>, LomoError> {
    record.validate()?;
    let body_json = serde_json::to_string(record).map_err(|error| {
        validation(
            "purge_record_encode_failed",
            &format!("cannot encode purge tombstone: {error}"),
        )
    })?;
    encode_record(&LomoPayload {
        kind: LomoRecordKind::Trash,
        record_id: record.memo_id.clone(),
        body_json,
    })
}

/// Decodes and validates one framed tombstone, proving envelope id matches body id.
///
/// # Errors
///
/// Validation/corruption when the checksum envelope, payload kind, payload identity, JSON body,
/// or record fields are invalid.
pub fn decode_purge_record(bytes: &[u8]) -> Result<PurgeRecordV1, LomoError> {
    let record = decode_record(bytes)?;
    if record.payload.kind != LomoRecordKind::Trash {
        return Err(validation(
            "purge_record_kind_mismatch",
            "purge tombstone envelope kind is not Trash",
        ));
    }
    let purge: PurgeRecordV1 =
        serde_json::from_str(&record.payload.body_json).map_err(|error| {
            validation(
                "purge_record_payload_invalid",
                &format!("cannot decode purge tombstone payload: {error}"),
            )
        })?;
    if record.payload.record_id != purge.memo_id {
        return Err(validation(
            "purge_record_id_mismatch",
            "purge tombstone envelope id does not match its payload",
        ));
    }
    Ok(purge)
}

/// Reads and validates one tombstone at a caller-resolved path.
///
/// # Errors
///
/// Storage when the file cannot be read; validation when the record is malformed.
pub fn read_purge_record(path: &Path) -> Result<PurgeRecordV1, LomoError> {
    let record = read_record(path)?;
    if record.payload.kind != LomoRecordKind::Trash {
        return Err(validation(
            "purge_record_kind_mismatch",
            "purge tombstone path does not contain a Trash-kind record",
        ));
    }
    let purge: PurgeRecordV1 =
        serde_json::from_str(&record.payload.body_json).map_err(|error| {
            validation(
                "purge_record_payload_invalid",
                &format!("cannot decode purge tombstone payload: {error}"),
            )
        })?;
    if record.payload.record_id != purge.memo_id {
        return Err(validation(
            "purge_record_id_mismatch",
            "purge tombstone envelope id does not match its payload",
        ));
    }
    Ok(purge)
}

/// Atomically writes one tombstone to a caller-resolved durable path.
///
/// # Errors
///
/// Validation/storage errors.
pub fn write_purge_record_atomic(path: &Path, record: &PurgeRecordV1) -> Result<(), LomoError> {
    write_record_atomic(
        path,
        &LomoPayload {
            kind: LomoRecordKind::Trash,
            record_id: record.memo_id.clone(),
            body_json: serde_json::to_string(record).map_err(|error| {
                validation(
                    "purge_record_encode_failed",
                    &format!("cannot encode purge tombstone: {error}"),
                )
            })?,
        },
    )
}

/// Enumerates every durably purged memo identity in a Direct workspace root.
///
/// Rebuild treats this set as suppression authority. Every `.rec` file must sit at the
/// hash-addressed path its payload claims and decode cleanly — a corrupt or misplaced tombstone
/// fails the rebuild rather than silently admitting resurrection.
///
/// # Errors
///
/// Storage when the directory cannot be listed/read; corruption when a record fails validation
/// or sits at a path that does not hash-bind its memo identity.
pub fn list_purged_memo_ids(workspace_root: &Path) -> Result<BTreeSet<String>, LomoError> {
    let directory = workspace_root.join(PURGE_RECORD_DIRECTORY);
    if !directory.exists() {
        return Ok(BTreeSet::new());
    }
    let mut purged = BTreeSet::new();
    for entry in std::fs::read_dir(&directory).map_err(|error| {
        storage(
            "purge_record_list_failed",
            &format!("cannot list durable purge tombstones: {error}"),
        )
    })? {
        let entry = entry.map_err(|error| {
            storage(
                "purge_record_list_failed",
                &format!("cannot read purge tombstone entry: {error}"),
            )
        })?;
        let path = entry.path();
        if path.extension().and_then(|extension| extension.to_str()) != Some("rec") {
            continue;
        }
        let bytes = std::fs::read(&path).map_err(|error| {
            storage(
                "purge_record_read_failed",
                &format!("cannot read purge tombstone {}: {error}", path.display()),
            )
        })?;
        let record = decode_purge_record(&bytes)?;
        let expected = purge_record_relative_path(&record.memo_id)?;
        if path != workspace_root.join(expected.as_str()) {
            return Err(crate::error::corruption(
                "purge_record_path_mismatch",
                "durable purge tombstone is not stored at its canonical hashed path",
            ));
        }
        purged.insert(record.memo_id);
    }
    Ok(purged)
}
