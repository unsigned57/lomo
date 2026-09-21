//! `RemoteSyncPort` implementation for Git (`git2` adapter only; no Git-specific planner).

use std::collections::BTreeMap;
use std::time::Duration;

use git2::{
    Cred, ErrorCode, FetchOptions, FileMode, ObjectType, Oid, PushOptions, RemoteCallbacks,
    Repository, Signature, TreeWalkMode, TreeWalkResult, build::TreeUpdateBuilder,
};
use sha2::{Digest, Sha256};

use crate::endpoint::{GitCredentials, GitEndpoint, GitObjectSource};
use crate::error::{conflict, from_git2, validation};
use crate::lock::{DEFAULT_STALE_LOCK_THRESHOLD, ensure_index_lock_clear};
use crate::mirror::open_local_repository;
use lomo_core::LomoError;
use lomo_sync::{
    BatchAtomicity, ContentDigest, MAX_ACTION_PAGE_ITEMS, PathPublishStatus, PreparedRemoteBatch,
    ProviderNeutralIntent, PublishReceipt, RemoteDigestFact, RemoteListingStream, RemotePathEntry,
    RemoteResolvedObject, RemoteSnapshot, RemoteSyncPort, RemoteValidator, SnapshotCompleteness,
    SyncPath, VerifiedRemoteState, VerifyExpectation, VerifyStatus,
};

