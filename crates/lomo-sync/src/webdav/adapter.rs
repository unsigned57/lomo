//! `RemoteSyncPort` implementation for `WebDAV` (protocol adapter only).

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::Duration;

use sha2::{Digest, Sha256};
use url::Url;

use crate::error::{resource_limit, validation};
use crate::limits::{
    MAX_ACTION_PAGE_ITEMS, MAX_STREAMING_REMOTE_PATH_KEYS, MAX_WEBDAV_SNAPSHOT_ENTRIES,
    MAX_WEBDAV_TRAVERSAL_DEPTH,
};
use crate::pipeline::{
    BatchAtomicity, ContentDigest, PathPublishStatus, PreparedRemoteBatch, ProviderNeutralIntent,
    PublishReceipt, RemotePathEntry, RemoteSnapshot, SnapshotCompleteness, SyncPath,
    VerifiedRemoteState, VerifyStatus,
};
use crate::ports::{RemoteListingStream, RemoteSyncPort};
use crate::webdav::endpoint::{WebDavCredentials, WebDavEndpoint};
use crate::webdav::multistatus::{MultistatusResource, parse_multistatus};
use crate::webdav::transport::{RemoteCapabilities, WebDavTransport};
use lomo_core::LomoError;

/// Supplies object bytes for `EnsurePresent` publishes (path → digest map known to the planner).
///
/// Production will resolve digests via store/workspace; hermetic tests supply a map.
pub trait WebDavObjectSource {
    /// Loads full object bytes for a workspace-relative path that the planner intends to publish.
    ///
    /// # Errors
    ///
    /// Validation when the path is unknown or bytes are unavailable.
    fn load_bytes(
        &self,
        path: &SyncPath,
        expected_digest: &ContentDigest,
    ) -> Result<Vec<u8>, LomoError>;
}

/// In-memory object source for hermetic contracts.
#[derive(Clone, Debug, Default)]
pub struct MapObjectSource {
    pub objects: BTreeMap<String, Vec<u8>>,
}

impl WebDavObjectSource for MapObjectSource {
    fn load_bytes(
        &self,
        path: &SyncPath,
        expected_digest: &ContentDigest,
    ) -> Result<Vec<u8>, LomoError> {
        let bytes = self.objects.get(path.as_str()).ok_or_else(|| {
            validation(
                "webdav_object_source_missing",
                "webdav object source has no bytes for the ensure-present path",
            )
        })?;
        let digest = format!("{:x}", Sha256::digest(bytes));
        if digest != expected_digest.as_str() {
            return Err(validation(
                "webdav_object_source_digest_mismatch",
                "webdav object source digest does not match the ensure-present intent",
            ));
        }
        Ok(bytes.clone())
    }
}

/// `WebDAV` remote adapter implementing the public [`RemoteSyncPort`].
pub struct WebDavAdapter<S: WebDavObjectSource> {
    transport: WebDavTransport,
    objects: S,
    /// When set, force snapshot completeness to Incomplete after listing (test injection).
    force_incomplete: bool,
}

impl<S: WebDavObjectSource> WebDavAdapter<S> {
    /// Constructs an adapter for dark-host tests and future composition (not production DI).
    ///
    /// # Errors
    ///
    /// Transport construction errors (temp dir / client).
    pub fn connect(
        endpoint: WebDavEndpoint,
        credentials: WebDavCredentials,
        temp_dir: impl Into<PathBuf>,
        objects: S,
        timeout: Duration,
    ) -> Result<Self, LomoError> {
        let transport = WebDavTransport::new(endpoint, credentials, temp_dir, timeout)?;
        Ok(Self {
            transport,
            objects,
            force_incomplete: false,
        })
    }

    /// Test-only: mark the next snapshot incomplete regardless of traversal outcome.
    #[must_use]
    pub const fn with_force_incomplete(mut self, force: bool) -> Self {
        self.force_incomplete = force;
        self
    }

