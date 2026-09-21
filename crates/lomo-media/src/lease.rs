//! Durable stage ledger: immutable artifact identity plus owner leases for staged media.
//!
//! Identity law: an artifact is immutable verified bytes, so its [`ArtifactId`] is derived from
//! the content digest. Two drafts referencing the same bytes therefore share one artifact while
//! each holds its own [`StageLease`]; releasing one lease must never destroy bytes another holder
//! still references.
//!
//! The ledger is the durable owner of every staged file between verify and commit/discard. It is
//! persisted next to the staged bytes and re-loaded on restart, so a process death does not lose
//! the association between staged media and its holders.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use lomo_core::LomoError;
use serde::{Deserialize, Serialize};

use crate::error::{corruption, storage, validation};
use crate::identity::{ContentDigest, MediaMime};
use crate::path::MediaRelativePath;
use crate::stage::MediaStaged;

/// Ledger file name inside a media stage directory.
pub const STAGE_LEDGER_FILE: &str = "ledger.json";

/// Stable identity of immutable staged bytes.
///
/// Content-derived: equal bytes are the same artifact regardless of staging path or filename.
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub struct ArtifactId(String);

impl ArtifactId {
    /// Derives the artifact identity from a verified content digest.
    #[must_use]
    pub fn of_digest(digest: &ContentDigest) -> Self {
        Self(digest.as_str().to_owned())
    }

    /// Parses an artifact identity from its wire form (a content digest).
    ///
    /// # Errors
    ///
    /// Returns validation when the wire form is not a 64-byte lowercase hex digest.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        ContentDigest::parse(raw)?;
        Ok(Self(raw.to_owned()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Who currently holds staged bytes. Owners are real holders, not placeholder roles.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum StageOwnerKind {
    /// An in-progress editor draft that imported the media but has not submitted it.
    Draft,
    /// A frozen memo operation that is committing the staged media.
    PendingOperation,
    /// A sync/LAN receive session staging inbound media before its memo arrives.
    IncomingTransfer,
    /// A committed document reference that must keep the bytes alive.
    CommittedReference,
}

/// One claim by a real holder on one artifact.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct StageLease {
    pub artifact_id: ArtifactId,
    pub owner_kind: StageOwnerKind,
    pub owner_id: String,
}

impl StageLease {
    /// Builds a checked lease.
    ///
    /// # Errors
    ///
    /// Returns validation when the owner id is blank or unbounded.
    pub fn new(
        artifact_id: ArtifactId,
        owner_kind: StageOwnerKind,
        owner_id: &str,
    ) -> Result<Self, LomoError> {
        if owner_id.trim().is_empty() || owner_id.len() > 256 {
            return Err(validation(
                "invalid_stage_lease_owner",
                "stage lease owner id must be a non-empty bounded token",
            ));
        }
        Ok(Self {
            artifact_id,
            owner_kind,
            owner_id: owner_id.to_owned(),
        })
    }
}

/// Durable record for one staged artifact with its current leases.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct StageRecord {
    pub artifact_id: ArtifactId,
    pub digest: ContentDigest,
    pub size: u64,
    pub mime: MediaMime,
    pub staging_path: PathBuf,
    pub human_name_hint: String,
    /// Resolved workspace-relative destination (collision-free within this ledger and workspace).
    pub suggested_final_relative_path: String,
    pub leases: Vec<StageLease>,
}

impl StageRecord {
    /// True when the staged bytes still exist on disk.
    #[must_use]
    pub fn is_present(&self) -> bool {
        self.staging_path.is_file()
    }

    /// Projects the durable record back to the staged-media facts used by promote planning.
    #[must_use]
    pub fn to_staged(&self) -> MediaStaged {
        MediaStaged {
            digest: self.digest.clone(),
            size: self.size,
            mime: self.mime,
            staging_path: self.staging_path.clone(),
            human_name_hint: self.human_name_hint.clone(),
            suggested_final_relative_path: self.suggested_final_relative_path.clone(),
        }
    }
}

/// Outcome of releasing or discarding staged bytes.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct StageRelease {
    pub artifact_id: ArtifactId,
    pub remaining_leases: u64,
    pub bytes_deleted: bool,
}

