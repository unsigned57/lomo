//! `RemoteSyncPort` implementation for Git (`git2` adapter only; no Git-specific planner).

use std::collections::BTreeMap;
use std::io::Read;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use git2::{
    Cred, ErrorCode, FetchOptions, FileMode, ObjectType, Oid, PushOptions, RemoteCallbacks,
    Repository, Signature, TreeWalkMode, TreeWalkResult, build::TreeUpdateBuilder,
};
use sha2::{Digest, Sha256};

use crate::endpoint::{GitCredentials, GitEndpoint, GitObjectSource, GitObjectStream};
use crate::error::{conflict, from_git2, network, resource_limit, storage, validation};
use crate::lock::{DEFAULT_STALE_LOCK_THRESHOLD, ensure_index_lock_clear};
use crate::mirror::open_local_repository;
use lomo_core::{LomoError, RetryDisposition};
use lomo_sync::{
    BatchAtomicity, ContentDigest, MAX_ACTION_PAGE_ITEMS, PathPublishStatus, PreparedRemoteBatch,
    ProviderNeutralIntent, PublishReceipt, RemoteCapabilities, RemoteDigestFact,
    RemoteListingStream, RemotePathEntry, RemoteResolvedObject, RemoteSnapshot, RemoteSyncPort,
    RemoteValidator, SnapshotCompleteness, SyncPath, VerifiedRemoteState, VerifyExpectation,
    VerifyStatus,
};

/// Workspace objects embedded into Git trees stay under the sync object bound.
const MAX_GIT_OBJECT_BYTES: u64 = 32 * 1_048_576;

/// Total object bytes one publish may stream into the ODB (eight objects' worth at the bound).
const MAX_GIT_BATCH_OBJECT_BYTES: u64 = 8 * MAX_GIT_OBJECT_BYTES;

/// Total bytes the app-private mirror / local object store may hold (2 GiB disk bound).
const MAX_GIT_MIRROR_BYTES: u64 = 2 * 1_073_741_824;

/// Push staging namespace; leased per publish and reconciled at connect.
const PUSH_STAGING_GLOB: &str = "refs/lomo/push/*";

/// The remote tip pinned for a cycle.
#[derive(Default)]
enum CycleTip {
    /// No remote query has run yet this cycle.
    #[default]
    Unfetched,
    /// The remote tip observed by this cycle's single fetch (or the pushed commit).
    Known(Option<Oid>),
}

/// Cycle-scoped remote state shared by every port call on one adapter instance.
///
/// The repository handle and the fetched remote tip live under one lock so list/publish/verify
/// observe one snapshot lifetime: the first port call fetches once, later calls reuse the pinned
/// tip, and a successful publish records its commit as the new tip.
#[derive(Default)]
struct RemoteCycle {
    repo: Option<Repository>,
    fetched_tip: CycleTip,
}

/// Adapter execution counters (diagnostics for the cycle's real cost).
#[derive(Default)]
struct AdapterStats {
    /// Remote fetch operations executed this cycle.
    fetches: AtomicUsize,
    /// Remote tip queries via `ls-remote` style connect+list (no object transfer).
    remote_tip_queries: AtomicUsize,
    /// Commit-tree walks executed for listings/verifies.
    tree_walks: AtomicUsize,
    /// Workspace object bytes streamed into the ODB.
    object_bytes: AtomicU64,
    /// Local object-store disk estimate; `u64::MAX` means "not yet measured this cycle".
    disk_bytes: AtomicU64,
}

/// Public snapshot of [`AdapterStats`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GitAdapterStats {
    /// Remote fetch operations executed this cycle.
    pub fetches: usize,
    /// `ls-remote` style tip queries (no object transfer).
    pub remote_tip_queries: usize,
    /// Commit-tree walks executed for listings/verifies.
    pub tree_walks: usize,
    /// Workspace object bytes streamed into the ODB.
    pub object_bytes: u64,
}

/// Git remote adapter implementing the public [`RemoteSyncPort`].
///
/// Compiles path intents into tree/commit + non-force CAS ref push (`WholeBatchRef`).
/// Publishes are single-parent on the enumerated remote tip; concurrent remote advances surface
/// as precondition failures for the planner to re-plan. Never force-pushes, never
/// checkout/resets user worktrees.
pub struct GitAdapter<S: GitObjectSource> {
    endpoint: GitEndpoint,
    credentials: GitCredentials,
    objects: S,
    author_name: String,
    author_email: String,
    timeout: Duration,
    stale_lock_threshold: Duration,
    cycle: Mutex<RemoteCycle>,
    stats: AdapterStats,
    max_object_bytes: u64,
    batch_object_budget: u64,
    mirror_disk_budget: u64,
}

