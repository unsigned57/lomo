//! Provider-neutral five-stage pipeline contract types (P5-03).
//!
//! Adapters only compile/execute intents; they do not own direction, conflict, baseline,
//! tombstone, or retry policy.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::error::{resource_limit, validation};
use crate::limits::{MAX_ACTION_PAGE_ITEMS, MAX_SYNC_PATH_BYTES, MAX_WHOLE_BATCH_INTENTS};
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

/// What a remote listing proves about one path's content bytes.
///
/// Content-equality proof and conditional-update authority are different facts: a provider that
/// only enumerates metadata cannot prove bytes, and a digest can never serve as an `If-Match`
/// precondition. Listing-time [`Self::Unresolved`] digests are resolved on demand by
/// [`crate::ports::RemoteSyncPort::resolve_remote_object`].
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case", tag = "state", content = "digest")]
pub enum RemoteDigestFact {
    /// Digest proven by the listing itself (content-addressed object ids / verified metadata).
    Known(ContentDigest),
    /// Metadata-only listing: the digest must be resolved on demand before byte decisions.
    Unresolved,
}

impl RemoteDigestFact {
    /// The proven digest, when the listing already carries one.
    #[must_use]
    pub const fn known(&self) -> Option<&ContentDigest> {
        match self {
            Self::Known(digest) => Some(digest),
            Self::Unresolved => None,
        }
    }
}

/// Provider conditional-update validator for one remote path.
///
/// Only [`Self::Strong`] tokens may drive conditional writes (`If-Match` deletes/updates).
/// Weak validators are reported for change detection hints and durable bookkeeping, never as
/// write preconditions; [`Self::Absent`] means the provider gave no per-path validator at all.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case", tag = "kind", content = "token")]
pub enum RemoteValidator {
    /// Strong conditional-write token (strong `ETag`, object version id, git blob OID).
    Strong(String),
    /// Observed token that cannot drive conditional writes (weak `ETag` `W/"…"`).
    Weak(String),
    /// No validator observed — conditional update unsupported for this path.
    Absent,
}

impl RemoteValidator {
    /// Raw token string for durable records / diagnostics, whatever its strength.
    #[must_use]
    pub const fn token(&self) -> Option<&str> {
        match self {
            Self::Strong(token) | Self::Weak(token) => Some(token.as_str()),
            Self::Absent => None,
        }
    }

    /// Strong token usable as a conditional-write precondition, when present.
    #[must_use]
    pub const fn strong_token(&self) -> Option<&str> {
        match self {
            Self::Strong(token) => Some(token.as_str()),
            Self::Weak(_) | Self::Absent => None,
        }
    }
}

/// One remote object observed in a snapshot.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct RemotePathEntry {
    pub path: SyncPath,
    /// Content digest when the listing proves it; otherwise resolved on demand.
    pub digest: RemoteDigestFact,
    /// Path-level conditional-update validator (`ETag` / object version / git blob OID). Never a
    /// secret. Not the snapshot CAS validator — that lives on
    /// [`RemoteSnapshot::snapshot_revision`].
    pub validator: RemoteValidator,
}

impl RemotePathEntry {
    /// Builds an entry whose digest is proven by the listing (legacy/test-shaped facts).
    #[must_use]
    pub const fn known(path: SyncPath, digest: ContentDigest, validator: RemoteValidator) -> Self {
        Self {
            path,
            digest: RemoteDigestFact::Known(digest),
            validator,
        }
    }
}