/// Durable in-memory view of one stage directory's ledger.
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
pub struct StageLedger {
    records: BTreeMap<String, StageRecord>,
}

impl StageLedger {
    /// Loads the ledger beside `stage_dir`.
    ///
    /// # Errors
    ///
    /// Returns corruption when an existing ledger cannot be decoded, storage when it cannot be read.
    pub fn load(stage_dir: &Path) -> Result<Self, LomoError> {
        let path = stage_dir.join(STAGE_LEDGER_FILE);
        if !path.is_file() {
            return Ok(Self::default());
        }
        let bytes = fs::read(&path).map_err(|error| {
            storage(
                "media_stage_ledger_read_failed",
                &format!("failed to read stage ledger: {error}"),
            )
        })?;
        serde_json::from_slice(&bytes).map_err(|error| {
            corruption(
                "media_stage_ledger_corrupt",
                &format!("stage ledger is not decodable: {error}"),
            )
        })
    }

    /// Persists the ledger durably (temp write + rename) beside `stage_dir`.
    ///
    /// # Errors
    ///
    /// Returns storage when the directory or durable write fails.
    pub fn save(&self, stage_dir: &Path) -> Result<(), LomoError> {
        fs::create_dir_all(stage_dir).map_err(|error| {
            storage(
                "media_stage_ledger_dir_failed",
                &format!("failed to create stage dir for ledger: {error}"),
            )
        })?;
        let body = serde_json::to_vec(self).map_err(|error| {
            validation(
                "media_stage_ledger_encode_failed",
                &format!("cannot encode stage ledger: {error}"),
            )
        })?;
        let path = stage_dir.join(STAGE_LEDGER_FILE);
        let tmp = stage_dir.join(format!("{STAGE_LEDGER_FILE}.tmp"));
        fs::write(&tmp, &body).map_err(|error| {
            storage(
                "media_stage_ledger_write_failed",
                &format!("failed to write stage ledger: {error}"),
            )
        })?;
        fs::rename(&tmp, &path).map_err(|error| {
            storage(
                "media_stage_ledger_commit_failed",
                &format!("failed to commit stage ledger: {error}"),
            )
        })?;
        Ok(())
    }

    /// Records a freshly staged artifact and acquires one lease for it.
    ///
    /// The destination is resolved deterministically against this ledger and any existing workspace
    /// file, so two same-named different-digest artifacts never collapse onto one path.
    ///
    /// # Errors
    ///
    /// Returns validation when the staged path has no stage directory.
    pub fn record(
        &mut self,
        workspace_root: Option<&Path>,
        staged: &MediaStaged,
        lease: StageLease,
    ) -> Result<StageRecord, LomoError> {
        let stage_dir = stage_directory_of(&staged.staging_path)?;
        let artifact_id = ArtifactId::of_digest(&staged.digest);
        let resolved = self.resolve_final_relative_path(
            workspace_root,
            &staged.suggested_final_relative_path,
            &staged.digest,
            staged.size,
        )?;
        let record = self
            .records
            .entry(artifact_id.as_str().to_owned())
            .or_insert_with(|| StageRecord {
                artifact_id: artifact_id.clone(),
                digest: staged.digest.clone(),
                size: staged.size,
                mime: staged.mime,
                staging_path: staged.staging_path.clone(),
                human_name_hint: staged.human_name_hint.clone(),
                suggested_final_relative_path: resolved.as_str().to_owned(),
                leases: Vec::new(),
            });
        if !record.leases.contains(&lease) {
            record.leases.push(lease);
        }
        let record = record.clone();
        self.save(&stage_dir)?;
        Ok(record)
    }

