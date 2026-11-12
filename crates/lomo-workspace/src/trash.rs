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

use crate::limits::{MAX_EDITABLE_MEMO_UTF8_CHARS, corruption, storage, validation};
use crate::lomo_record::{LOMO_MAX_PAYLOAD_BYTES, write_bytes_atomic};

use crate::reminder::{ReminderRef, ReminderReference};
use crate::source::SourceFingerprint;
use crate::types::{MemoIdentity, WorkspaceRelativePath};

/// Current durable trash-record schema.
pub const TRASH_RECORD_SCHEMA_VERSION: u32 = 1;

/// Relative directory containing provider-backed trash records.
pub const TRASH_RECORD_DIRECTORY: &str = crate::layout::relative::TRASH_V1;

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

/// A readable Markdown body with a checked recovery header. The header carries metadata only;
/// the memo body occurs exactly once and remains directly readable by any Markdown tool.
#[derive(Serialize, Deserialize)]
struct TrashMarkdownHeader {
    facts: serde_json::Value,
    checksum: String,
}

const MARKDOWN_HEADER: &str = "<!-- lomo-trash-v1 ";
const MARKDOWN_BODY: &str = " -->\n\n";

/// Encodes one recoverable memo as Markdown with a checksummed recovery header.
///
/// # Errors
/// Rejects invalid or oversized facts before publication.
pub fn encode_trash_record(record: &TrashRecordV1) -> Result<Vec<u8>, LomoError> {
    record.validate()?;
    let serialized = serde_json::to_vec(record)
        .map_err(|error| validation("trash_record_encode_failed", &error.to_string()))?;
    let mut facts = serde_json::to_value(record)
        .map_err(|error| validation("trash_record_encode_failed", &error.to_string()))?;
    facts
        .as_object_mut()
        .ok_or_else(|| {
            validation(
                "trash_record_encode_failed",
                "trash facts require an object",
            )
        })?
        .remove("body");
    let header = serde_json::to_string(&TrashMarkdownHeader {
        facts,
        checksum: SourceFingerprint::of_bytes(&serialized).as_str().to_owned(),
    })
    .map_err(|error| validation("trash_record_encode_failed", &error.to_string()))?;
    // HTML comments must not be terminated by a user-supplied metadata string.
    let header = header.replace('<', "\\u003c").replace('>', "\\u003e");
    let markdown = format!("{MARKDOWN_HEADER}{header}{MARKDOWN_BODY}{}", record.body);
    if markdown.len() > LOMO_MAX_PAYLOAD_BYTES {
        return Err(validation(
            "trash_record_too_large",
            "trash Markdown exceeds its byte budget",
        ));
    }
    Ok(markdown.into_bytes())
}

/// Decodes a complete trash Markdown document, rejecting corrupt metadata or body bytes.
///
/// # Errors
/// Malformed headers, schema, checksum and invalid domain facts remain observable failures.
pub fn decode_trash_record(bytes: &[u8]) -> Result<TrashRecordV1, LomoError> {
    if bytes.len() > LOMO_MAX_PAYLOAD_BYTES {
        return Err(validation(
            "trash_record_too_large",
            "trash Markdown exceeds its byte budget",
        ));
    }
    let markdown = std::str::from_utf8(bytes)
        .map_err(|error| corruption("trash_record_payload_invalid", &error.to_string()))?;
    let (header, body) = markdown
        .strip_prefix(MARKDOWN_HEADER)
        .and_then(|text| text.split_once(MARKDOWN_BODY))
        .ok_or_else(|| {
            corruption(
                "trash_record_payload_invalid",
                "trash Markdown requires its recovery header",
            )
        })?;
    let mut header: TrashMarkdownHeader = serde_json::from_str(header)
        .map_err(|error| corruption("trash_record_payload_invalid", &error.to_string()))?;
    let facts = header.facts.as_object_mut().ok_or_else(|| {
        corruption(
            "trash_record_payload_invalid",
            "trash metadata must be an object",
        )
    })?;
    if facts.contains_key("body") {
        return Err(corruption(
            "trash_record_payload_invalid",
            "trash metadata cannot contain a second body",
        ));
    }
    facts.insert(
        "body".to_owned(),
        serde_json::Value::String(body.to_owned()),
    );
    let trash: TrashRecordV1 = serde_json::from_value(header.facts)
        .map_err(|error| corruption("trash_record_payload_invalid", &error.to_string()))?;
    let serialized = serde_json::to_vec(&trash)
        .map_err(|error| validation("trash_record_encode_failed", &error.to_string()))?;
    if SourceFingerprint::of_bytes(&serialized).as_str() != header.checksum {
        return Err(corruption(
            "lomo_checksum_mismatch",
            "trash Markdown checksum does not match",
        ));
    }
    trash.validate()?;
    Ok(trash)
}