impl<S: GitObjectSource> GitAdapter<S> {
    /// Constructs a dark-host Git adapter (not production DI).
    ///
    /// # Errors
    ///
    /// Local open/init failures.
    pub fn connect(
        endpoint: GitEndpoint,
        credentials: GitCredentials,
        objects: S,
        author_name: impl Into<String>,
        author_email: impl Into<String>,
        timeout: Duration,
    ) -> Result<Self, LomoError> {
        let repo = open_local_repository(endpoint.local())?;
        // Startup reconcile: leftover publish-staging refs from a crashed cycle are reclaimed.
        reconcile_push_refs(&repo)?;
        Ok(Self {
            endpoint,
            credentials,
            objects,
            author_name: author_name.into(),
            author_email: author_email.into(),
            timeout,
            stale_lock_threshold: DEFAULT_STALE_LOCK_THRESHOLD,
            cycle: Mutex::new(RemoteCycle::default()),
            stats: AdapterStats {
                disk_bytes: AtomicU64::new(u64::MAX),
                ..AdapterStats::default()
            },
            max_object_bytes: MAX_GIT_OBJECT_BYTES,
            batch_object_budget: MAX_GIT_BATCH_OBJECT_BYTES,
            mirror_disk_budget: MAX_GIT_MIRROR_BYTES,
        })
    }

    /// Diagnostic counters for this adapter's cycle.
    #[must_use]
    pub fn stats(&self) -> GitAdapterStats {
        GitAdapterStats {
            fetches: self.stats.fetches.load(Ordering::Acquire),
            remote_tip_queries: self.stats.remote_tip_queries.load(Ordering::Acquire),
            tree_walks: self.stats.tree_walks.load(Ordering::Acquire),
            object_bytes: self.stats.object_bytes.load(Ordering::Acquire),
        }
    }

    /// Test-only: override stale-lock threshold.
    #[must_use]
    pub const fn with_stale_lock_threshold(mut self, threshold: Duration) -> Self {
        self.stale_lock_threshold = threshold;
        self
    }

    /// Test-only: shrink the per-object byte budget.
    #[must_use]
    pub const fn with_object_budget(mut self, bytes: u64) -> Self {
        self.max_object_bytes = bytes;
        self
    }

    /// Test-only: shrink the per-batch object byte budget.
    #[must_use]
    pub const fn with_batch_object_budget(mut self, bytes: u64) -> Self {
        self.batch_object_budget = bytes;
        self
    }

    /// Test-only: shrink the local mirror disk budget.
    #[must_use]
    pub const fn with_mirror_disk_budget(mut self, bytes: u64) -> Self {
        self.mirror_disk_budget = bytes;
        self
    }

    /// Runs `f` against the cycle's repository handle (opened once, lock-protected).
    fn with_repo<R>(
        &self,
        f: impl FnOnce(&Repository) -> Result<R, LomoError>,
    ) -> Result<R, LomoError> {
        let mut guard = self
            .cycle
            .lock()
            .map_err(|error| storage("git_cycle_lock_poisoned", &error.to_string()))?;
        if guard.repo.is_none() {
            guard.repo = Some(open_local_repository(self.endpoint.local())?);
        }
        let repo = guard
            .repo
            .as_ref()
            .ok_or_else(|| storage("git_cycle_repo_missing", "cycle repository was not opened"))?;
        let output = f(repo);
        drop(guard);
        output
    }

    /// The remote tip pinned for this cycle: first call fetches once, later calls reuse it.
    fn cycle_tip(&self) -> Result<Option<Oid>, LomoError> {
        let mut guard = self
            .cycle
            .lock()
            .map_err(|error| storage("git_cycle_lock_poisoned", &error.to_string()))?;
        if guard.repo.is_none() {
            guard.repo = Some(open_local_repository(self.endpoint.local())?);
        }
        if let CycleTip::Known(tip) = guard.fetched_tip {
            return Ok(tip);
        }
        let repo = guard
            .repo
            .as_ref()
            .ok_or_else(|| storage("git_cycle_repo_missing", "cycle repository was not opened"))?;
        self.ensure_lock_clear(repo)?;
        self.fetch_remote(repo)?;
        let tip = self.resolve_remote_tip(repo)?;
        guard.fetched_tip = CycleTip::Known(tip);
        drop(guard);
        Ok(tip)
    }

    /// Records the pushed commit as this cycle's remote tip after a successful publish.
    fn record_published_tip(&self, commit: Oid) {
        if let Ok(mut guard) = self.cycle.lock() {
            guard.fetched_tip = CycleTip::Known(Some(commit));
        }
    }

    fn ensure_lock_clear(&self, repo: &Repository) -> Result<(), LomoError> {
        ensure_index_lock_clear(
            repo.path(),
            self.stale_lock_threshold,
            std::time::SystemTime::now(),
        )
    }

    fn network_deadline(&self) -> Instant {
        Instant::now() + self.timeout
    }

    /// Fails when the configured network timeout has already elapsed.
    fn check_deadline(deadline: Instant) -> Result<(), LomoError> {
        if Instant::now() >= deadline {
            return Err(network(
                "git_deadline_exceeded",
                "git network operation exceeded the configured timeout",
                RetryDisposition::Transient,
            ));
        }
        Ok(())
    }

