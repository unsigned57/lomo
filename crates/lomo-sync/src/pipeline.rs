//! Provider-neutral five-stage pipeline contract types (P5-03).
//!
//! Adapters only compile/execute intents; they do not own direction, conflict, baseline,
//! tombstone, or retry policy.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::error::{resource_limit, validation};
use crate::limits::{MAX_ACTION_PAGE_ITEMS, MAX_SYNC_PATH_BYTES};
use lomo_core::LomoError;

/// Completeness of a remote listing. Only [`SnapshotCompleteness::Complete`] may participate in
/// missing-path / delete derivation.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SnapshotCompleteness {
    Complete,
    Incomplete,
}

/// Content digest for a sync path (sha256 lowercase hex).
#[derive(Clone, Debug, Eq, PartialEq, Hash, Serialize, Deserialize)]
pub struct ContentDigest(String);

impl ContentDigest {
    /// Parses a 64-char lowercase hex digest.
    ///
    /// # Errors
    ///
    /// Validation when length/charset is wrong.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        if raw.len() != 64 || !raw.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(validation(
                "invalid_content_digest",
                "content digest must be 64 lowercase hex characters",
            ));
        }
        Ok(Self(raw.to_ascii_lowercase()))
    }

    /// Content-addressed digest of arbitrary body bytes (SHA-256 lowercase hex).
    #[must_use]
    pub fn from_bytes(bytes: &[u8]) -> Self {
        Self(format!("{:x}", Sha256::digest(bytes)))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Canonical workspace-relative sync path.
#[derive(Clone, Debug, Eq, PartialEq, Hash, Serialize, Deserialize)]
pub struct SyncPath(String);

impl SyncPath {
    /// Parses a non-empty relative path without `..` segments or absolute roots.
    ///
    /// # Errors
    ///
    /// Validation / resource-limit on empty, oversized, absolute, or traversal paths.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        if raw.is_empty() {
            return Err(validation(
                "invalid_sync_path",
                "sync path must be non-empty",
            ));
        }
        if raw.len() > MAX_SYNC_PATH_BYTES {
            return Err(resource_limit(
                "sync_path_too_long",
                "sync path exceeds the 1024-byte limit",
            ));
        }
        if raw.starts_with('/') || raw.starts_with('\\') {
            return Err(validation(
                "invalid_sync_path",
                "sync path must be workspace-relative",
            ));
        }
        if raw
            .split(['/', '\\'])
            .any(|seg| seg == ".." || seg.is_empty())
        {
            return Err(validation(
                "invalid_sync_path",
                "sync path must not contain empty or parent segments",
            ));
        }
        Ok(Self(raw.to_owned()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// One remote object observed in a snapshot.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct RemotePathEntry {
    pub path: SyncPath,
    pub digest: ContentDigest,
    /// Opaque provider revision token (`ETag` / object version / git blob id). Never a secret.
    pub revision_token: String,
}

/// Stage 1: remote listing fact.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct RemoteSnapshot {
    pub completeness: SnapshotCompleteness,
    pub entries: Vec<RemotePathEntry>,
}

impl RemoteSnapshot {
    /// Builds a snapshot after validating page size.
    ///
    /// # Errors
    ///
    /// Resource-limit when entries exceed the action page ceiling.
    pub fn new(
        completeness: SnapshotCompleteness,
        entries: Vec<RemotePathEntry>,
    ) -> Result<Self, LomoError> {
        if entries.len() > MAX_ACTION_PAGE_ITEMS {
            return Err(resource_limit(
                "remote_snapshot_page_too_large",
                "remote snapshot exceeds the 512-item action page limit",
            ));
        }
        Ok(Self {
            completeness,
            entries,
        })
    }

    /// Builds one streaming snapshot **page** with the same page ceiling as [`Self::new`].
    ///
    /// Completeness of the overall remote listing is owned by the streaming planner; this
    /// constructor only validates the page buffer size (never materializes multi-page sets).
    ///
    /// # Errors
    ///
    /// Resource-limit when entries exceed the action page ceiling.
    pub fn page(entries: Vec<RemotePathEntry>) -> Result<Self, LomoError> {
        Self::new(SnapshotCompleteness::Incomplete, entries)
    }
}

/// Direction-neutral intent compiled from local/remote/baseline facts.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ProviderNeutralIntent {
    /// Ensure remote has this path with this digest (upload / create).
    EnsurePresent {
        path: SyncPath,
        digest: ContentDigest,
        expected_remote_token: Option<String>,
    },
    /// Ensure remote no longer has this path (delete). Only emitted when snapshot is Complete,
    /// baseline exists, tombstone rules pass, and session is not first-takeover.
    EnsureAbsent {
        path: SyncPath,
        expected_remote_token: String,
    },
    /// Local must adopt remote bytes (download).
    PullPresent {
        path: SyncPath,
        digest: ContentDigest,
        remote_token: String,
    },
    /// Path requires durable conflict resolution (both-modified / unproven overlap).
    OpenConflict {
        path: SyncPath,
        local_digest: ContentDigest,
        remote_digest: ContentDigest,
        baseline_digest: Option<ContentDigest>,
    },
    /// Report-only: remote path not owned by Lomo sync surface.
    ReportUnrecognized { path: SyncPath },
}

/// Atomicity of a prepared remote batch.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BatchAtomicity {
    /// Per-path publish (`WebDAV` / S3 style).
    PerPath,
    /// Whole-batch CAS ref update (Git style).
    WholeBatchRef,
}

/// Stage 3: compiled batch ready for adapter execution.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct PreparedRemoteBatch {
    pub atomicity: BatchAtomicity,
    pub intents: Vec<ProviderNeutralIntent>,
}

