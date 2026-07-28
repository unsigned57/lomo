//! Fake-friendly local and remote ports for hermetic state-machine tests (P5-03/P5-04).
//!
//! Production provider adapters (WebDAV/S3/Git) land in later packages. Local mutations always go
//! through `lomo-store` expected-revision `LocalSyncMutationBatch` ports — this module only bridges
//! coarse snapshot facts into the planner and never writes user Markdown/media.

use std::collections::BTreeMap;
use std::sync::Mutex;

use sha2::{Digest, Sha256};

use crate::error::validation;
use crate::limits::MAX_ACTION_PAGE_ITEMS;
use crate::pipeline::{
    ContentDigest, PreparedRemoteBatch, ProviderNeutralIntent, PublishReceipt, RemotePathEntry,
    RemoteSnapshot, SnapshotCompleteness, SyncPath, VerifiedRemoteState,
};
use lomo_core::LomoError;

/// Local workspace facts visible to the planner (path + digest only; no full-byte load).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LocalPathEntry {
    pub path: SyncPath,
    pub digest: ContentDigest,
}

/// Snapshot of local sync-relevant paths.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LocalSnapshot {
    pub entries: Vec<LocalPathEntry>,
    /// Durable workspace generation fence when the local owner supplies one.
    pub workspace_generation: Option<String>,
}

/// Read-only local port used by the planner.
pub trait LocalSyncPort {
    /// Loads a path/digest local snapshot.
    ///
    /// # Errors
    ///
    /// Storage / validation errors from the local owner.
    fn snapshot(&self) -> Result<LocalSnapshot, LomoError>;
}

/// Remote transport port: list + execute prepared batch + verify.
pub trait RemoteSyncPort {
    /// Lists remote objects (may be incomplete).
    ///
    /// Default single-shot path remains page-bounded via [`RemoteSnapshot::new`]. Host residual
    /// multi-page listings use [`Self::list_remote_pages`] so the cycle never materializes a
    /// multi-page payload into one `RemoteSnapshot`.
    ///
    /// # Errors
    ///
    /// Network / validation errors from the adapter.
    fn list_remote(&self) -> Result<RemoteSnapshot, LomoError>;

    /// Streams remote listing pages (each page ≤ action page ceiling) without multi-page materialize.
    ///
    /// Default falls back to one page from [`Self::list_remote`] so existing adapters keep compiling.
    /// Host residual fakes and future production adapters override to yield real pages.
    ///
    /// # Errors
    ///
    /// Network / validation / resource-limit errors from the adapter or page construction.
    fn list_remote_pages(&self) -> Result<RemoteListingStream, LomoError> {
        let snap = self.list_remote()?;
        Ok(RemoteListingStream::from_single_snapshot(snap))
    }

    /// Executes a prepared batch (conditional writes only).
    ///
    /// # Errors
    ///
    /// Network / precondition / validation errors.
    fn publish(&self, batch: &PreparedRemoteBatch) -> Result<PublishReceipt, LomoError>;

    /// Re-reads remote state for applied paths (verify before baseline).
    ///
    /// # Errors
    ///
    /// Network / validation errors.
    fn verify(&self, paths: &[SyncPath]) -> Result<VerifiedRemoteState, LomoError>;
}

/// One remote listing stream: overall completeness + ordered page buffers (≤512 each).
///
/// Pages are consumed by the streaming planner; only path keys are retained across pages.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RemoteListingStream {
    /// Overall completeness of the multi-page listing (Complete only when the full set is known).
    pub overall_completeness: SnapshotCompleteness,
    /// Page buffers already page-bounded (never a multi-page materialize of all entries).
    pub pages: Vec<Vec<RemotePathEntry>>,
}

impl RemoteListingStream {
    /// Builds a stream from a single already page-bounded snapshot (default adapter fallback).
    #[must_use]
    pub fn from_single_snapshot(snapshot: RemoteSnapshot) -> Self {
        let overall_completeness = snapshot.completeness;
        let pages = if snapshot.entries.is_empty() {
            Vec::new()
        } else {
            vec![snapshot.entries]
        };
        Self {
            overall_completeness,
            pages,
        }
    }