    /// Credentials + deadline-aware progress callbacks: transfers cancel past the deadline.
    fn remote_callbacks(&self, deadline: Instant) -> RemoteCallbacks<'_> {
        let mut callbacks = RemoteCallbacks::new();
        if self.credentials.has_secret() {
            let username = self.credentials.username().to_owned();
            let token = self.credentials.token().to_owned();
            callbacks.credentials(move |_url, _username_from_url, _allowed| {
                Cred::userpass_plaintext(&username, &token)
            });
        }
        callbacks.transfer_progress(move |_progress| Instant::now() < deadline);
        callbacks
    }

    /// Ensures `origin` exists and points at the configured remote URL, then returns it.
    fn origin_remote<'repo>(
        &self,
        repo: &'repo Repository,
    ) -> Result<git2::Remote<'repo>, LomoError> {
        let remote_name = "origin";
        match repo.find_remote(remote_name) {
            Ok(remote) => {
                let matches = remote
                    .url()
                    .map_err(|error| from_git2("git_remote_url_read_failed", &error))?
                    == self.endpoint.remote_url();
                if !matches {
                    repo.remote_set_url(remote_name, self.endpoint.remote_url())
                        .map_err(|error| from_git2("git_remote_set_url_failed", &error))?;
                }
            }
            Err(_) => {
                repo.remote(remote_name, self.endpoint.remote_url())
                    .map_err(|error| from_git2("git_remote_create_failed", &error))?;
            }
        }
        repo.find_remote(remote_name)
            .map_err(|error| from_git2("git_remote_find_failed", &error))
    }

    /// Queries the live remote tip for the configured branch without transferring objects.
    fn live_remote_tip(&self, repo: &Repository) -> Result<Option<Oid>, LomoError> {
        let deadline = self.network_deadline();
        Self::check_deadline(deadline)?;
        let mut remote = self.origin_remote(repo)?;
        remote
            .connect(git2::Direction::Fetch)
            .map_err(|error| from_git2("git_remote_connect_failed", &error))?;
        Self::check_deadline(deadline)?;
        self.stats.remote_tip_queries.fetch_add(1, Ordering::AcqRel);
        let branch_ref = self.endpoint.branch_ref();
        let tip = remote
            .list()
            .map_err(|error| from_git2("git_remote_list_failed", &error))?
            .iter()
            .find(|head| head.name() == branch_ref)
            .map(git2::RemoteHead::oid);
        Self::check_deadline(deadline)?;
        Ok(tip)
    }

    /// Fetch remote branch into `refs/remotes/origin/{branch}`.
    fn fetch_remote(&self, repo: &Repository) -> Result<(), LomoError> {
        let deadline = self.network_deadline();
        Self::check_deadline(deadline)?;
        let mut remote = self.origin_remote(repo)?;
        let mut fetch_opts = FetchOptions::new();
        fetch_opts.remote_callbacks(self.remote_callbacks(deadline));
        // Leading `+` updates the *remote-tracking* ref only; publish push is non-force.
        let refspec = format!(
            "+{}:refs/remotes/origin/{}",
            self.endpoint.branch_ref(),
            self.endpoint.branch()
        );
        self.stats.fetches.fetch_add(1, Ordering::AcqRel);
        let result = remote.fetch(&[refspec.as_str()], Some(&mut fetch_opts), None);
        Self::check_deadline(deadline)?;
        result.map_err(|error| from_git2("git_fetch_failed", &error))
    }

    fn remote_tracking_ref(&self) -> String {
        format!("refs/remotes/origin/{}", self.endpoint.branch())
    }

    fn resolve_remote_tip(&self, repo: &Repository) -> Result<Option<Oid>, LomoError> {
        let tracking = self.remote_tracking_ref();
        match repo.refname_to_id(&tracking) {
            Ok(oid) => Ok(Some(oid)),
            Err(error) if error.code() == ErrorCode::NotFound => Ok(None),
            Err(error) => Err(from_git2("git_remote_tip_resolve_failed", &error)),
        }
    }

    fn tree_entries_from_commit(
        &self,
        repo: &Repository,
        commit_oid: Oid,
    ) -> Result<Vec<RemotePathEntry>, LomoError> {
        self.stats.tree_walks.fetch_add(1, Ordering::AcqRel);
        let commit = repo
            .find_commit(commit_oid)
            .map_err(|error| from_git2("git_commit_lookup_failed", &error))?;
        let tree = commit
            .tree()
            .map_err(|error| from_git2("git_tree_lookup_failed", &error))?;
        let mut entries = Vec::new();
        let walk_result = tree.walk(TreeWalkMode::PreOrder, |root, entry| {
            if entry.kind() != Some(ObjectType::Blob) {
                return TreeWalkResult::Ok;
            }
            let Ok(name) = entry.name() else {
                return TreeWalkResult::Ok;
            };
            let path_str = if root.is_empty() {
                name.to_owned()
            } else {
                format!("{root}{name}")
            };
            let Ok(sync_path) = SyncPath::parse(&path_str) else {
                return TreeWalkResult::Ok;
            };
            // Metadata-only listing: the blob OID is the strong conditional-update validator;
            // blob bytes stay unread until on-demand digest resolution.
            entries.push(RemotePathEntry {
                path: sync_path,
                digest: RemoteDigestFact::Unresolved,
                validator: RemoteValidator::Strong(entry.id().to_string()),
            });
            TreeWalkResult::Ok
        });
        walk_result.map_err(|error| from_git2("git_tree_walk_failed", &error))?;
        Ok(entries)
    }

    /// Token-first verify for one path against the fetched remote tip tree.
    ///
    /// A blob OID equal to `expected_token` verifies without a blob re-read (receipt + validator
    /// equality is the postcondition); otherwise the blob is read once and digests compared.
    fn verify_expectation(
        repo: &Repository,
        tree: Option<&git2::Tree<'_>>,
        expectation: &VerifyExpectation,
    ) -> VerifyStatus {
        let path = &expectation.path;
        let entry = match tree {
            None => None,
            Some(tree) => match tree.get_path(std::path::Path::new(path.as_str())) {
                Ok(entry) => Some(entry),
                Err(error) if error.code() == ErrorCode::NotFound => None,
                Err(_error) => {
                    return VerifyStatus::Failed {
                        path: path.clone(),
                        code: "git_tree_path_lookup_failed".to_owned(),
                    };
                }
            },
        };
        let Some(entry) = entry else {
            return match expectation.expected_digest {
                Some(_) => VerifyStatus::Failed {
                    path: path.clone(),
                    code: "verify_expected_present_missing".to_owned(),
                },
                None => VerifyStatus::AbsentVerified { path: path.clone() },
            };
        };
        let Some(expected_digest) = expectation.expected_digest.as_ref() else {
            return VerifyStatus::Failed {
                path: path.clone(),
                code: "verify_expected_absent_present".to_owned(),
            };
        };
        let observed_token = entry.id().to_string();
        if expectation.expected_token.as_deref() == Some(observed_token.as_str()) {
            return VerifyStatus::Verified {
                path: path.clone(),
                digest: expected_digest.clone(),
                remote_token: observed_token,
            };
        }
        match repo.find_blob(entry.id()) {
            Ok(blob) => {
                if u64::try_from(blob.size()).unwrap_or(u64::MAX) > MAX_GIT_OBJECT_BYTES {
                    return VerifyStatus::Failed {
                        path: path.clone(),
                        code: "git_object_exceeds_budget".to_owned(),
                    };
                }
                let digest_hex = format!("{:x}", Sha256::digest(blob.content()));
                if digest_hex == expected_digest.as_str() {
                    VerifyStatus::Verified {
                        path: path.clone(),
                        digest: expected_digest.clone(),
                        remote_token: observed_token,
                    }
                } else {
                    VerifyStatus::Failed {
                        path: path.clone(),
                        code: "verify_digest_mismatch".to_owned(),
                    }
                }
            }
            Err(_error) => VerifyStatus::Failed {
                path: path.clone(),
                code: "git_blob_lookup_failed".to_owned(),
            },
        }
    }

    /// Reads one remote blob at the cycle's pinned remote tip: `(body bytes, blob oid)` when
    /// present.
    fn read_remote_blob(&self, path: &SyncPath) -> Result<Option<(Vec<u8>, String)>, LomoError> {
        let Some(tip) = self.cycle_tip()? else {
            return Ok(None);
        };
        self.with_repo(|repo| {
            let commit = repo
                .find_commit(tip)
                .map_err(|error| from_git2("git_commit_lookup_failed", &error))?;
            let tree = commit
                .tree()
                .map_err(|error| from_git2("git_tree_lookup_failed", &error))?;
            let entry = match tree.get_path(std::path::Path::new(path.as_str())) {
                Ok(entry) => entry,
                Err(error) if error.code() == ErrorCode::NotFound => return Ok(None),
                Err(error) => {
                    return Err(from_git2("git_tree_path_lookup_failed", &error));
                }
            };
            let blob = repo
                .find_blob(entry.id())
                .map_err(|error| from_git2("git_blob_lookup_failed", &error))?;
            let size = u64::try_from(blob.size())
                .map_err(|error| validation("git_blob_size_overflow", &error.to_string()))?;
            if size > self.max_object_bytes {
                return Err(resource_limit(
                    "git_object_exceeds_budget",
                    "remote blob exceeds the per-object byte bound",
                ));
            }
            Ok(Some((blob.content().to_vec(), entry.id().to_string())))
        })
    }

    fn apply_intents_to_tree(
        &self,
        repo: &Repository,
        baseline_tree_oid: Option<Oid>,
        intents: &[ProviderNeutralIntent],
    ) -> Result<Oid, LomoError> {
        let mut upserts: BTreeMap<String, Oid> = BTreeMap::new();
        let mut removes: Vec<String> = Vec::new();
        let mut batch_used: u64 = 0;
        for intent in intents {
            match intent {
                ProviderNeutralIntent::EnsurePresent { path, digest, .. } => {
                    // behavior-contract: loop-io-ok: each EnsurePresent names a distinct required
                    // blob; ObjectSource has no bulk contract and tree build needs every body.
                    let oid = self.stream_object_to_odb(repo, path, digest, &mut batch_used)?;
                    upserts.insert(path.as_str().to_owned(), oid);
                }
                ProviderNeutralIntent::EnsureAbsent { path, .. } => {
                    removes.push(path.as_str().to_owned());
                }
                ProviderNeutralIntent::PullPresent { .. }
                | ProviderNeutralIntent::OpenConflict { .. }
                | ProviderNeutralIntent::ReportUnrecognized { .. }
                | ProviderNeutralIntent::Hold { .. } => {}
            }
        }

        if let Some(tree_oid) = baseline_tree_oid {
            let tree = repo
                .find_tree(tree_oid)
                .map_err(|error| from_git2("git_tree_lookup_failed", &error))?;
            if removes.is_empty() && upserts.is_empty() {
                return Ok(tree.id());
            }
            let mut updater = TreeUpdateBuilder::new();
            for path in &removes {
                updater.remove(path.as_str());
            }
            for (path, oid) in &upserts {
                updater.upsert(path.as_str(), *oid, FileMode::Blob);
            }
            updater
                .create_updated(repo, &tree)
                .map_err(|error| from_git2("git_tree_update_failed", &error))
        } else {
            build_tree_from_paths(repo, &upserts)
        }
    }

    /// Streams one workspace object into the ODB under the per-object and per-batch budgets.
    ///
    /// The source reports its length on the opened descriptor; over-budget objects reject before
    /// a single byte is read. Bytes stream through a SHA-256 tee into the ODB writer, so the
    /// digest check runs while bytes move — a length drift or digest mismatch surfaces before the
    /// OID is admitted into the tree.
    fn stream_object_to_odb(
        &self,
        repo: &Repository,
        path: &SyncPath,
        expected_digest: &ContentDigest,
        batch_used: &mut u64,
    ) -> Result<Oid, LomoError> {
        let GitObjectStream { len, mut reader } = self.objects.open_object(path)?;
        if len > self.max_object_bytes {
            return Err(resource_limit(
                "git_object_exceeds_budget",
                "git object exceeds the 32 MiB per-object bound",
            ));
        }
        if batch_used.saturating_add(len) > self.batch_object_budget {
            return Err(resource_limit(
                "git_batch_object_budget_exceeded",
                "git publish batch exceeds the object byte budget",
            ));
        }
        self.charge_mirror_disk(repo, len)?;
        let declared_size = usize::try_from(len)
            .map_err(|error| validation("git_object_size_overflow", &error.to_string()))?;
        let odb = repo
            .odb()
            .map_err(|error| from_git2("git_odb_open_failed", &error))?;
        let mut writer = odb
            .writer(declared_size, ObjectType::Blob)
            .map_err(|error| from_git2("git_odb_writer_failed", &error))?;
        let mut hasher = Sha256::new();
        let mut buffer = vec![0u8; 64 * 1024].into_boxed_slice();
        let mut written: u64 = 0;
        loop {
            let read = reader
                .read(&mut buffer)
                .map_err(|error| storage("git_object_source_read_failed", &error.to_string()))?;
            if read == 0 {
                break;
            }
            let Some(chunk) = buffer.get(..read) else {
                return Err(storage(
                    "git_object_source_read_overflow",
                    "object source returned more bytes than the stream buffer",
                ));
            };
            hasher.update(chunk);
            std::io::Write::write_all(&mut writer, chunk)
                .map_err(|error| storage("git_odb_stream_write_failed", &error.to_string()))?;
            written = written.saturating_add(u64::try_from(read).map_err(|error| {
                validation("git_object_stream_count_overflow", &error.to_string())
            })?);
        }
        if written != len {
            return Err(validation(
                "git_object_source_len_mismatch",
                "object source length drifted between open and stream end",
            ));
        }
        let digest_hex = format!("{:x}", hasher.finalize());
        if digest_hex != expected_digest.as_str() {
            return Err(validation(
                "git_object_source_digest_mismatch",
                "git object source digest does not match the ensure-present intent",
            ));
        }
        let oid = writer
            .finalize()
            .map_err(|error| from_git2("git_odb_stream_commit_failed", &error))?;
        *batch_used = batch_used.saturating_add(written);
        self.stats.object_bytes.fetch_add(written, Ordering::AcqRel);
        Ok(oid)
    }

    /// Charges `delta` bytes against the local object-store disk budget.
    ///
    /// The on-disk footprint is measured once per cycle by walking the repository dir, then
    /// tracked incrementally by streamed bytes — object writes never exceed the configured
    /// mirror budget.
    fn charge_mirror_disk(&self, repo: &Repository, delta: u64) -> Result<(), LomoError> {
        let mut used = self.stats.disk_bytes.load(Ordering::Acquire);
        if used == u64::MAX {
            used = git_dir_size(repo.path());
            self.stats.disk_bytes.store(used, Ordering::Release);
        }
        if used.saturating_add(delta) > self.mirror_disk_budget {
            return Err(resource_limit(
                "git_mirror_disk_budget_exceeded",
                "git local object store exceeds the disk budget",
            ));
        }
        self.stats.disk_bytes.fetch_add(delta, Ordering::AcqRel);
        Ok(())
    }

    fn commit_tree(
        &self,
        repo: &Repository,
        tree_oid: Oid,
        parents: &[Oid],
        message: &str,
    ) -> Result<Oid, LomoError> {
        let tree = repo
            .find_tree(tree_oid)
            .map_err(|error| from_git2("git_find_tree_failed", &error))?;
        let signature = Signature::now(&self.author_name, &self.author_email)
            .map_err(|error| from_git2("git_signature_failed", &error))?;
        let parent_commits: Result<Vec<_>, _> = parents
            .iter()
            .map(|oid| {
                repo.find_commit(*oid)
                    .map_err(|error| from_git2("git_parent_lookup_failed", &error))
            })
            .collect();
        let parent_commits = parent_commits?;
        let parent_refs: Vec<&git2::Commit<'_>> = parent_commits.iter().collect();
        // Do not update any local branch ref here — CAS push is the sole ref mutation authority.
        repo.commit(None, &signature, &signature, message, &tree, &parent_refs)
            .map_err(|error| from_git2("git_commit_failed", &error))
    }

    /// Non-force push of `commit` to `refs/heads/{branch}`; the staging ref is leased and always
    /// cleaned with an observed result. The remote's non-fast-forward rejection is the CAS.
    fn push_cas(&self, repo: &Repository, commit: Oid) -> Result<(), LomoError> {
        let deadline = self.network_deadline();
        Self::check_deadline(deadline)?;
        let branch_ref = self.endpoint.branch_ref();
        let push_src = format!("refs/lomo/push/{commit}");
        repo.reference(&push_src, commit, true, "lomo-git publish staging")
            .map_err(|error| from_git2("git_push_src_ref_failed", &error))?;

        let mut remote = self.origin_remote(repo)?;
        let mut push_opts = PushOptions::new();
        push_opts.remote_callbacks(self.remote_callbacks(deadline));
        // Non-force dest update: `src:dst` without leading `+`.
        let refspec = format!("{push_src}:{branch_ref}");
        let result = remote.push(&[refspec.as_str()], Some(&mut push_opts));
        // The staging lease is durable-state hygiene: cleanup failures always surface.
        cleanup_push_ref(repo, &push_src)?;
        Self::check_deadline(deadline)?;
        match result {
            Ok(()) => Ok(()),
            Err(error) if error.code() == ErrorCode::NotFastForward => Err(conflict(
                "git_push_rejected_not_fast_forward",
                "non-force push rejected (remote tip diverged)",
            )),
            Err(error) => {
                let msg = error.message().to_ascii_lowercase();
                if msg.contains("non-fast-forward")
                    || msg.contains("failed to update")
                    || msg.contains("rejected")
                {
                    Err(conflict(
                        "git_push_rejected",
                        "non-force push rejected by remote",
                    ))
                } else {
                    Err(from_git2("git_push_failed", &error))
                }
            }
        }
    }
}