/// Git remote adapter implementing the public [`RemoteSyncPort`].
///
/// Compiles path intents into tree/commit + non-force CAS ref push (`WholeBatchRef`).
/// When local HEAD and remote tip diverge with a proven merge-base (conflict resolve), the
/// publish commit is dual-parent (remote tip first, local HEAD second). Never force-pushes,
/// never checkout/resets user worktrees.
pub struct GitAdapter<S: GitObjectSource> {
    endpoint: GitEndpoint,
    credentials: GitCredentials,
    objects: S,
    author_name: String,
    author_email: String,
    stale_lock_threshold: Duration,
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
        _timeout: Duration,
    ) -> Result<Self, LomoError> {
        let _repo = open_local_repository(endpoint.local())?;
        Ok(Self {
            endpoint,
            credentials,
            objects,
            author_name: author_name.into(),
            author_email: author_email.into(),
            stale_lock_threshold: DEFAULT_STALE_LOCK_THRESHOLD,
        })
    }

    /// Test-only: override stale-lock threshold.
    #[must_use]
    pub const fn with_stale_lock_threshold(mut self, threshold: Duration) -> Self {
        self.stale_lock_threshold = threshold;
        self
    }

    fn open(&self) -> Result<Repository, LomoError> {
        open_local_repository(self.endpoint.local())
    }

    fn ensure_lock_clear(&self, repo: &Repository) -> Result<(), LomoError> {
        ensure_index_lock_clear(
            repo.path(),
            self.stale_lock_threshold,
            std::time::SystemTime::now(),
        )
    }

    fn remote_callbacks(&self) -> RemoteCallbacks<'_> {
        let mut callbacks = RemoteCallbacks::new();
        if self.credentials.has_secret() {
            let username = self.credentials.username().to_owned();
            let token = self.credentials.token().to_owned();
            callbacks.credentials(move |_url, _username_from_url, _allowed| {
                Cred::userpass_plaintext(&username, &token)
            });
        }
        callbacks
    }

    /// Fetch remote branch into `refs/remotes/origin/{branch}`.
    fn fetch_remote(&self, repo: &Repository) -> Result<(), LomoError> {
        let branch_ref = self.endpoint.branch_ref();
        let remote_name = "origin";
        match repo.find_remote(remote_name) {
            Ok(remote) => {
                let matches = remote
                    .url()
                    .is_ok_and(|url| url == self.endpoint.remote_url());
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
        let mut remote = repo
            .find_remote(remote_name)
            .map_err(|error| from_git2("git_remote_find_failed", &error))?;
        let mut fetch_opts = FetchOptions::new();
        fetch_opts.remote_callbacks(self.remote_callbacks());
        // Leading `+` updates the *remote-tracking* ref only; publish push is non-force.
        let refspec = format!(
            "+{branch_ref}:refs/remotes/origin/{}",
            self.endpoint.branch()
        );
        remote
            .fetch(&[refspec.as_str()], Some(&mut fetch_opts), None)
            .map_err(|error| from_git2("git_fetch_failed", &error))?;
        Ok(())
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
        repo: &Repository,
        commit_oid: Oid,
    ) -> Result<Vec<RemotePathEntry>, LomoError> {
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

    /// Reads one remote blob at the fetched remote tip: `(body bytes, blob oid)` when present.
    fn read_remote_blob(&self, path: &SyncPath) -> Result<Option<(Vec<u8>, String)>, LomoError> {
        let repo = self.open()?;
        self.ensure_lock_clear(&repo)?;
        self.fetch_remote(&repo)?;
        let Some(tip) = self.resolve_remote_tip(&repo)? else {
            return Ok(None);
        };
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
        Ok(Some((blob.content().to_vec(), entry.id().to_string())))
    }

    fn apply_intents_to_tree(
        &self,
        repo: &Repository,
        baseline_tree: Option<git2::Tree<'_>>,
        intents: &[ProviderNeutralIntent],
    ) -> Result<Oid, LomoError> {
        let mut upserts: BTreeMap<String, Oid> = BTreeMap::new();
        let mut removes: Vec<String> = Vec::new();
        for intent in intents {
            match intent {
                ProviderNeutralIntent::EnsurePresent { path, digest, .. } => {
                    let bytes = self.objects.load_bytes(path, digest)?;
                    let oid = repo
                        .blob(&bytes)
                        .map_err(|error| from_git2("git_blob_write_failed", &error))?;
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

        if let Some(tree) = baseline_tree {
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

    /// Non-force push of `commit` to `refs/heads/{branch}` with expected remote tip CAS.
    fn push_cas(
        &self,
        repo: &Repository,
        commit: Oid,
        expected_remote_tip: Option<Oid>,
    ) -> Result<(), LomoError> {
        if let Some(expected) = expected_remote_tip {
            let current = self.resolve_remote_tip(repo)?;
            if current != Some(expected) {
                return Err(conflict(
                    "git_precondition_failed",
                    "remote tip moved since snapshot; non-force CAS push blocked",
                ));
            }
        }

        let branch_ref = self.endpoint.branch_ref();
        let push_src = format!("refs/lomo/push/{commit}");
        repo.reference(&push_src, commit, true, "lomo-git publish staging")
            .map_err(|error| from_git2("git_push_src_ref_failed", &error))?;

        let mut remote = repo
            .find_remote("origin")
            .map_err(|error| from_git2("git_remote_find_failed", &error))?;
        let mut push_opts = PushOptions::new();
        push_opts.remote_callbacks(self.remote_callbacks());
        // Non-force dest update: `src:dst` without leading `+`.
        let refspec = format!("{push_src}:{branch_ref}");
        let result = remote.push(&[refspec.as_str()], Some(&mut push_opts));
        if let Ok(mut reference) = repo.find_reference(&push_src) {
            let _deleted: Result<(), git2::Error> = reference.delete();
        }
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

    /// Selects commit parents for a publish:
    /// - remote tip as first parent when present (CAS mainline)
    /// - local HEAD as second parent when distinct and merge-base is proven (resolve shape)
    /// - empty when remote has no tip (root commit)
    fn select_publish_parents(
        repo: &Repository,
        remote_tip: Option<Oid>,
    ) -> Result<Vec<Oid>, LomoError> {
        let mut parents: Vec<Oid> = Vec::new();
        let Some(tip) = remote_tip else {
            return Ok(parents);
        };
        parents.push(tip);
        if let Ok(head) = repo.head()
            && let Ok(head_commit) = head.peel_to_commit()
        {
            let local = head_commit.id();
            if local != tip {
                let _base = Self::require_merge_base(repo, local, tip)?;
                parents.push(local);
            }
        }
        Ok(parents)
    }

    /// Proves merge-base between local tip and remote tip; blocks when unprovable (shallow).
    fn require_merge_base(repo: &Repository, local: Oid, remote: Oid) -> Result<Oid, LomoError> {
        match repo.merge_base(local, remote) {
            Ok(base) => Ok(base),
            Err(error) => Err(conflict(
                "git_merge_base_unproven",
                &format!(
                    "cannot prove merge-base between local and remote tips: {}",
                    crate::redaction::redact_diagnostic(error.message())
                ),
            )),
        }
    }
}

impl<S: GitObjectSource> RemoteSyncPort for GitAdapter<S> {
    fn list_remote(&self) -> Result<RemoteSnapshot, LomoError> {
        let repo = self.open()?;
        self.ensure_lock_clear(&repo)?;
        self.fetch_remote(&repo)?;
        let tip = self.resolve_remote_tip(&repo)?;
        let snapshot_revision = tip.map(|oid| oid.to_string());
        let Some(commit_oid) = tip else {
            return RemoteSnapshot::with_snapshot_revision(
                SnapshotCompleteness::Complete,
                Vec::new(),
                None,
            );
        };
        let entries = Self::tree_entries_from_commit(&repo, commit_oid)?;
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
        let repo = self.open()?;
        self.ensure_lock_clear(&repo)?;
        self.fetch_remote(&repo)?;
        let tip = self.resolve_remote_tip(&repo)?;
        let snapshot_revision = tip.map(|oid| oid.to_string());
        let entries = if let Some(commit_oid) = tip {
            Self::tree_entries_from_commit(&repo, commit_oid)?
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

    fn publish(&self, batch: &PreparedRemoteBatch) -> Result<PublishReceipt, LomoError> {
        if batch.atomicity != BatchAtomicity::WholeBatchRef {
            return Err(validation(
                "git_batch_atomicity",
                "git adapter only executes WholeBatchRef batches",
            ));
        }
        let repo = self.open()?;
        self.ensure_lock_clear(&repo)?;
        self.fetch_remote(&repo)?;

        let remote_tip = self.resolve_remote_tip(&repo)?;
        match (batch.expected_snapshot_token.as_deref(), remote_tip) {
            (Some(token), Some(tip)) if !token.is_empty() && tip.to_string() != token => {
                return Ok(PublishReceipt {
                    path_results: path_statuses(batch, &PathPublishStatus::PreconditionFailed),
                });
            }
            (None, Some(_)) => {
                return Ok(PublishReceipt {
                    path_results: path_statuses(batch, &PathPublishStatus::PreconditionFailed),
                });
            }
            (Some(token), None) if !token.is_empty() => {
                return Ok(PublishReceipt {
                    path_results: path_statuses(batch, &PathPublishStatus::PreconditionFailed),
                });
            }
            _ => {}
        }

        let baseline_tree = if let Some(tip) = remote_tip {
            let commit = repo
                .find_commit(tip)
                .map_err(|error| from_git2("git_commit_lookup_failed", &error))?;
            Some(
                commit
                    .tree()
                    .map_err(|error| from_git2("git_tree_lookup_failed", &error))?,
            )
        } else {
            None
        };

        let tree_oid = match self.apply_intents_to_tree(&repo, baseline_tree, &batch.intents) {
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

        let parents = Self::select_publish_parents(&repo, remote_tip)?;
        let message = if parents.len() >= 2 {
            "lomo-git sync publish (merge after resolve)"
        } else {
            "lomo-git sync publish"
        };
        let commit_oid = self.commit_tree(&repo, tree_oid, &parents, message)?;

        match self.push_cas(&repo, commit_oid, parents.first().copied()) {
            Ok(()) => {
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
        let repo = self.open()?;
        self.ensure_lock_clear(&repo)?;
        self.fetch_remote(&repo)?;
        let tip = self.resolve_remote_tip(&repo)?;
        let tree = tip
            .map(|oid| {
                repo.find_commit(oid)
                    .and_then(|commit| commit.tree())
                    .map_err(|error| from_git2("git_tree_lookup_failed", &error))
            })
            .transpose()?;
        let results = expectations
            .iter()
            .map(|expectation| Self::verify_expectation(&repo, tree.as_ref(), expectation))
            .collect();
        Ok(VerifiedRemoteState { results })
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
