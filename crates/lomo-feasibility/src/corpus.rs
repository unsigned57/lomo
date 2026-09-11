use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::report::ReportValidationError;

/// Versioned phase-0 corpus manifest.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct CorpusManifestV1 {
    pub schema_version: u32,
    pub corpus_version: String,
    pub seed: u64,
    pub workload: CorpusWorkloadV1,
    pub files: Vec<CorpusFileEntryV1>,
    pub logical_attachments: Vec<LogicalAttachmentEntryV1>,
}

/// Workload counters that identify the synthetic corpus scale.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct CorpusWorkloadV1 {
    pub memo_count: u64,
    pub remote_change_count: u64,
    pub attachment_logical_bytes: u64,
}

/// One material file entry with a content digest.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct CorpusFileEntryV1 {
    pub relative_path: String,
    pub sha256: String,
    pub byte_length: u64,
}

/// One logical attachment that may be sparse/streamed rather than stored as full bytes.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct LogicalAttachmentEntryV1 {
    pub identity: String,
    pub logical_bytes: u64,
    pub sha256: String,
}

impl CorpusManifestV1 {
    pub const SCHEMA_VERSION: u32 = 1;

    /// Validate structural completeness for a phase-0 corpus manifest.
    ///
    /// # Errors
    ///
    /// Returns [`ReportValidationError`] when required fields are missing or invalid.
    pub fn validate(&self) -> Result<(), ReportValidationError> {
        if self.schema_version != Self::SCHEMA_VERSION {
            return Err(ReportValidationError::UnsupportedSchema {
                found: self.schema_version,
                expected: Self::SCHEMA_VERSION,
            });
        }
        if self.corpus_version.trim().is_empty() {
            return Err(ReportValidationError::MissingField {
                field: "corpus_version",
            });
        }
        if self
            .files
            .iter()
            .any(|file| file.relative_path.trim().is_empty())
        {
            return Err(ReportValidationError::MissingField {
                field: "files.relative_path",
            });
        }
        if self.files.iter().any(|file| !is_hex_sha256(&file.sha256)) {
            return Err(ReportValidationError::InvalidField {
                field: "files.sha256",
            });
        }
        if self
            .logical_attachments
            .iter()
            .any(|entry| entry.identity.trim().is_empty() || !is_hex_sha256(&entry.sha256))
        {
            return Err(ReportValidationError::InvalidField {
                field: "logical_attachments",
            });
        }
        if self
            .files
            .iter()
            .any(|file| file.relative_path.contains("..") || looks_absolute(&file.relative_path))
        {
            return Err(ReportValidationError::InvalidField {
                field: "files.relative_path",
            });
        }
        Ok(())
    }

    /// Serialize to deterministic JSON bytes after sorting entries.
    ///
    /// # Errors
    ///
    /// Returns [`ReportValidationError`] when validation or serialization fails.
    pub fn to_canonical_json(&self) -> Result<Vec<u8>, ReportValidationError> {
        self.validate()?;
        let mut normalized = self.clone();
        normalized
            .files
            .sort_by(|left, right| left.relative_path.cmp(&right.relative_path));
        normalized
            .logical_attachments
            .sort_by(|left, right| left.identity.cmp(&right.identity));
        serde_json::to_vec_pretty(&normalized).map_err(|error| ReportValidationError::Serialize {
            detail: error.to_string(),
        })
    }

    /// SHA-256 of the canonical JSON encoding.
    ///
    /// # Errors
    ///
    /// Returns [`ReportValidationError`] when canonicalization fails.
    pub fn canonical_digest(&self) -> Result<String, ReportValidationError> {
        let bytes = self.to_canonical_json()?;
        Ok(hex_digest(&bytes))
    }
}

/// Hex-encode a SHA-256 digest of `bytes`.
#[must_use]
pub fn hex_digest(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let digest = Sha256::digest(bytes);
    let mut out = String::with_capacity(64);
    for byte in digest {
        let high = HEX.get((byte >> 4) as usize).copied().unwrap_or(b'?');
        let low = HEX.get((byte & 0x0f) as usize).copied().unwrap_or(b'?');
        out.push(char::from(high));
        out.push(char::from(low));
    }
    out
}

fn is_hex_sha256(value: &str) -> bool {
    value.len() == 64 && value.chars().all(|ch| ch.is_ascii_hexdigit())
}

fn looks_absolute(path: &str) -> bool {
    path.starts_with('/') || path.chars().nth(1) == Some(':')
}