    /// Builds a multi-page stream after validating each page size.
    ///
    /// # Errors
    ///
    /// Resource-limit when any page exceeds the action page ceiling.
    pub fn from_pages(
        overall_completeness: SnapshotCompleteness,
        pages: Vec<Vec<RemotePathEntry>>,
    ) -> Result<Self, LomoError> {
        for page in &pages {
            if page.len() > MAX_ACTION_PAGE_ITEMS {
                return Err(crate::error::resource_limit(
                    "remote_snapshot_page_too_large",
                    "streaming remote page exceeds the 512-item action page limit",
                ));
            }
        }
        Ok(Self {
            overall_completeness,
            pages,
        })
    }

    /// Yields page results for [`crate::machine::plan_intents_streaming`].
    pub fn into_page_iter(self) -> impl Iterator<Item = Result<Vec<RemotePathEntry>, LomoError>> {
        self.pages.into_iter().map(Ok)
    }
}

/// In-memory fake local port for hermetic contracts.
#[derive(Clone, Debug, Default)]
pub struct FakeLocalPort {
    pub entries: Vec<LocalPathEntry>,
}

impl LocalSyncPort for FakeLocalPort {
    fn snapshot(&self) -> Result<LocalSnapshot, LomoError> {
        Ok(LocalSnapshot {
            entries: self.entries.clone(),
            workspace_generation: None,
        })
    }
}

/// Read-only bridge from `lomo-store` coarse snapshot facts into the provider-neutral planner.
///
/// The bridge deliberately owns copied path/digest facts only. It cannot open `SQLite`, read memo
/// bodies, or mutate the workspace, so `lomo-store` remains the sole local authority.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct StoreLocalSnapshotPort {
    snapshot: LocalSnapshot,
}

impl StoreLocalSnapshotPort {
    /// Builds a local port from one generation-fenced store snapshot.
    ///
    /// # Errors
    ///
    /// Validation when the generation is empty or any path/digest is invalid.
    pub fn from_store_snapshot<I, P, D>(
        workspace_generation: &str,
        entries: I,
    ) -> Result<Self, LomoError>
    where
        I: IntoIterator<Item = (P, D)>,
        P: Into<String>,
        D: Into<String>,
    {
        if workspace_generation.is_empty() {
            return Err(validation(
                "local_snapshot_generation_empty",
                "store local snapshot requires a workspace generation fence",
            ));
        }
        let entries = entries
            .into_iter()
            .map(|(path, digest)| {
                Ok(LocalPathEntry {
                    path: SyncPath::parse(&path.into())?,
                    digest: ContentDigest::parse(&digest.into())?,
                })
            })
            .collect::<Result<Vec<_>, LomoError>>()?;
        Ok(Self {
            snapshot: LocalSnapshot {
                entries,
                workspace_generation: Some(workspace_generation.to_owned()),
            },
        })
    }

    /// Returns the copied planner snapshot without requiring callers to import the port trait.
    ///
    /// # Errors
    ///
    /// This in-memory bridge cannot fail after construction; the result type keeps the same
    /// boundary as [`LocalSyncPort::snapshot`].
    pub fn snapshot(&self) -> Result<LocalSnapshot, LomoError> {
        Ok(self.snapshot.clone())
    }
}

impl LocalSyncPort for StoreLocalSnapshotPort {
    fn snapshot(&self) -> Result<LocalSnapshot, LomoError> {
        Ok(self.snapshot.clone())
    }
}

/// In-memory object source for hermetic `EnsurePresent` body binding (path → candidate bytes).
///
/// Mirrors adapter `*ObjectSource` contracts: load fails closed when the path is unknown or when
/// SHA-256(bytes) ≠ the intent digest.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct MapRemoteObjectSource {
    pub objects: BTreeMap<String, Vec<u8>>,
}

impl MapRemoteObjectSource {
    /// Builds an empty map source.
    #[must_use]
    pub const fn empty() -> Self {
        Self {
            objects: BTreeMap::new(),
        }
    }

    /// Builds a map source from `(path, bytes)` pairs.
    #[must_use]
    pub fn from_entries<I, P>(entries: I) -> Self
    where
        I: IntoIterator<Item = (P, Vec<u8>)>,
        P: Into<String>,
    {
        let mut objects = BTreeMap::new();
        for (path, bytes) in entries {
            objects.insert(path.into(), bytes);
        }
        Self { objects }
    }

    /// Inserts or replaces bytes for a path.
    pub fn insert(&mut self, path: impl Into<String>, bytes: Vec<u8>) {
        self.objects.insert(path.into(), bytes);
    }