    /// Read-only capability probe (`OPTIONS` + `PROPFIND` Depth=0).
    ///
    /// # Errors
    ///
    /// Wire / auth errors from the transport.
    pub fn preflight(&self) -> Result<RemoteCapabilities, LomoError> {
        self.transport.probe_capabilities()
    }

    /// Single-shot list capped at [`MAX_WEBDAV_SNAPSHOT_ENTRIES`] (≤512). Extra remote keys mark Incomplete.
    fn list_all(&self) -> (Vec<RemotePathEntry>, bool) {
        let (pages, incomplete) = self.list_into_pages(MAX_WEBDAV_SNAPSHOT_ENTRIES);
        let entries = pages.into_iter().flatten().collect();
        (entries, incomplete)
    }

    /// Multi-page list for residual streaming: each page ≤ [`MAX_ACTION_PAGE_ITEMS`], never one
    /// multi-page materialize into a single `RemoteSnapshot`. Stops at
    /// [`MAX_STREAMING_REMOTE_PATH_KEYS`] with Incomplete (fail closed, never clamp).
    fn list_into_pages(&self, max_entries: usize) -> (Vec<Vec<RemotePathEntry>>, bool) {
        let max_entries = max_entries.min(MAX_STREAMING_REMOTE_PATH_KEYS);
        let mut pages: Vec<Vec<RemotePathEntry>> = Vec::new();
        let mut current_page: Vec<RemotePathEntry> = Vec::new();
        let mut total_entries = 0usize;
        let mut incomplete = self.force_incomplete;
        let root = self.transport.endpoint().url().clone();
        let _walked: Result<(), LomoError> = self.collect_into_pages(
            &root,
            0,
            max_entries,
            &mut pages,
            &mut current_page,
            &mut total_entries,
            &mut incomplete,
        );
        if !current_page.is_empty() {
            pages.push(current_page);
        }
        (pages, incomplete)
    }

