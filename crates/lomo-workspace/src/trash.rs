//! Durable, checksummed soft-delete facts for provider-backed workspaces.
//!
//! A SAF provider is not a transaction log.  The trash record is therefore the source-of-truth
//! fence for a soft delete: it contains the exact recoverable memo snapshot and is written through
//! the same Rust-planned platform-action boundary as Markdown documents.  `SQLite` may project it,
//! but never owns the only copy.

use lomo_core::LomoError;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::Path;

use crate::limits::{MAX_EDITABLE_MEMO_UTF8_CHARS, validation};
use crate::lomo_record::{
    LomoPayload, LomoRecordKind, decode_record, encode_record, hex_encode, read_record,
    write_record_atomic,
};
use crate::reminder::{ReminderRef, ReminderReference};
use crate::source::SourceFingerprint;
use crate::types::{MemoIdentity, WorkspaceRelativePath};

/// Current durable trash-record schema.
pub const TRASH_RECORD_SCHEMA_VERSION: u32 = 1;

/// Relative directory containing provider-backed trash records.
pub const TRASH_RECORD_DIRECTORY: &str = ".lomo/trash/v1";

/// Constructor input for one durable trash snapshot.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TrashRecordCreate {
    pub memo_id: String,
    pub source_path: String,
    pub time_part: String,
    pub source_fingerprint: String,
    pub chronology_epoch_ms: i64,
    pub trashed_at_ms: i64,
    pub body: String,
    pub tags: Vec<String>,
    pub attachments: Vec<String>,
    pub reminders: Vec<ReminderReference>,
    pub has_todo: bool,
    pub has_url: bool,
}

/// Exact recoverable facts for one soft-deleted memo.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct TrashRecordV1 {
    pub schema_version: u32,
    pub memo_id: String,
    pub source_path: String,
    pub time_part: String,
    pub source_fingerprint: String,
    pub chronology_epoch_ms: i64,
    pub trashed_at_ms: i64,
    pub body: String,
    pub tags: Vec<String>,
    pub attachments: Vec<String>,
    pub reminders: Vec<ReminderReference>,
    pub has_todo: bool,
    pub has_url: bool,
}

impl TrashRecordV1 {
    /// Validates and constructs a version-one trash snapshot.
    ///
    /// # Errors
    ///
    /// Returns validation/resource errors for malformed identities, paths, fingerprints, times,
    /// bodies, tags, attachments, or reminder references.
    pub fn try_new(input: TrashRecordCreate) -> Result<Self, LomoError> {
        let record = Self {
            schema_version: TRASH_RECORD_SCHEMA_VERSION,
            memo_id: input.memo_id,
            source_path: input.source_path,
            time_part: input.time_part,
            source_fingerprint: input.source_fingerprint,
            chronology_epoch_ms: input.chronology_epoch_ms,
            trashed_at_ms: input.trashed_at_ms,
            body: input.body,
            tags: input.tags,
            attachments: input.attachments,
            reminders: input.reminders,
            has_todo: input.has_todo,
            has_url: input.has_url,
        };
        record.validate()?;
        Ok(record)
    }