impl<S: GitObjectSource> RemoteSyncPort for GitAdapter<S> {
    fn list_remote(&self) -> Result<RemoteSnapshot, LomoError> {
        let tip = self.cycle_tip()?;
        let snapshot_revision = tip.map(|oid| oid.to_string());
        let Some(commit_oid) = tip else {
            return RemoteSnapshot::with_snapshot_revision(
                SnapshotCompleteness::Complete,
                Vec::new(),
                None,
            );
        };
        let entries = self.with_repo(|repo| self.tree_entries_from_commit(repo, commit_oid))?;
        if entries.len() > MAX_ACTION_PAGE_ITEMS {
            let page = entries.into_iter().take(MAX_ACTION_PAGE_ITEMS).collect();
            return RemoteSnapshot::with_snapshot_revision(
                SnapshotCompleteness::Incomplete,
                page,
                snapshot_revision,
            );
        }
        RemoteSnapshot::with_snapshot_revision(
            SnapshotCompleteness::Complete,
            entries,
            snapshot_revision,
        )
    }

    fn list_remote_pages(&self) -> Result<RemoteListingStream, LomoError> {
        let tip = self.cycle_tip()?;
        let snapshot_revision = tip.map(|oid| oid.to_string());
        let entries = if let Some(commit_oid) = tip {
            self.with_repo(|repo| self.tree_entries_from_commit(repo, commit_oid))?
        } else {
            Vec::new()
        };
        let pages = if entries.is_empty() {
            Vec::new()
        } else {
            entries
                .chunks(MAX_ACTION_PAGE_ITEMS)
                .map(<[RemotePathEntry]>::to_vec)
                .collect()
        };
        RemoteListingStream::from_pages_with_revision(
            SnapshotCompleteness::Complete,
            pages,
            snapshot_revision,
        )
    }