    #[expect(
        clippy::too_many_arguments,
        reason = "page collector is a private recursive walk with shared page state"
    )]
    fn collect_into_pages(
        &self,
        collection_url: &Url,
        depth: usize,
        max_entries: usize,
        pages: &mut Vec<Vec<RemotePathEntry>>,
        current_page: &mut Vec<RemotePathEntry>,
        total_entries: &mut usize,
        incomplete: &mut bool,
    ) -> Result<(), LomoError> {
        if depth > MAX_WEBDAV_TRAVERSAL_DEPTH {
            *incomplete = true;
            return Ok(());
        }
        if *total_entries >= max_entries {
            *incomplete = true;
            return Ok(());
        }
        let body = match self.transport.propfind(collection_url, 1) {
            Ok(bytes) => bytes,
            Err(_error) => {
                // Any subtree failure → incomplete snapshot (no delete derivation).
                *incomplete = true;
                return Ok(());
            }
        };
        let resources = match parse_multistatus(self.transport.endpoint(), &body) {
            Ok(list) => list,
            Err(_error) => {
                *incomplete = true;
                return Ok(());
            }
        };

        let mut child_dirs: Vec<(String, Url)> = Vec::new();
        for resource in resources {
            if *total_entries >= max_entries {
                *incomplete = true;
                break;
            }
            if resource.is_directory {
                if let Ok(child_url) = self
                    .transport
                    .endpoint()
                    .resolve_path(&format!("{}/", resource.relative_path))
                    && child_url.as_str() != collection_url.as_str()
                {
                    child_dirs.push((resource.relative_path, child_url));
                }
                continue;
            }
            match self.snapshot_file(&resource) {
                Ok(entry) => {
                    current_page.push(entry);
                    *total_entries = total_entries.saturating_add(1);
                    if current_page.len() >= MAX_ACTION_PAGE_ITEMS {
                        pages.push(std::mem::take(current_page));
                    }
                }
                Err(_error) => {
                    *incomplete = true;
                }
            }
        }

        for (_path, child_url) in child_dirs {
            if *total_entries >= max_entries {
                *incomplete = true;
                break;
            }
            self.collect_into_pages(
                &child_url,
                depth + 1,
                max_entries,
                pages,
                current_page,
                total_entries,
                incomplete,
            )?;
        }
        Ok(())
    }

    fn snapshot_file(&self, resource: &MultistatusResource) -> Result<RemotePathEntry, LomoError> {
        let url = self
            .transport
            .endpoint()
            .resolve_path(&resource.relative_path)?;
        let (temp_path, etag, digest_hex) = self.transport.get_to_temp(&url)?;
        let _removed: Result<(), std::io::Error> = std::fs::remove_file(&temp_path);
        let path = SyncPath::parse(&resource.relative_path)?;
        let digest = ContentDigest::parse(&digest_hex)?;
        let revision_token = etag.or_else(|| resource.etag.clone()).unwrap_or_else(|| {
            let mut token = String::from("sha256:");
            token.push_str(&digest_hex);
            token
        });
        Ok(RemotePathEntry {
            path,
            digest,
            revision_token,
        })
    }

    fn publish_ensure_present(
        &self,
        path: &SyncPath,
        digest: &ContentDigest,
        expected_remote_token: Option<&str>,
    ) -> PathPublishStatus {
        if let Err(error) = self.ensure_parent_collections(path.as_str()) {
            return PathPublishStatus::Failed {
                code: error.code().to_owned(),
            };
        }
        let url = match self.transport.endpoint().resolve_path(path.as_str()) {
            Ok(url) => url,
            Err(error) => {
                return PathPublishStatus::Failed {
                    code: error.code().to_owned(),
                };
            }
        };
        let bytes = match self.objects.load_bytes(path, digest) {
            Ok(bytes) => bytes,
            Err(error) => {
                return PathPublishStatus::Failed {
                    code: error.code().to_owned(),
                };
            }
        };
        let result = match expected_remote_token {
            Some(token) => self.transport.put_bytes(&url, bytes, Some(token), false),
            None => self.transport.put_bytes(&url, bytes, None, true),
        };
        match result {
            Ok(etag) => {
                let new_token = etag.unwrap_or_else(|| {
                    let mut token = String::from("sha256:");
                    token.push_str(digest.as_str());
                    token
                });
                PathPublishStatus::Applied { new_token }
            }
            Err(error) if error.code() == "webdav_precondition_failed" => {
                PathPublishStatus::PreconditionFailed
            }
            Err(error) => PathPublishStatus::Failed {
                code: error.code().to_owned(),
            },
        }
    }

    /// Ensures intermediate collection parents exist (`MKCOL` walk) for nested object keys.
    ///
    /// Host flat-key servers hide this; real Nutstore/Nextcloud require parent collections.
    fn ensure_parent_collections(&self, relative: &str) -> Result<(), LomoError> {
        let trimmed = relative.trim_matches('/');
        let Some((parent, _)) = trimmed.rsplit_once('/') else {
            return Ok(());
        };
        let mut built = String::new();
        for segment in parent.split('/') {
            if segment.is_empty() {
                return Err(validation(
                    "webdav_parent_segment_empty",
                    "webdav parent collection path must not contain empty segments",
                ));
            }
            if !built.is_empty() {
                built.push('/');
            }
            built.push_str(segment);
            let collection_rel = format!("{built}/");
            let url = self.transport.endpoint().resolve_path(&collection_rel)?;
            self.transport.mkcol(&url)?;
        }
        Ok(())
    }

    fn publish_ensure_absent(
        &self,
        path: &SyncPath,
        expected_remote_token: &str,
    ) -> PathPublishStatus {
        let url = match self.transport.endpoint().resolve_path(path.as_str()) {
            Ok(url) => url,
            Err(error) => {
                return PathPublishStatus::Failed {
                    code: error.code().to_owned(),
                };
            }
        };
        match self.transport.delete(&url, Some(expected_remote_token)) {
            Ok(()) => PathPublishStatus::Applied {
                new_token: String::new(),
            },
            Err(error) if error.code() == "webdav_precondition_failed" => {
                PathPublishStatus::PreconditionFailed
            }
            Err(error) if error.code() == "webdav_not_found" => PathPublishStatus::Applied {
                new_token: String::new(),
            },
            Err(error) => PathPublishStatus::Failed {
                code: error.code().to_owned(),
            },
        }
    }

    fn verify_path(&self, path: &SyncPath) -> VerifyStatus {
        let url = match self.transport.endpoint().resolve_path(path.as_str()) {
            Ok(url) => url,
            Err(error) => {
                return VerifyStatus::Failed {
                    path: path.clone(),
                    code: error.code().to_owned(),
                };
            }
        };
        match self.transport.get_to_temp(&url) {
            Ok((temp_path, etag, digest_hex)) => {
                let _removed: Result<(), std::io::Error> = std::fs::remove_file(temp_path);
                match ContentDigest::parse(&digest_hex) {
                    Ok(digest) => VerifyStatus::Verified {
                        path: path.clone(),
                        digest,
                        remote_token: etag.unwrap_or_else(|| {
                            let mut token = String::from("sha256:");
                            token.push_str(&digest_hex);
                            token
                        }),
                    },
                    Err(error) => VerifyStatus::Failed {
                        path: path.clone(),
                        code: error.code().to_owned(),
                    },
                }
            }
            Err(error) if error.code() == "webdav_not_found" => {
                VerifyStatus::AbsentVerified { path: path.clone() }
            }
            Err(error) => VerifyStatus::Failed {
                path: path.clone(),
                code: error.code().to_owned(),
            },
        }
    }
}

