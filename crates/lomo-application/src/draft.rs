use std::{fs::create_dir_all, path::PathBuf, time::SystemTime};

use lomo_core::{LomoError, OperationId, RelativeWorkspacePath};
use serde::{Deserialize, Serialize};

use crate::{
    error::{storage, validation},
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
    baselines_dir: PathBuf,
}

/// One retained draft body with its owning operation identity.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DraftBody {
    pub operation_id: OperationId,
    pub draft_content: String,
}

/// File-level token evidence for one retained conflict-evidence draft.
///
/// A file whose name, byte length and modification timestamp all match the token a
/// consumer recorded holds the same bytes, so its decoded projection stays valid
/// without touching content again. Any deviation — a rewritten, truncated or replaced
/// file — changes the token and the consumer must fall back to
/// [`DraftStore::read_draft_body`]'s full decode.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DraftFileEvidence {
    /// Bare file name inside the drafts directory (`<operation-id>.json`).
    pub name: String,
    /// Byte length reported by directory metadata.
    pub size: u64,
    /// Modification timestamp reported by directory metadata.
    pub modified: SystemTime,
}

/// A draft body supplied by an editor whose drafts live outside the Rust `DraftStore`.
///
/// The host passes these bodies to the guarding attachment-index / sweep entry points, and
/// their attachment destinations extend the keep-set exactly like an internal draft for the
/// duration of that computation. Nothing is persisted.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GuardedDraftBody {
    /// Opaque owner identity for diagnostics (for example the host's draft id).
    pub owner_id: String,
    /// The draft body text; attachment references are projected by the render owner.
    pub content: String,
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
    /// Creates a new draft store over the canonical session-state children
    /// ([`lomo_workspace::SessionStateLayout::drafts`] for evidence,
    /// [`lomo_workspace::SessionStateLayout::baselines`] for CAS reads).
    ///
    /// # Errors
    /// Returns `Storage` error if the drafts directory cannot be created.
    pub fn new(state: &lomo_workspace::SessionStateLayout) -> Result<Self, LomoError> {
        create_dir_all(&state.drafts).map_err(|err| {
            storage(
                "drafts_dir_unavailable",
                format!("failed to create drafts directory: {err}"),
            )
        })?;
        Ok(Self {
            drafts_dir: state.drafts.clone(),
            baselines_dir: state.baselines.clone(),
        })
    }

    /// Persists 3-way conflict evidence (operation, baseline, disk, and draft).
    ///
    /// `current_bytes` are the disk bytes the caller already holds in memory
    /// (`LoadedDocument::original`); the baseline side still resolves through the CAS,
    /// which only retains bytes committed at a write boundary.
    ///
    /// # Errors
    /// Returns `Storage` error if JSON encoding or writing to disk fails.
    pub fn save_conflict_evidence(
        &self,
        evidence: &ConflictEvidence,
        current_bytes: Option<&[u8]>,
    ) -> Result<(), LomoError> {
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
            baseline_bytes: baseline_bytes(&self.baselines_dir, &evidence.baseline_fingerprint)?,
            current_bytes: current_bytes.map(<[u8]>::to_vec),
        };
        let bytes = serde_json::to_vec_pretty(&physical)
            .map_err(|error| storage("draft_evidence_encode_failed", error.to_string()))?;
        write_atomic(&path, &bytes)
    }

    /// Fingerprints retained draft evidence still cites — the baseline/disk sides the
    /// CAS must keep alive for evidence enrichment.
    ///
    /// # Errors
    /// Returns `Storage` when the drafts directory cannot be listed or a file cannot be read,
    /// and corruption when a retained draft cannot be decoded — an unreadable draft aborts
    /// eviction rather than orphaning evidence it may reference.
    pub fn referenced_fingerprints(&self) -> Result<std::collections::BTreeSet<String>, LomoError> {
        let mut out = std::collections::BTreeSet::new();
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
            let evidence: ConflictEvidence = serde_json::from_slice(&bytes).map_err(|err| {
                crate::error::corruption(
                    "draft_evidence_corrupt",
                    format!("draft evidence is not decodable: {err}"),
                )
            })?;
            out.insert(evidence.baseline_fingerprint);
            out.insert(evidence.disk_fingerprint);
        }
        Ok(out)
    }

    /// Lists retained conflict-evidence drafts by file token in file-name order —
    /// name, size and modification timestamp — without reading or decoding any body.
    ///
    /// Draft bodies participate in the media protection set: an attachment the editor still
    /// holds must never look collectable to the sweep. Sweep callers compare these tokens
    /// incrementally against their retained projections; only a changed token pays a full
    /// decode through [`Self::read_draft_body`].
    ///
    /// # Errors
    ///
    /// Returns `Storage` when the drafts directory or one entry's metadata cannot be read,
    /// and `Validation` when a retained draft name is not bare UTF-8 — a name that cannot be
    /// addressed is evidence the consumer must not silently skip.
    pub fn list_draft_evidence(&self) -> Result<Vec<DraftFileEvidence>, LomoError> {
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
            let name = entry.file_name().into_string().map_err(|name| {
                validation(
                    "invalid_draft_evidence_name",
                    format!(
                        "draft evidence file name is not UTF-8: {}",
                        name.to_string_lossy()
                    ),
                )
            })?;
            let metadata = entry.metadata().map_err(|err| {
                storage(
                    "draft_evidence_read_failed",
                    format!("failed to stat draft evidence {name}: {err}"),
                )
            })?;
            let modified = metadata.modified().map_err(|err| {
                storage(
                    "draft_evidence_read_failed",
                    format!("failed to read draft evidence modification time {name}: {err}"),
                )
            })?;
            out.push(DraftFileEvidence {
                name,
                size: metadata.len(),
                modified,
            });
        }
        out.sort_by(|left, right| left.name.cmp(&right.name));
        Ok(out)
    }

    /// Reads and decodes one retained draft body, returning the token evidence observed
    /// for that read.
    ///
    /// Metadata is sampled before the body bytes so a concurrent rewrite can only be newer
    /// than the returned token — a consumer comparing tokens on its next pass always sees
    /// the change and re-decodes; a stale body is never silently retained.
    ///
    /// # Errors
    ///
    /// Returns `Validation` when `name` is not a bare `*.json` file name, `Storage` when
    /// the file cannot be read, and corruption when the retained evidence is not decodable.
    pub fn read_draft_body(&self, name: &str) -> Result<(DraftFileEvidence, DraftBody), LomoError> {
        if name.is_empty()
            || name == "."
            || name == ".."
            || name.contains(['/', '\\'])
            || !std::path::Path::new(name)
                .extension()
                .is_some_and(|ext| ext.eq_ignore_ascii_case("json"))
        {
            return Err(validation(
                "invalid_draft_evidence_name",
                "draft evidence name must be a bare '<operation-id>.json' file name",
            ));
        }
        let path = self.drafts_dir.join(name);
        let metadata = std::fs::metadata(&path).map_err(|err| {
            storage(
                "draft_evidence_read_failed",
                format!("failed to stat draft evidence {name}: {err}"),
            )
        })?;
        let modified = metadata.modified().map_err(|err| {
            storage(
                "draft_evidence_read_failed",
                format!("failed to read draft evidence modification time {name}: {err}"),
            )
        })?;
        let bytes = std::fs::read(&path).map_err(|err| {
            storage(
                "draft_evidence_read_failed",
                format!("failed to read draft evidence {name}: {err}"),
            )
        })?;
        let facts: DraftBodyFacts = serde_json::from_slice(&bytes).map_err(|err| {
            crate::error::corruption(
                "draft_evidence_corrupt",
                format!("draft evidence {name} is not decodable: {err}"),
            )
        })?;
        Ok((
            DraftFileEvidence {
                name: name.to_owned(),
                size: metadata.len(),
                modified,
            },
            DraftBody {
                operation_id: facts.operation_id,
                draft_content: facts.draft_content,
            },
        ))
    }
}