    fn batch_atomicity(&self) -> BatchAtomicity {
        BatchAtomicity::WholeBatchRef
    }

    fn remote_capabilities(&self) -> Result<RemoteCapabilities, LomoError> {
        // Protocol-static: ref-tip CAS (non-force push) is the conditional write/delete;
        // ETag/move/copy are not part of the Git smart-protocol surface the adapter uses.
        Ok(RemoteCapabilities {
            conditional_write: true,
            conditional_delete: true,
            supports_move: false,
            supports_copy: false,
            supports_etag: false,
        })
    }

    fn publish(&self, batch: &PreparedRemoteBatch) -> Result<PublishReceipt, LomoError> {
        if batch.atomicity != BatchAtomicity::WholeBatchRef {
            return Err(validation(
                "git_batch_atomicity",
                "git adapter only executes WholeBatchRef batches",
            ));
        }
        self.cycle_tip()?;

        // CAS anchor: compare the planner's expected snapshot token against the *live* remote tip
        // (cheap connect+list, no object transfer). A concurrent remote advance fails closed as
        // PreconditionFailed before any tree work, so the planner re-plans.
        let live_tip = self.with_repo(|repo| self.live_remote_tip(repo))?;
        let expected = batch.expected_snapshot_token.as_deref();
        match (expected, live_tip) {
            // A present-but-empty (or whitespace-only) CAS anchor is malformed input, never
            // "no expectation": the precondition is unverifiable, so the publish must not proceed.
            (Some(token), _) if token.trim().is_empty() => {
                return Ok(PublishReceipt {
                    path_results: path_statuses(batch, &PathPublishStatus::PreconditionFailed),
                });
            }
            (Some(token), Some(tip)) if tip.to_string() != token => {
                return Ok(PublishReceipt {
                    path_results: path_statuses(batch, &PathPublishStatus::PreconditionFailed),
                });
            }
            (None, Some(_)) | (Some(_), None) => {
                return Ok(PublishReceipt {
                    path_results: path_statuses(batch, &PathPublishStatus::PreconditionFailed),
                });
            }
            _ => {}
        }

        // The baseline is the tree of the expected tip. If the live tip's objects are not yet in
        // the local ODB (remote moved between our cycle fetch and the live tip query), fetch once
        // to confirm the final tip — correctness is never traded for a fetch count.
        let baseline_tree_oid = if let Some(tip) = live_tip {
            let commit_result = self.with_repo(|repo| {
                repo.find_commit(tip)
                    .map_err(|error| from_git2("git_commit_lookup_failed", &error))
                    .map(|commit| commit.id())
            });
            match commit_result {
                Ok(_) => {}
                Err(_) => {
                    self.with_repo(|repo| {
                        self.ensure_lock_clear(repo)?;
                        self.fetch_remote(repo)
                    })?;
                }
            }
            Some(self.with_repo(|repo| {
                repo.find_commit(tip)
                    .and_then(|commit| commit.tree())
                    .map(|tree| tree.id())
                    .map_err(|error| from_git2("git_tree_lookup_failed", &error))
            })?)
        } else {
            None
        };

        let tree_oid = match self
            .with_repo(|repo| self.apply_intents_to_tree(repo, baseline_tree_oid, &batch.intents))
        {
            Ok(oid) => oid,
            Err(error) => {
                return Ok(PublishReceipt {
                    path_results: path_statuses(
                        batch,
                        &PathPublishStatus::Failed {
                            code: error.code().to_owned(),
                        },
                    ),
                });
            }
        };

        // Single-parent conditional publish: the confirmed remote tip is the sole CAS parent.
        let parents: Vec<Oid> = live_tip.into_iter().collect();
        let commit_oid = self.with_repo(|repo| {
            self.commit_tree(repo, tree_oid, &parents, "lomo-git sync publish")
        })?;

        match self.with_repo(|repo| self.push_cas(repo, commit_oid)) {
            Ok(()) => {
                self.record_published_tip(commit_oid);
                let new_token = commit_oid.to_string();
                Ok(PublishReceipt {
                    path_results: path_statuses(batch, &PathPublishStatus::Applied { new_token }),
                })
            }
            Err(error)
                if error.code() == "git_precondition_failed"
                    || error.code() == "git_push_rejected_not_fast_forward"
                    || error.code() == "git_push_rejected" =>
            {
                Ok(PublishReceipt {
                    path_results: path_statuses(batch, &PathPublishStatus::PreconditionFailed),
                })
            }
            Err(error) => Ok(PublishReceipt {
                path_results: path_statuses(
                    batch,
                    &PathPublishStatus::Failed {
                        code: error.code().to_owned(),
                    },
                ),
            }),
        }
    }