    /// Validates an already decoded record before it can cross into projection logic.
    ///
    /// # Errors
    ///
    /// Returns a validation error when any serialized identity, path, fingerprint, timestamp,
    /// body, tag, attachment, or reminder violates the durable trash-record contract.
    pub fn validate(&self) -> Result<(), LomoError> {
        if self.schema_version != TRASH_RECORD_SCHEMA_VERSION {
            return Err(validation(
                "unknown_trash_record_schema",
                "trash record schema version is unsupported",
            ));
        }
        // Workspace-native documents use the canonical date/time/ordinal identity.  Direct
        // imports may carry an opaque, filename-safe identity (for example a UUID); that is a
        // first-class identity form, not a second trash format.  Both forms remain bounded and
        // are bound to the serialized source filename before the record can be trusted.
        let canonical_identity = classify_memo_identity(&self.memo_id)?;
        let source_path = WorkspaceRelativePath::parse(&self.source_path)?;
        let filename = source_path.as_str().rsplit('/').next().ok_or_else(|| {
            validation(
                "invalid_workspace_path",
                "trash source path has no file name",
            )
        })?;
        let stem = filename.strip_suffix(".md").unwrap_or(filename);
        if let TrashMemoIdentity::Canonical(ref identity) = canonical_identity {
            // SAF day-shards use the date key as the filename; Direct per-memo files use the
            // complete canonical identity. Both are canonical workspace layouts and are bound to
            // the same identity before the record is admitted.
            if stem != identity.date_key() && stem != self.memo_id {
                return Err(validation(
                    "trash_identity_source_mismatch",
                    "trash memo identity does not match its source document",
                ));
            }
            if identity.time_part() != self.time_part {
                return Err(validation(
                    "trash_identity_time_mismatch",
                    "trash memo identity time part does not match its serialized time part",
                ));
            }
        } else if stem != self.memo_id && crate::MemoId::parse(&self.memo_id).is_err() {
            return Err(validation(
                "trash_identity_source_mismatch",
                "opaque trash memo identity must match its source document filename",
            ));
        }
        if self.time_part.is_empty()
            || self.time_part.len() > 64
            || self.time_part.chars().any(char::is_control)
            || self.time_part.contains('/')
            || self.time_part.contains('\\')
        {
            return Err(validation(
                "invalid_trash_time_part",
                "trash memo time part must be a bounded non-path value",
            ));
        }
        SourceFingerprint::parse(&self.source_fingerprint)?;
        if self.chronology_epoch_ms <= 0 {
            return Err(validation(
                "invalid_trash_chronology",
                "trash memo chronology must be a positive epoch millisecond",
            ));
        }
        if self.trashed_at_ms <= 0 {
            return Err(validation(
                "invalid_trash_timestamp",
                "trash timestamp must be a positive epoch millisecond",
            ));
        }
        if self.body.chars().count() > MAX_EDITABLE_MEMO_UTF8_CHARS {
            return Err(validation(
                "trash_body_too_large",
                "trash memo body exceeds the editable memo budget",
            ));
        }
        for tag in &self.tags {
            if tag.is_empty() || tag.len() > 256 || tag.chars().any(char::is_control) {
                return Err(validation(
                    "invalid_trash_tag",
                    "trash memo tag is empty, oversized, or contains controls",
                ));
            }
        }
        for attachment in &self.attachments {
            if attachment.is_empty()
                || attachment.len() > 1_024
                || attachment.chars().any(char::is_control)
            {
                return Err(validation(
                    "invalid_trash_attachment",
                    "trash attachment path is empty, oversized, or contains controls",
                ));
            }
        }
        for reminder in &self.reminders {
            let parsed = ReminderRef::try_from_reference(reminder.clone())?;
            if matches!(canonical_identity, TrashMemoIdentity::Canonical(_))
                && parsed.memo_identity().as_str() != self.memo_id
            {
                return Err(validation(
                    "trash_reminder_memo_mismatch",
                    "trash reminder belongs to a different memo",
                ));
            }
        }
        Ok(())
    }
}

/// Encodes a trash record using the workspace's framed checksum envelope.
///
/// # Errors
///
/// Returns a validation error when the record is invalid or cannot be serialized into the framed
/// checksum envelope.
pub fn encode_trash_record(record: &TrashRecordV1) -> Result<Vec<u8>, LomoError> {
    record.validate()?;
    let body_json = serde_json::to_string(record).map_err(|error| {
        validation(
            "trash_record_encode_failed",
            &format!("cannot encode trash record: {error}"),
        )
    })?;
    encode_record(&LomoPayload {
        kind: LomoRecordKind::Trash,
        record_id: record.memo_id.clone(),
        body_json,
    })
}

/// Decodes and validates one framed trash record.
///
/// # Errors
///
/// Returns a validation or corruption error when the checksum envelope, payload kind, payload
/// identity, JSON body, or record fields are invalid.
pub fn decode_trash_record(bytes: &[u8]) -> Result<TrashRecordV1, LomoError> {
    let record = decode_record(bytes)?;
    if record.payload.kind != LomoRecordKind::Trash {
        return Err(validation(
            "trash_record_kind_mismatch",
            "trash record envelope kind is not Trash",
        ));
    }
    let trash: TrashRecordV1 =
        serde_json::from_str(&record.payload.body_json).map_err(|error| {
            validation(
                "trash_record_payload_invalid",
                &format!("cannot decode trash record payload: {error}"),
            )
        })?;
    if record.payload.record_id != trash.memo_id {
        return Err(validation(
            "trash_record_id_mismatch",
            "trash record envelope id does not match its payload",
        ));
    }
    trash.validate()?;
    Ok(trash)
}