    /// Loads body bytes only when SHA-256 matches the intent digest (fail-closed).
    ///
    /// # Errors
    ///
    /// Validation when the path is missing or the digest does not match the body.
    pub fn load_bytes(
        &self,
        path: &SyncPath,
        expected_digest: &ContentDigest,
    ) -> Result<Vec<u8>, LomoError> {
        let bytes = self.objects.get(path.as_str()).ok_or_else(|| {
            validation(
                "fake_remote_object_source_missing",
                "fake remote object source has no bytes for the ensure-present path",
            )
        })?;
        let digest = format!("{:x}", Sha256::digest(bytes));
        if digest != expected_digest.as_str() {
            return Err(validation(
                "fake_remote_object_source_digest_mismatch",
                "fake remote object source digest does not match the ensure-present intent",
            ));
        }
        Ok(bytes.clone())
    }
}

/// One successful `EnsurePresent` publish observed by the fake remote (body-digest coupled).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct FakePublishedBody {
    pub path: String,
    pub digest: String,
    pub body: Vec<u8>,
}

/// In-memory fake remote port for hermetic contracts.
///
/// When `objects` is non-empty **or** `require_body_for_ensure_present` is true, every
/// `EnsurePresent` intent must load body bytes whose SHA-256 equals the intent digest before the
/// canned receipt is returned. Empty batches and non-present intents never require a body.
///
/// Host residual streaming: when `listing_pages` is set, [`RemoteSyncPort::list_remote_pages`]
/// yields those pages (each ≤512) without multi-page materialize into one snapshot.
#[derive(Debug)]
pub struct FakeRemotePort {
    pub snapshot: RemoteSnapshot,
    pub publish_receipt: PublishReceipt,
    pub verify_state: VerifiedRemoteState,
    pub objects: MapRemoteObjectSource,
    /// When true, `EnsurePresent` fails closed unless a digest-matched body is available even if
    /// the object map is empty (explicit body-wire mode for conflict apply contracts).
    pub require_body_for_ensure_present: bool,
    /// Optional multi-page listing for host residual streaming cycle (pages already ≤512 each).
    pub listing_pages: Option<RemoteListingStream>,
    pub publish_calls: Mutex<u32>,
    pub verify_calls: Mutex<u32>,
    pub list_pages_calls: Mutex<u32>,
    pub published_bodies: Mutex<Vec<FakePublishedBody>>,
}

impl FakeRemotePort {
    /// Builds a fake remote with fixed snapshot / receipt / verify fixtures (no body binding).
    ///
    /// Prefer [`Self::with_objects`] when exercising `EnsurePresent` publish paths that must bind
    /// real candidate bodies (conflict KeepLocal/Merged apply).
    #[must_use]
    pub const fn new(
        snapshot: RemoteSnapshot,
        publish_receipt: PublishReceipt,
        verify_state: VerifiedRemoteState,
    ) -> Self {
        Self {
            snapshot,
            publish_receipt,
            verify_state,
            objects: MapRemoteObjectSource::empty(),
            require_body_for_ensure_present: false,
            listing_pages: None,
            publish_calls: Mutex::new(0),
            verify_calls: Mutex::new(0),
            list_pages_calls: Mutex::new(0),
            published_bodies: Mutex::new(Vec::new()),
        }
    }

    /// Builds a fake remote that validates `EnsurePresent` body digests against `objects`.
    #[must_use]
    pub fn with_objects(
        snapshot: RemoteSnapshot,
        publish_receipt: PublishReceipt,
        verify_state: VerifiedRemoteState,
        objects: MapRemoteObjectSource,
    ) -> Self {
        let mut port = Self::new(snapshot, publish_receipt, verify_state);
        port.objects = objects;
        port.require_body_for_ensure_present = true;
        port
    }

    /// Attaches a multi-page listing stream (host residual streaming cycle).
    ///
    /// [`RemoteSyncPort::list_remote`] still returns the single-shot `snapshot` field (page-bounded).
    /// Streaming residual cycle uses [`RemoteSyncPort::list_remote_pages`].
    #[must_use]
    pub fn with_listing_pages(mut self, stream: RemoteListingStream) -> Self {
        self.listing_pages = Some(stream);
        self
    }

    /// Forces body validation for every `EnsurePresent` even when the object map is empty.
    #[must_use]
    pub const fn requiring_body(mut self) -> Self {
        self.require_body_for_ensure_present = true;
        self
    }