    fn verify(&self, expectations: &[VerifyExpectation]) -> Result<VerifiedRemoteState, LomoError> {
        let tip = self.cycle_tip()?;
        self.with_repo(|repo| {
            let tree = tip
                .map(|oid| {
                    repo.find_commit(oid)
                        .and_then(|commit| commit.tree())
                        .map_err(|error| from_git2("git_tree_lookup_failed", &error))
                })
                .transpose()?;
            let results = expectations
                .iter()
                .map(|expectation| Self::verify_expectation(repo, tree.as_ref(), expectation))
                .collect();
            Ok(VerifiedRemoteState { results })
        })
    }

    fn resolve_remote_object(
        &self,
        path: &SyncPath,
    ) -> Result<Option<RemoteResolvedObject>, LomoError> {
        Ok(self
            .read_remote_blob(path)?
            .map(|(bytes, _oid)| RemoteResolvedObject {
                digest: ContentDigest::from_bytes(&bytes),
                body: bytes,
            }))
    }

    fn load_object(
        &self,
        path: &SyncPath,
        expected_digest: &ContentDigest,
    ) -> Result<Option<Vec<u8>>, LomoError> {
        match self.read_remote_blob(path)? {
            Some((bytes, _oid)) => {
                let digest_hex = format!("{:x}", Sha256::digest(&bytes));
                if digest_hex != expected_digest.as_str() {
                    return Err(validation(
                        "git_object_digest_mismatch",
                        "git object digest does not match the conflict candidate digest",
                    ));
                }
                Ok(Some(bytes))
            }
            None => Ok(None),
        }
    }
}

