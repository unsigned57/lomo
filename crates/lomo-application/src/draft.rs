use std::{
    fs::create_dir_all,
    path::{Path, PathBuf},
};

use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
use serde::{Deserialize, Serialize};

use crate::{
    error::storage,
    private_io::{baseline_bytes, write_atomic},
};

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ConflictEvidence {
    pub operation_id: OperationId,
    pub path: RelativeWorkspacePath,
    pub baseline_fingerprint: String,
    pub disk_fingerprint: String,
    pub draft_content: String,
    pub recorded_at_ms: i64,
}

#[derive(Debug)]
pub struct DraftStore {
    drafts_dir: PathBuf,
    state_dir: PathBuf,
}

/// One retained draft body with its owning operation identity.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DraftBody {
    pub operation_id: OperationId,
    pub draft_content: String,
}

#[derive(Deserialize)]
struct DraftBodyFacts {
    operation_id: OperationId,
    draft_content: String,
}

#[derive(Serialize)]
struct PhysicalConflictEvidence<'a> {
    #[serde(flatten)]
    request: &'a ConflictEvidence,
    baseline_bytes: Option<Vec<u8>>,
    current_bytes: Option<Vec<u8>>,
}

impl DraftStore {
    /// Creates a new draft store under the provided state directory.
    ///
    /// # Errors
    /// Returns `Storage` error if the drafts directory cannot be created.
    pub fn new(state_dir: &Path) -> Result<Self, LomoError> {
        let drafts_dir = state_dir.join("drafts");
        create_dir_all(&drafts_dir).map_err(|err| {
            storage(
                "drafts_dir_unavailable",
                format!("failed to create drafts directory: {err}"),
            )
        })?;
        Ok(Self {
            drafts_dir,
            state_dir: state_dir.to_path_buf(),
        })
    }

    /// Persists 3-way conflict evidence (operation, baseline, disk, and draft).
    ///
    /// # Errors
    /// Returns `Storage` error if JSON encoding or writing to disk fails.
    pub fn save_conflict_evidence(&self, evidence: &ConflictEvidence) -> Result<(), LomoError> {
        let path = self
            .drafts_dir
            .join(format!("{}.json", evidence.operation_id.as_str()));
        let bytes = serde_json::to_vec_pretty(evidence).map_err(|err| {
            storage(
                "draft_evidence_encode_failed",
                format!("failed to encode draft evidence: {err}"),
            )
        })?;
        // Preserve the editor text even if an older baseline has become unavailable or corrupt.
        write_atomic(&path, &bytes)?;
        let physical = PhysicalConflictEvidence {
            request: evidence,
            baseline_bytes: baseline_bytes(&self.state_dir, &evidence.baseline_fingerprint)?,
            current_bytes: baseline_bytes(&self.state_dir, &evidence.disk_fingerprint)?,
        };
        let bytes = serde_json::to_vec_pretty(&physical)
            .map_err(|error| storage("draft_evidence_encode_failed", error.to_string()))?;
        write_atomic(&path, &bytes)
    }

    /// Lists every retained draft body (conflict evidence) in operation-id order.
    ///
    /// Draft bodies participate in the media protection set: an attachment the editor still
    /// holds must never look collectable to the sweep.
    ///
    /// # Errors
    ///
    /// Returns `Storage` when the drafts directory cannot be listed or a file cannot be read,
    /// and corruption when a retained draft cannot be decoded.
    pub fn list_draft_bodies(&self) -> Result<Vec<DraftBody>, LomoError> {
        let mut out = Vec::new();
        let entries = std::fs::read_dir(&self.drafts_dir).map_err(|err| {
            storage(
                "drafts_dir_unavailable",
                format!("failed to list drafts directory: {err}"),
            )
        })?;
        for entry in entries {
            let entry = entry.map_err(|err| {
                storage(
                    "drafts_dir_unavailable",
                    format!("failed to read drafts directory entry: {err}"),
                )
            })?;
            let path = entry.path();
            if path.extension().and_then(|ext| ext.to_str()) != Some("json") {
                continue;
            }
            let bytes = std::fs::read(&path).map_err(|err| {
                storage(
                    "draft_evidence_read_failed",
                    format!("failed to read draft evidence: {err}"),
                )
            })?;
            let facts: DraftBodyFacts = serde_json::from_slice(&bytes).map_err(|err| {
                crate::error::corruption(
                    "draft_evidence_corrupt",
                    format!("draft evidence is not decodable: {err}"),
                )
            })?;
            out.push(DraftBody {
                operation_id: facts.operation_id,
                draft_content: facts.draft_content,
            });
        }
        out.sort_by(|left, right| left.operation_id.as_str().cmp(right.operation_id.as_str()));
        Ok(out)
    }
}