impl<S: WebDavObjectSource> RemoteSyncPort for WebDavAdapter<S> {
    fn list_remote(&self) -> Result<RemoteSnapshot, LomoError> {
        let (entries, incomplete) = self.list_all();
        if entries.len() > MAX_WEBDAV_SNAPSHOT_ENTRIES {
            return Err(resource_limit(
                "webdav_snapshot_too_large",
                "webdav snapshot exceeds the action page entry ceiling",
            ));
        }
        let completeness = if incomplete {
            SnapshotCompleteness::Incomplete
        } else {
            SnapshotCompleteness::Complete
        };
        RemoteSnapshot::new(completeness, entries)
    }

    fn list_remote_pages(&self) -> Result<RemoteListingStream, LomoError> {
        // Stream multi-page listings without raising single-shot RemoteSnapshot past 512.
        // Page buffers are ≤ MAX_ACTION_PAGE_ITEMS; overall Incomplete when truncated/faulted.
        let (pages, incomplete) = self.list_into_pages(MAX_STREAMING_REMOTE_PATH_KEYS);
        for page in &pages {
            if page.len() > MAX_ACTION_PAGE_ITEMS {
                return Err(resource_limit(
                    "remote_snapshot_page_too_large",
                    "webdav streaming remote page exceeds the 512-item action page limit",
                ));
            }
        }
        let completeness = if incomplete {
            SnapshotCompleteness::Incomplete
        } else {
            SnapshotCompleteness::Complete
        };
        RemoteListingStream::from_pages(completeness, pages)
    }