fn path_statuses(
    batch: &PreparedRemoteBatch,
    status: &PathPublishStatus,
) -> Vec<(SyncPath, PathPublishStatus)> {
    batch
        .intents
        .iter()
        .map(|intent| match intent {
            ProviderNeutralIntent::EnsurePresent { path, .. }
            | ProviderNeutralIntent::EnsureAbsent { path, .. } => (path.clone(), status.clone()),
            ProviderNeutralIntent::PullPresent { path, .. }
            | ProviderNeutralIntent::OpenConflict { path, .. }
            | ProviderNeutralIntent::ReportUnrecognized { path }
            | ProviderNeutralIntent::Hold { path, .. } => {
                (path.clone(), PathPublishStatus::Skipped)
            }
        })
        .collect()
}

/// Nested tree node for building a root tree without a baseline.
#[derive(Default)]
struct TreeNode {
    files: BTreeMap<String, Oid>,
    dirs: BTreeMap<String, Self>,
}

fn build_tree_from_paths(
    repo: &Repository,
    files: &BTreeMap<String, Oid>,
) -> Result<Oid, LomoError> {
    let mut root = TreeNode::default();
    for (path, oid) in files {
        let mut parts: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();
        if parts.is_empty() {
            continue;
        }
        let file = parts.pop().unwrap_or("");
        let mut node = &mut root;
        for part in parts {
            node = node.dirs.entry(part.to_owned()).or_default();
        }
        node.files.insert(file.to_owned(), *oid);
    }
    write_tree_node(repo, &root)
}