    #[must_use]
    pub fn publish_call_count(&self) -> u32 {
        self.publish_calls.lock().map_or(0, |guard| *guard)
    }

    #[must_use]
    pub fn verify_call_count(&self) -> u32 {
        self.verify_calls.lock().map_or(0, |guard| *guard)
    }

    #[must_use]
    pub fn list_pages_call_count(&self) -> u32 {
        self.list_pages_calls.lock().map_or(0, |guard| *guard)
    }

    /// Returns bodies accepted by the last successful `EnsurePresent` publishes (digest-coupled).
    #[must_use]
    pub fn published_bodies(&self) -> Vec<FakePublishedBody> {
        self.published_bodies
            .lock()
            .map_or_else(|_| Vec::new(), |guard| guard.clone())
    }
}

impl RemoteSyncPort for FakeRemotePort {
    fn list_remote(&self) -> Result<RemoteSnapshot, LomoError> {
        Ok(self.snapshot.clone())
    }

    fn list_remote_pages(&self) -> Result<RemoteListingStream, LomoError> {
        if let Ok(mut guard) = self.list_pages_calls.lock() {
            *guard = guard.saturating_add(1);
        }
        if let Some(pages) = self.listing_pages.as_ref() {
            return Ok(pages.clone());
        }
        Ok(RemoteListingStream::from_single_snapshot(
            self.snapshot.clone(),
        ))
    }

    fn publish(&self, batch: &PreparedRemoteBatch) -> Result<PublishReceipt, LomoError> {
        let must_bind_body =
            self.require_body_for_ensure_present || !self.objects.objects.is_empty();
        if must_bind_body {
            let mut accepted = Vec::new();
            for intent in &batch.intents {
                if let ProviderNeutralIntent::EnsurePresent { path, digest, .. } = intent {
                    let body = self.objects.load_bytes(path, digest)?;
                    accepted.push(FakePublishedBody {
                        path: path.as_str().to_owned(),
                        digest: digest.as_str().to_owned(),
                        body,
                    });
                }
            }
            if let Ok(mut guard) = self.published_bodies.lock() {
                *guard = accepted;
            }
        }
        if let Ok(mut guard) = self.publish_calls.lock() {
            *guard = guard.saturating_add(1);
        }
        // Page-scoped honesty: return only receipt rows whose path is in this batch.
        // Full canned multi-page fixtures remain valid; each publish sees its page slice only.
        let batch_paths: BTreeMap<&str, ()> = batch
            .intents
            .iter()
            .map(|intent| match intent {
                ProviderNeutralIntent::EnsurePresent { path, .. }
                | ProviderNeutralIntent::EnsureAbsent { path, .. }
                | ProviderNeutralIntent::PullPresent { path, .. }
                | ProviderNeutralIntent::OpenConflict { path, .. }
                | ProviderNeutralIntent::ReportUnrecognized { path } => (path.as_str(), ()),
            })
            .collect();
        let path_results = self
            .publish_receipt
            .path_results
            .iter()
            .filter(|(path, _status)| batch_paths.contains_key(path.as_str()))
            .cloned()
            .collect();
        Ok(PublishReceipt { path_results })
    }

    fn verify(&self, paths: &[SyncPath]) -> Result<VerifiedRemoteState, LomoError> {
        if let Ok(mut guard) = self.verify_calls.lock() {
            *guard = guard.saturating_add(1);
        }
        // Page-scoped honesty: filter canned verify fixtures to the requested paths only.
        // Empty request → empty results (domain-empty; not a silent full-fixture dump).
        if paths.is_empty() {
            return Ok(VerifiedRemoteState {
                results: Vec::new(),
            });
        }
        let wanted: BTreeMap<&str, ()> = paths.iter().map(|path| (path.as_str(), ())).collect();
        let results = self
            .verify_state
            .results
            .iter()
            .filter(|status| wanted.contains_key(verify_status_path(status)))
            .cloned()
            .collect();
        Ok(VerifiedRemoteState { results })
    }
}

fn verify_status_path(status: &crate::pipeline::VerifyStatus) -> &str {
    match status {
        crate::pipeline::VerifyStatus::Verified { path, .. }
        | crate::pipeline::VerifyStatus::AbsentVerified { path }
        | crate::pipeline::VerifyStatus::Failed { path, .. } => path.as_str(),
    }
}