/// Stage 1: remote listing fact.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct RemoteSnapshot {
    pub completeness: SnapshotCompleteness,
    pub entries: Vec<RemotePathEntry>,
    /// Snapshot-level CAS token when the provider is whole-batch (Git branch tip commit OID).
    /// Per-path providers leave this `None`.
    #[serde(default)]
    pub snapshot_revision: Option<String>,
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
        Self::with_snapshot_revision(completeness, entries, None)
    }

    /// Builds a snapshot with an explicit snapshot-level CAS token (Git branch tip).
    ///
    /// # Errors
    ///
    /// Resource-limit when entries exceed the action page ceiling.
    pub fn with_snapshot_revision(
        completeness: SnapshotCompleteness,
        entries: Vec<RemotePathEntry>,
        snapshot_revision: Option<String>,
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
            snapshot_revision,
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
///
/// In-memory pipeline value only — durable intent facts live in
/// [`crate::conflict::ConflictPathRecord`] and the
/// baseline/tombstone heads. No wire or durable serde surface exists for this type.
#[derive(Clone, Debug, Eq, PartialEq)]
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
        /// Observed listing validator for durable records/diagnostics; `None` when absent.
        remote_token: Option<String>,
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
    /// A remote mutation is required but cannot be issued: the provider gave no strong validator,
    /// so a conditional update is impossible without risking an unconditional overwrite. The
    /// intent is never published; it is recorded in the plan summary so the hold is surfaced.
    Hold { path: SyncPath, reason: HoldReason },
}

/// Why a required remote mutation was held instead of published.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HoldReason {
    /// Provider listed no strong validator (`ETag`/version/blob OID) for this path.
    ConditionalUpdateUnsupported,
}

/// Capability gate for a prepared batch: conditional remote mutations require the
/// probed/declared capability facts. `EnsurePresent` needs `conditional_write`;
/// `EnsureAbsent` needs `conditional_delete`. Other intents (pull, conflict, hold,
/// report) never mutate the remote and pass unconditionally.
///
/// # Errors
///
/// Validation `remote_capability_unsupported` naming the first unmet requirement —
/// the batch is refused before any conditional write relies on server leniency.
pub fn require_remote_capabilities(
    intents: &[ProviderNeutralIntent],
    caps: crate::ports::RemoteCapabilities,
) -> Result<(), LomoError> {
    for intent in intents {
        let unmet = match intent {
            ProviderNeutralIntent::EnsurePresent { .. } if !caps.conditional_write => {
                Some("conditional_write")
            }
            ProviderNeutralIntent::EnsureAbsent { .. } if !caps.conditional_delete => {
                Some("conditional_delete")
            }
            ProviderNeutralIntent::EnsurePresent { .. }
            | ProviderNeutralIntent::EnsureAbsent { .. }
            | ProviderNeutralIntent::PullPresent { .. }
            | ProviderNeutralIntent::OpenConflict { .. }
            | ProviderNeutralIntent::ReportUnrecognized { .. }
            | ProviderNeutralIntent::Hold { .. } => None,
        };
        if let Some(capability) = unmet {
            return Err(validation(
                "remote_capability_unsupported",
                &format!("remote does not honour {capability}; refusing conditional mutation"),
            ));
        }
    }
    Ok(())
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

/// Planner/adapter handshake for one publish: atomicity plus snapshot-level CAS token.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RemotePublishContract {
    pub atomicity: BatchAtomicity,
    /// Git branch tip when [`BatchAtomicity::WholeBatchRef`]; always `None` for per-path providers.
    pub snapshot_revision: Option<String>,
}

impl RemotePublishContract {
    /// Per-path CAS (`WebDAV` / S3): no snapshot token.
    #[must_use]
    pub const fn per_path() -> Self {
        Self {
            atomicity: BatchAtomicity::PerPath,
            snapshot_revision: None,
        }
    }

    /// Whole-batch ref CAS (Git): snapshot token is the listed branch tip.
    #[must_use]
    pub const fn whole_batch(snapshot_revision: Option<String>) -> Self {
        Self {
            atomicity: BatchAtomicity::WholeBatchRef,
            snapshot_revision,
        }
    }