    fn publish(&self, batch: &PreparedRemoteBatch) -> Result<PublishReceipt, LomoError> {
        if batch.atomicity != BatchAtomicity::PerPath {
            return Err(validation(
                "webdav_batch_atomicity",
                "webdav adapter only executes PerPath batches",
            ));
        }
        let mut path_results = Vec::with_capacity(batch.intents.len());
        for intent in &batch.intents {
            match intent {
                ProviderNeutralIntent::EnsurePresent {
                    path,
                    digest,
                    expected_remote_token,
                } => {
                    let status =
                        self.publish_ensure_present(path, digest, expected_remote_token.as_deref());
                    path_results.push((path.clone(), status));
                }
                ProviderNeutralIntent::EnsureAbsent {
                    path,
                    expected_remote_token,
                } => {
                    let status = self.publish_ensure_absent(path, expected_remote_token);
                    path_results.push((path.clone(), status));
                }
                ProviderNeutralIntent::PullPresent { path, .. }
                | ProviderNeutralIntent::OpenConflict { path, .. }
                | ProviderNeutralIntent::ReportUnrecognized { path } => {
                    path_results.push((path.clone(), PathPublishStatus::Skipped));
                }
            }
        }
        Ok(PublishReceipt { path_results })
    }

    fn verify(&self, paths: &[SyncPath]) -> Result<VerifiedRemoteState, LomoError> {
        let results = paths.iter().map(|path| self.verify_path(path)).collect();
        Ok(VerifiedRemoteState { results })
    }
}

/// Convenience constructor using a filesystem temp directory path.
///
/// # Errors
///
/// Endpoint / credential / transport construction errors.
pub fn connect_map_source(
    endpoint_url: &str,
    username: &str,
    password: &str,
    temp_dir: &Path,
    objects: MapObjectSource,
    timeout: Duration,
) -> Result<WebDavAdapter<MapObjectSource>, LomoError> {
    let endpoint = WebDavEndpoint::parse(endpoint_url)?;
    let credentials = WebDavCredentials::new(username, password)?;
    WebDavAdapter::connect(endpoint, credentials, temp_dir, objects, timeout)
}

/// Production object source: loads user file bytes from the Direct workspace filesystem.
///
/// Digest is verified against the intent before publish. Never invents bodies; missing path fails closed.
#[derive(Clone, Debug)]
pub struct WorkspaceFileObjectSource {
    workspace_root: PathBuf,
}

impl WorkspaceFileObjectSource {
    /// Builds a workspace-rooted object source.
    #[must_use]
    pub fn new(workspace_root: impl Into<PathBuf>) -> Self {
        Self {
            workspace_root: workspace_root.into(),
        }
    }
}

impl WebDavObjectSource for WorkspaceFileObjectSource {
    fn load_bytes(
        &self,
        path: &SyncPath,
        expected_digest: &ContentDigest,
    ) -> Result<Vec<u8>, LomoError> {
        load_workspace_file_bytes(&self.workspace_root, path, expected_digest)
    }
}

fn load_workspace_file_bytes(
    workspace_root: &Path,
    path: &SyncPath,
    expected_digest: &ContentDigest,
) -> Result<Vec<u8>, LomoError> {
    let absolute = workspace_root.join(path.as_str());
    let bytes = std::fs::read(&absolute).map_err(|err| {
        validation(
            "workspace_object_source_missing",
            &format!(
                "workspace object source cannot read {}: {err}",
                path.as_str()
            ),
        )
    })?;
    let digest = format!("{:x}", Sha256::digest(&bytes));
    if digest != expected_digest.as_str() {
        return Err(validation(
            "workspace_object_source_digest_mismatch",
            "workspace object source digest does not match the ensure-present intent",
        ));
    }
    Ok(bytes)
}

/// Connects a production `WebDAV` adapter over workspace file object source.
///
/// # Errors
///
/// Endpoint / credential / transport construction errors.
pub fn connect_workspace_webdav(
    endpoint_url: &str,
    username: &str,
    password: &str,
    temp_dir: &Path,
    objects: WorkspaceFileObjectSource,
    timeout: Duration,
) -> Result<WebDavAdapter<WorkspaceFileObjectSource>, LomoError> {
    let endpoint = WebDavEndpoint::parse(endpoint_url)?;
    let credentials = WebDavCredentials::new(username, password)?;
    WebDavAdapter::connect(endpoint, credentials, temp_dir, objects, timeout)
}