/// Reads a bounded trash Markdown document.
///
/// # Errors
/// Propagates storage, decode and checksum failures.
pub fn read_trash_record(path: &Path) -> Result<TrashRecordV1, LomoError> {
    use std::io::Read;
    let file = std::fs::File::open(path)
        .map_err(|error| storage("trash_read_failed", &error.to_string()))?;
    let mut bytes = Vec::new();
    file.take(LOMO_MAX_PAYLOAD_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| storage("trash_read_failed", &error.to_string()))?;
    decode_trash_record(&bytes)
}

/// Atomically writes the recoverable Markdown document.
///
/// # Errors
/// Propagates validation, encode and durable publication failures.
pub fn write_trash_record_atomic(path: &Path, record: &TrashRecordV1) -> Result<(), LomoError> {
    write_bytes_atomic(path, &encode_trash_record(record)?)
}

/// Returns the canonical Markdown path under `.trash`, prefixed by the original document name.
///
/// # Errors
/// Rejects invalid memo identities and source paths before path construction.
pub fn trash_record_relative_path(
    memo_id: &str,
    source_path: &str,
) -> Result<WorkspaceRelativePath, LomoError> {
    classify_memo_identity(memo_id)?;
    let source = WorkspaceRelativePath::parse(source_path)?;
    let filename =
        source.as_str().rsplit('/').next().ok_or_else(|| {
            validation("invalid_trash_source", "trash source requires a filename")
        })?;
    let stem = filename.strip_suffix(".md").unwrap_or(filename);
    // The label is for people; the digest owns uniqueness. Bound even a long imported filename
    // below filesystem component limits (32 Unicode scalars occupy at most 128 UTF-8 bytes).
    let label: String = stem.chars().take(32).collect();
    let digest = Sha256::digest(memo_id.as_bytes());
    WorkspaceRelativePath::parse(&format!(
        "{TRASH_RECORD_DIRECTORY}/{label}-{}.md",
        lomo_core::hex_encode(&digest)
    ))
}

/// Whether a path is recovery Markdown (including earlier unlabelled record names).
#[must_use]
pub fn is_trash_record_path(path: &str) -> bool {
    path.strip_prefix(crate::layout::relative::TRASH_PREFIX)
        .and_then(|name| name.strip_suffix(".md"))
        .is_some_and(|stem| {
            let digest = stem.rsplit_once('-').map_or(stem, |(_, digest)| digest);
            !stem.contains('/')
                && digest.len() == 64
                && digest
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        })
}

/// Matches an identity suffix when the source document is no longer projected (e.g. after purge).
/// Record readers additionally validate the complete path against the checksummed source facts.
#[must_use]
pub fn trash_record_path_matches_identity(path: &str, memo_id: &str) -> bool {
    let digest = Sha256::digest(memo_id.as_bytes());
    is_trash_record_path(path) && path.ends_with(&format!("-{}.md", lomo_core::hex_encode(&digest)))
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