fn write_tree_node(repo: &Repository, node: &TreeNode) -> Result<Oid, LomoError> {
    let mut builder = repo
        .treebuilder(None)
        .map_err(|error| from_git2("git_treebuilder_failed", &error))?;
    for (name, oid) in &node.files {
        builder
            .insert(name.as_str(), *oid, i32::from(FileMode::Blob))
            .map_err(|error| from_git2("git_treebuilder_insert_blob_failed", &error))?;
    }
    for (name, child) in &node.dirs {
        let child_oid = write_tree_node(repo, child)?;
        builder
            .insert(name.as_str(), child_oid, i32::from(FileMode::Tree))
            .map_err(|error| from_git2("git_treebuilder_insert_tree_failed", &error))?;
    }
    builder
        .write()
        .map_err(|error| from_git2("git_treebuilder_write_failed", &error))
}

/// Deletes the publish staging ref with an observed result; deletion failures surface.
/// Sums file sizes under a git directory (bounded: mirror/object stores are small).
fn git_dir_size(dir: &std::path::Path) -> u64 {
    let mut total: u64 = 0;
    let Ok(entries) = std::fs::read_dir(dir) else {
        return total;
    };
    for entry in entries.flatten() {
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        if metadata.is_dir() {
            total = total.saturating_add(git_dir_size(&entry.path()));
        } else {
            total = total.saturating_add(metadata.len());
        }
    }
    total
}

fn cleanup_push_ref(repo: &Repository, refname: &str) -> Result<(), LomoError> {
    match repo.find_reference(refname) {
        Ok(mut reference) => reference
            .delete()
            .map_err(|error| from_git2("git_push_src_ref_cleanup_failed", &error)),
        Err(error) if error.code() == ErrorCode::NotFound => Ok(()),
        Err(error) => Err(from_git2("git_push_src_ref_lookup_failed", &error)),
    }
}

/// Reclaims orphaned `refs/lomo/push/*` staging refs left by a crashed publish cycle.
///
/// Runs once at connect; each ref names its own commit OID so enumeration is bounded by actual
/// leftovers, and every delete is result-checked rather than best-effort.
fn reconcile_push_refs(repo: &Repository) -> Result<(), LomoError> {
    let stale: Vec<String> = repo
        .references_glob(PUSH_STAGING_GLOB)
        .map_err(|error| from_git2("git_push_ref_reconcile_failed", &error))?
        .map(|reference| {
            let reference =
                reference.map_err(|error| from_git2("git_push_ref_reconcile_failed", &error))?;
            let name = reference
                .name()
                .map_err(|error| from_git2("git_push_ref_reconcile_failed", &error))?;
            Ok::<String, LomoError>(name.to_owned())
        })
        .collect::<Result<Vec<String>, LomoError>>()?;
    for refname in stale {
        cleanup_push_ref(repo, &refname)?;
    }
    Ok(())
}

/// Convenience constructor using map object source (hermetic tests).
///
/// # Errors
///
/// Endpoint / open errors.
pub fn connect_map_git_source(
    params: crate::endpoint::MapGitConnectParams<'_>,
) -> Result<GitAdapter<crate::endpoint::MapGitObjectSource>, LomoError> {
    let endpoint = GitEndpoint::parse(params.remote_url, params.branch, params.local)?;
    GitAdapter::connect(
        endpoint,
        params.credentials,
        params.objects,
        params.author_name,
        params.author_email,
        params.timeout,
    )
}

/// Connects a production Git adapter over a workspace file object source.
///
/// Local mode is always an app-private bare mirror (never checkout/reset of user worktrees).
/// Secrets are process-local only; never journaled by this constructor.
///
/// # Errors
///
/// Endpoint / credential / local open-init failures.
#[expect(
    clippy::too_many_arguments,
    reason = "production connect mirrors MapGitConnectParams fields without a second config type"
)]
pub fn connect_workspace_git(
    remote_url: &str,
    branch: &str,
    mirror_dir: std::path::PathBuf,
    username: &str,
    token: &str,
    objects: crate::endpoint::WorkspaceFileGitObjectSource,
    author_name: &str,
    author_email: &str,
    timeout: Duration,
) -> Result<GitAdapter<crate::endpoint::WorkspaceFileGitObjectSource>, LomoError> {
    let endpoint = GitEndpoint::parse(
        remote_url,
        branch,
        crate::endpoint::GitLocalMode::AppPrivateBareMirror { mirror_dir },
    )?;
    let credentials = if token.is_empty() {
        GitCredentials::anonymous()
    } else {
        GitCredentials::new(username, token)?
    };
    GitAdapter::connect(
        endpoint,
        credentials,
        objects,
        author_name,
        author_email,
        timeout,
    )
}