impl PreparedRemoteBatch {
    /// Builds a prepared batch after validating page size.
    ///
    /// # Errors
    ///
    /// Resource-limit when intents exceed the action page ceiling.
    pub fn new(
        atomicity: BatchAtomicity,
        intents: Vec<ProviderNeutralIntent>,
    ) -> Result<Self, LomoError> {
        if intents.len() > MAX_ACTION_PAGE_ITEMS {
            return Err(resource_limit(
                "prepared_batch_page_too_large",
                "prepared remote batch exceeds the 512-item action page limit",
            ));
        }
        Ok(Self { atomicity, intents })
    }

    /// Counts `EnsureAbsent` intents (user-file remote deletes).
    #[must_use]
    pub fn ensure_absent_count(&self) -> usize {
        self.intents
            .iter()
            .filter(|intent| matches!(intent, ProviderNeutralIntent::EnsureAbsent { .. }))
            .count()
    }

    /// Counts `EnsurePresent` intents.
    #[must_use]
    pub fn ensure_present_count(&self) -> usize {
        self.intents
            .iter()
            .filter(|intent| matches!(intent, ProviderNeutralIntent::EnsurePresent { .. }))
            .count()
    }

    /// Counts durable conflict opens.
    #[must_use]
    pub fn open_conflict_count(&self) -> usize {
        self.intents
            .iter()
            .filter(|intent| matches!(intent, ProviderNeutralIntent::OpenConflict { .. }))
            .count()
    }

    /// Counts `PullPresent` intents (local must adopt remote bytes).
    #[must_use]
    pub fn pull_present_count(&self) -> usize {
        self.intents
            .iter()
            .filter(|intent| matches!(intent, ProviderNeutralIntent::PullPresent { .. }))
            .count()
    }

    /// Counts report-only unrecognized remote paths.
    #[must_use]
    pub fn report_unrecognized_count(&self) -> usize {
        self.intents
            .iter()
            .filter(|intent| matches!(intent, ProviderNeutralIntent::ReportUnrecognized { .. }))
            .count()
    }

    /// True when any path published with a conditional-write / CAS precondition failure.
    ///
    /// Adapters surface this as replan-required; the owner never treats it as unconditional
    /// overwrite success.
    #[must_use]
    pub fn receipt_requires_replan(receipt: &PublishReceipt) -> bool {
        receipt
            .path_results
            .iter()
            .any(|(_path, status)| matches!(status, PathPublishStatus::PreconditionFailed))
    }
}

/// True when a workspace-relative path is on the Lomo-owned user sync surface.
///
/// Owned surface (host hermetic default): Markdown files, and non-hidden paths under the common
/// layout roots `memo/`, `media/`, `images/`, `voice/`. Hidden segments (including `.lomo` control)
/// and foreign tooling paths are **not** owned — plan emits `ReportUnrecognized` only.
#[must_use]
pub fn is_owned_sync_user_path(path: &str) -> bool {
    if path.is_empty() {
        return false;
    }
    if path
        .split(['/', '\\'])
        .any(|segment| segment.is_empty() || segment.starts_with('.'))
    {
        return false;
    }
    // Markdown memos are always on the user surface (any directory depth).
    if is_markdown_path_suffix(path) {
        return true;
    }
    let Some(first) = path.split(['/', '\\']).next() else {
        return false;
    };
    matches!(first, "memo" | "media" | "images" | "voice")
}

fn is_markdown_path_suffix(path: &str) -> bool {
    path.rsplit(['/', '\\']).next().is_some_and(|name| {
        name.len() > 3
            && name
                .as_bytes()
                .get(name.len() - 3..)
                .is_some_and(|suffix| suffix.eq_ignore_ascii_case(b".md"))
    })
}

/// Per-path publish outcome from an adapter.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PathPublishStatus {
    Applied { new_token: String },
    PreconditionFailed,
    Failed { code: String },
    Skipped,
}

/// Stage 4: adapter publish receipt (no secrets).
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct PublishReceipt {
    pub path_results: Vec<(SyncPath, PathPublishStatus)>,
}

/// Stage 5: re-read verification after apply.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VerifyStatus {
    Verified {
        path: SyncPath,
        digest: ContentDigest,
        remote_token: String,
    },
    Failed {
        path: SyncPath,
        code: String,
    },
    AbsentVerified {
        path: SyncPath,
    },
}

/// Verified remote state used as the only authority to advance baseline.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct VerifiedRemoteState {
    pub results: Vec<VerifyStatus>,
}

impl VerifiedRemoteState {
    /// True when every result is a verified success (present or absent).
    #[must_use]
    pub fn all_verified(&self) -> bool {
        self.results.iter().all(|result| {
            matches!(
                result,
                VerifyStatus::Verified { .. } | VerifyStatus::AbsentVerified { .. }
            )
        })
    }

    /// Paths that verified successfully as present.
    #[must_use]
    pub fn verified_present(&self) -> Vec<(SyncPath, ContentDigest, String)> {
        self.results
            .iter()
            .filter_map(|result| match result {
                VerifyStatus::Verified {
                    path,
                    digest,
                    remote_token,
                } => Some((path.clone(), digest.clone(), remote_token.clone())),
                VerifyStatus::Failed { .. } | VerifyStatus::AbsentVerified { .. } => None,
            })
            .collect()
    }
}

/// Pipeline stage tag for diagnostics / session pages.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PipelineStage {
    RemoteSnapshot,
    ProviderNeutralIntent,
    PreparedRemoteBatch,
    PublishReceipt,
    VerifiedRemoteState,
}