    /// Releases one lease. Staged bytes are deleted only when no lease remains.
    ///
    /// # Errors
    ///
    /// Returns validation when the lease artifact has no record.
    pub fn release(
        &mut self,
        stage_dir: &Path,
        lease: &StageLease,
    ) -> Result<StageRelease, LomoError> {
        let Some(record) = self.records.get_mut(lease.artifact_id.as_str()) else {
            return Err(validation(
                "media_stage_artifact_unknown",
                "stage lease names an artifact the ledger does not hold",
            ));
        };
        record.leases.retain(|held| held != lease);
        let remaining = u64::try_from(record.leases.len()).unwrap_or(u64::MAX);
        let mut bytes_deleted = false;
        if remaining == 0 {
            if record.staging_path.is_file() {
                fs::remove_file(&record.staging_path).map_err(|error| {
                    storage(
                        "media_stage_release_delete_failed",
                        &format!("failed to delete unleased staged bytes: {error}"),
                    )
                })?;
                bytes_deleted = true;
            }
            self.records.remove(lease.artifact_id.as_str());
        }
        self.save(stage_dir)?;
        Ok(StageRelease {
            artifact_id: lease.artifact_id.clone(),
            remaining_leases: remaining,
            bytes_deleted,
        })
    }

    /// Transfers one holder's claim to another without deleting any bytes.
    ///
    /// A draft submits by handing its [`StageOwnerKind::Draft`] lease to the frozen
    /// [`StageOwnerKind::PendingOperation`] lease, so the commit path never double-owns the bytes
    /// and a shared artifact still owned by another draft is untouched. Re-transferring an already
    /// transferred claim is idempotent, which keeps a retried submit on the same frozen identity.
    ///
    /// # Errors
    ///
    /// Returns validation when neither the source nor the destination lease is held.
    pub fn transfer(
        &mut self,
        stage_dir: &Path,
        from: &StageLease,
        to: StageLease,
    ) -> Result<StageRelease, LomoError> {
        let Some(record) = self.records.get_mut(from.artifact_id.as_str()) else {
            return Err(validation(
                "media_stage_artifact_unknown",
                "stage lease names an artifact the ledger does not hold",
            ));
        };
        if to.artifact_id != from.artifact_id {
            return Err(validation(
                "media_stage_transfer_mismatch",
                "stage lease transfer must stay within one artifact",
            ));
        }
        if !record.leases.contains(from) {
            // A retried submit observed its claim already transferred; the frozen identity means
            // this is the same operation, not a new holder.
            if record.leases.contains(&to) {
                let remaining = u64::try_from(record.leases.len()).unwrap_or(u64::MAX);
                return Ok(StageRelease {
                    artifact_id: from.artifact_id.clone(),
                    remaining_leases: remaining,
                    bytes_deleted: false,
                });
            }
            return Err(validation(
                "media_stage_lease_not_held",
                "stage lease transfer source is not held by the ledger",
            ));
        }
        if !record.leases.contains(&to) {
            record.leases.push(to);
        }
        record.leases.retain(|held| held != from);
        let remaining = u64::try_from(record.leases.len()).unwrap_or(u64::MAX);
        self.save(stage_dir)?;
        Ok(StageRelease {
            artifact_id: from.artifact_id.clone(),
            remaining_leases: remaining,
            bytes_deleted: false,
        })
    }

    /// Records leased by one exact holder.
    #[must_use]
    pub fn records_for_owner(
        &self,
        owner_kind: StageOwnerKind,
        owner_id: &str,
    ) -> Vec<StageRecord> {
        self.records
            .values()
            .filter(|record| {
                record
                    .leases
                    .iter()
                    .any(|lease| lease.owner_kind == owner_kind && lease.owner_id == owner_id)
            })
            .cloned()
            .collect()
    }

    /// True when any holder still leases the artifact.
    #[must_use]
    pub fn holds_leases(&self, artifact_id: &ArtifactId) -> bool {
        self.records
            .get(artifact_id.as_str())
            .is_some_and(|record| !record.leases.is_empty())
    }