    /// Combines adapter atomicity with the listed snapshot revision.
    ///
    /// Per-path providers drop the snapshot token: path CAS stays on each intent.
    #[must_use]
    pub fn from_atomicity(atomicity: BatchAtomicity, snapshot_revision: Option<String>) -> Self {
        match atomicity {
            BatchAtomicity::PerPath => Self::per_path(),
            BatchAtomicity::WholeBatchRef => Self::whole_batch(snapshot_revision),
        }
    }
}

/// Stage 3: compiled batch ready for adapter execution.
///
/// In-memory pipeline value only — never serialized to wire or durable state.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PreparedRemoteBatch {
    pub atomicity: BatchAtomicity,
    pub intents: Vec<ProviderNeutralIntent>,
    /// Snapshot-level CAS token for [`BatchAtomicity::WholeBatchRef`] (Git branch tip).
    /// Per-path batches always store `None`; path `ETag`s stay on each intent.
    pub expected_snapshot_token: Option<String>,
}

impl PreparedRemoteBatch {
    /// Builds a prepared batch after validating the atomicity-specific intent ceiling.
    ///
    /// # Errors
    ///
    /// Resource-limit when intents exceed the page ([`BatchAtomicity::PerPath`]) or whole-batch
    /// (Git) ceiling.
    pub fn new(
        atomicity: BatchAtomicity,
        intents: Vec<ProviderNeutralIntent>,
    ) -> Result<Self, LomoError> {
        Self::with_snapshot_token(atomicity, intents, None)
    }

    /// Builds a batch from the planner/adapter publish contract.
    ///
    /// # Errors
    ///
    /// Resource-limit when intents exceed the atomicity-specific ceiling.
    pub fn from_contract(
        contract: RemotePublishContract,
        intents: Vec<ProviderNeutralIntent>,
    ) -> Result<Self, LomoError> {
        Self::with_snapshot_token(contract.atomicity, intents, contract.snapshot_revision)
    }

    /// Builds a batch with an explicit snapshot CAS token.
    ///
    /// Per-path atomicity ignores `expected_snapshot_token` (path tokens stay on intents).
    ///
    /// # Errors
    ///
    /// Resource-limit when intents exceed the atomicity-specific ceiling.
    pub fn with_snapshot_token(
        atomicity: BatchAtomicity,
        intents: Vec<ProviderNeutralIntent>,
        expected_snapshot_token: Option<String>,
    ) -> Result<Self, LomoError> {
        let (ceiling, code, message) = match atomicity {
            BatchAtomicity::PerPath => (
                MAX_ACTION_PAGE_ITEMS,
                "prepared_batch_page_too_large",
                "prepared remote batch exceeds the 512-item action page limit",
            ),
            BatchAtomicity::WholeBatchRef => (
                MAX_WHOLE_BATCH_INTENTS,
                "prepared_whole_batch_too_large",
                "whole-batch publish exceeds the streaming path-key ceiling",
            ),
        };
        if intents.len() > ceiling {
            return Err(resource_limit(code, message));
        }
        let expected_snapshot_token = match atomicity {
            BatchAtomicity::PerPath => None,
            BatchAtomicity::WholeBatchRef => expected_snapshot_token,
        };
        Ok(Self {
            atomicity,
            intents,
            expected_snapshot_token,
        })
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

    /// Counts `Hold` intents (required mutations blocked: no strong validator).
    #[must_use]
    pub fn hold_count(&self) -> usize {
        self.intents
            .iter()
            .filter(|intent| matches!(intent, ProviderNeutralIntent::Hold { .. }))
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

/// What the driver expects remote state to look like for one path at verify time.
///
/// Verification is token-first: when `expected_token` still matches the provider's observed
/// validator, the path is verified without re-reading the body. A stream GET + digest compare is
/// reserved for paths whose token is unknown, weak, or changed.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct VerifyExpectation {
    pub path: SyncPath,
    /// Expected remote content digest after apply; `None` expects the path absent.
    pub expected_digest: Option<ContentDigest>,
    /// Strong validator observed at listing or publish time, when available.
    pub expected_token: Option<String>,
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