/// Reads one canonical trash record from a durable path.
///
/// The path is supplied by the caller only after it has been resolved through
/// [`trash_record_relative_path`]. This helper still validates the framed kind and payload id so
/// a copied or swapped marker cannot cross the authority boundary.
///
/// # Errors
///
/// Returns an error when reading the record fails, the envelope does not match
/// the trash payload kind or id, or validation fails.
pub fn read_trash_record(path: &Path) -> Result<TrashRecordV1, LomoError> {
    let record = read_record(path)?;
    if record.payload.kind != LomoRecordKind::Trash {
        return Err(validation(
            "trash_record_kind_mismatch",
            "trash record path does not contain a Trash record",
        ));
    }
    let trash: TrashRecordV1 =
        serde_json::from_str(&record.payload.body_json).map_err(|error| {
            validation(
                "trash_record_payload_invalid",
                &format!("cannot decode trash record payload: {error}"),
            )
        })?;
    if record.payload.record_id != trash.memo_id {
        return Err(validation(
            "trash_record_id_mismatch",
            "trash record envelope id does not match its payload",
        ));
    }
    trash.validate()?;
    Ok(trash)
}

/// Atomically writes one canonical trash record to a durable path.
///
/// # Errors
///
/// Returns an error when record validation, JSON serialization, or atomic write fails.
pub fn write_trash_record_atomic(path: &Path, record: &TrashRecordV1) -> Result<(), LomoError> {
    record.validate()?;
    let body_json = serde_json::to_string(record).map_err(|error| {
        validation(
            "trash_record_encode_failed",
            &format!("cannot encode trash record payload: {error}"),
        )
    })?;
    write_record_atomic(
        path,
        &LomoPayload {
            kind: LomoRecordKind::Trash,
            record_id: record.memo_id.clone(),
            body_json,
        },
    )
}

/// Returns the canonical, identity-hiding relative path for one memo's trash record.
///
/// # Errors
///
/// Returns a validation error when the memo identity or resulting workspace-relative path is
/// invalid.
pub fn trash_record_relative_path(memo_id: &str) -> Result<WorkspaceRelativePath, LomoError> {
    classify_memo_identity(memo_id)?;
    let digest = Sha256::digest(memo_id.as_bytes());
    WorkspaceRelativePath::parse(&format!(
        "{TRASH_RECORD_DIRECTORY}/{}.rec",
        hex_encode(&digest)
    ))
}

/// The two admitted identity forms for a durable trash record.
///
/// Parsing failure is intentionally classified here, at the identity boundary, rather than
/// erased with an `Option`: Direct/imported workspaces may carry opaque ids, while malformed ids
/// are still rejected by the opaque validator below.  Keeping this classification explicit makes
/// the exception auditable and prevents callers from accidentally treating an invalid id as a
/// missing value.
#[derive(Clone, Debug, Eq, PartialEq)]
enum TrashMemoIdentity {
    Canonical(MemoIdentity),
    Opaque,
}

fn classify_memo_identity(memo_id: &str) -> Result<TrashMemoIdentity, LomoError> {
    match MemoIdentity::parse(memo_id) {
        Ok(identity) => Ok(TrashMemoIdentity::Canonical(identity)),
        Err(_identity_error) => {
            validate_opaque_identity_if_needed(memo_id, false)?;
            Ok(TrashMemoIdentity::Opaque)
        }
    }
}

/// Validates the opaque identity form used by Direct/imported memo filenames.
///
/// Canonical identities are validated by [`MemoIdentity::parse`].  An opaque identity is still a
/// domain fact, but it must never be allowed to influence a filesystem path or carry controls.
fn validate_opaque_identity_if_needed(memo_id: &str, is_canonical: bool) -> Result<(), LomoError> {
    if is_canonical {
        return Ok(());
    }
    if memo_id.is_empty()
        || memo_id.len() > 512
        || memo_id.chars().any(char::is_control)
        || memo_id.contains('/')
        || memo_id.contains('\\')
        || matches!(memo_id, "." | "..")
    {
        return Err(validation(
            "invalid_memo_identity",
            "opaque memo identity must be a bounded filename-safe value",
        ));
    }
    Ok(())
}