    /// Artifacts whose staged bytes disappeared (recoverable draft failure evidence).
    #[must_use]
    pub fn missing_artifacts(&self) -> Vec<ArtifactId> {
        self.records
            .values()
            .filter(|record| !record.is_present())
            .map(|record| record.artifact_id.clone())
            .collect()
    }

    /// Resolves a collision-free media destination.
    ///
    /// A candidate is reusable when no other-digest record claims it and any existing workspace
    /// file holds the same bytes. Otherwise a deterministic `_1`, `_2`, ... suffix is appended.
    ///
    /// # Errors
    ///
    /// Returns validation when the suggestion or a suffixed candidate is not a canonical path.
    pub fn resolve_final_relative_path(
        &self,
        workspace_root: Option<&Path>,
        suggested: &str,
        digest: &ContentDigest,
        size: u64,
    ) -> Result<MediaRelativePath, LomoError> {
        let candidate = MediaRelativePath::parse(suggested)?;
        if self.candidate_is_available(workspace_root, &candidate, digest, size)? {
            return Ok(candidate);
        }
        let path = Path::new(suggested);
        let parent = path.parent().unwrap_or_else(|| Path::new(""));
        let stem = path
            .file_stem()
            .and_then(|value| value.to_str())
            .ok_or_else(|| {
                validation(
                    "media_stage_destination_invalid",
                    "staged media suggestion has no UTF-8 filename stem",
                )
            })?;
        let extension = path
            .extension()
            .and_then(|value| value.to_str())
            .ok_or_else(|| {
                validation(
                    "media_stage_destination_invalid",
                    "staged media suggestion has no UTF-8 extension",
                )
            })?;
        for suffix in 1_u32..=u32::MAX {
            let raw = parent
                .join(format!("{stem}_{suffix}.{extension}"))
                .to_str()
                .ok_or_else(|| {
                    validation(
                        "media_stage_destination_invalid",
                        "staged media destination is not valid UTF-8",
                    )
                })?
                .to_owned();
            let candidate = MediaRelativePath::parse(&raw)?;
            if self.candidate_is_available(workspace_root, &candidate, digest, size)? {
                return Ok(candidate);
            }
        }
        Err(validation(
            "media_stage_destination_exhausted",
            "staged media destination suffix range is exhausted",
        ))
    }

    fn candidate_is_available(
        &self,
        workspace_root: Option<&Path>,
        candidate: &MediaRelativePath,
        digest: &ContentDigest,
        size: u64,
    ) -> Result<bool, LomoError> {
        let claimed_by_other = self.records.values().any(|record| {
            record.suggested_final_relative_path == candidate.as_str() && record.digest != *digest
        });
        if claimed_by_other {
            return Ok(false);
        }
        let Some(root) = workspace_root else {
            return Ok(true);
        };
        let absolute = root.join(candidate.as_str());
        if !absolute.exists() {
            return Ok(true);
        }
        if !absolute.is_file() {
            return Ok(false);
        }
        let (existing, existing_size) = ContentDigest::stream_from_path(&absolute)?;
        Ok(existing == *digest && existing_size == size)
    }
}

/// Returns the stage directory that owns `staging_path`.
///
/// # Errors
///
/// Returns validation when the staged path has no parent directory.
pub fn stage_directory_of(staging_path: &Path) -> Result<PathBuf, LomoError> {
    staging_path
        .parent()
        .map(Path::to_path_buf)
        .filter(|parent| !parent.as_os_str().is_empty())
        .ok_or_else(|| {
            validation(
                "media_stage_path_invalid",
                "staged media path must have an owning stage directory",
            )
        })
}

/// Returns the canonical stage directory under a media root.
#[must_use]
pub fn stage_directory(media_root: &Path) -> PathBuf {
    media_root.join(crate::stage::STAGE_DIR_NAME)
}
