//! `RemoteSyncPort` implementation for S3 (protocol adapter only).

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::durable::{
    SYNC_RECORD_MAGIC, decode_sync_record, encode_sync_record, write_sync_record_atomic,
};
use crate::error::{corrupt_state, resource_limit, storage, validation};
use crate::limits::{
    MAX_ACTION_PAGE_ITEMS, MAX_DURABLE_RECORD_BYTES, MAX_S3_LIST_PAGES, MAX_S3_SNAPSHOT_ENTRIES,
    MAX_STREAMING_REMOTE_PATH_KEYS, S3_MULTIPART_PART_BYTES, SYNC_DURABLE_SCHEMA,
};
use crate::pipeline::{
    BatchAtomicity, ContentDigest, PathPublishStatus, PreparedRemoteBatch, ProviderNeutralIntent,
    PublishReceipt, RemotePathEntry, RemoteSnapshot, SnapshotCompleteness, SyncPath,
    VerifiedRemoteState, VerifyStatus,
};
use crate::ports::{RemoteListingStream, RemoteSyncPort};
use crate::s3::endpoint::{S3Credentials, S3Endpoint};
use crate::s3::transport::S3Transport;
use lomo_core::LomoError;

/// Supplies object bytes for `EnsurePresent` publishes.
pub trait S3ObjectSource {
    /// Loads full object bytes for a workspace-relative path.
    ///
    /// # Errors
    ///
    /// Validation when the path is unknown or digest mismatches.
    fn load_bytes(
        &self,
        path: &SyncPath,
        expected_digest: &ContentDigest,
    ) -> Result<Vec<u8>, LomoError>;
}

/// In-memory object source for hermetic contracts.
#[derive(Clone, Debug, Default)]
pub struct MapS3ObjectSource {
    pub objects: BTreeMap<String, Vec<u8>>,
}

impl S3ObjectSource for MapS3ObjectSource {
    fn load_bytes(
        &self,
        path: &SyncPath,
        expected_digest: &ContentDigest,
    ) -> Result<Vec<u8>, LomoError> {
        let bytes = self.objects.get(path.as_str()).ok_or_else(|| {
            validation(
                "s3_object_source_missing",
                "s3 object source has no bytes for the ensure-present path",
            )
        })?;
        let digest = format!("{:x}", Sha256::digest(bytes));
        if digest != expected_digest.as_str() {
            return Err(validation(
                "s3_object_source_digest_mismatch",
                "s3 object source digest does not match the ensure-present intent",
            ));
        }
        Ok(bytes.clone())
    }
}

/// Confirmed multipart part (durable-facing facts for host resume slice).
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct MultipartConfirmedPart {
    pub part_number: u32,
    pub etag: String,
    pub size_bytes: usize,
}

/// In-flight multipart session tracked by the adapter (host slice; not a second planner).
///
/// When [`S3Adapter::with_durable_multipart_root`] is set, sessions also persist under
/// `.lomo/sync/v1/multipart/` so process death can resume without re-uploading confirmed parts.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct MultipartSession {
    pub path: String,
    pub key: String,
    pub upload_id: String,
    pub confirmed_parts: Vec<MultipartConfirmedPart>,
    /// Content digest hex (SHA-256 of the full object body).
    pub content_digest: String,
}

/// On-disk multipart session record (LSYN framed JSON under `multipart/`).
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
struct DurableMultipartRecord {
    schema: u32,
    path: String,
    key: String,
    upload_id: String,
    confirmed_parts: Vec<MultipartConfirmedPart>,
    content_digest: String,
}

/// S3 remote adapter implementing the public [`RemoteSyncPort`].
pub struct S3Adapter<S: S3ObjectSource> {
    transport: S3Transport,
    objects: S,
    force_incomplete: bool,
    /// Threshold above which publish uses multipart (default [`S3_MULTIPART_PART_BYTES`]).
    multipart_threshold: usize,
    /// In-memory multipart sessions for hermetic resume (path → session).
    multipart_sessions: std::sync::Mutex<BTreeMap<String, MultipartSession>>,
    /// Optional workspace root for durable multipart session files under `.lomo/sync/v1/multipart/`.
    durable_workspace_root: Option<PathBuf>,
}

impl<S: S3ObjectSource> S3Adapter<S> {
    /// Constructs an adapter for dark-host tests and future composition (not production DI).
    ///
    /// # Errors
    ///
    /// Transport construction errors.
    pub fn connect(
        endpoint: S3Endpoint,
        credentials: S3Credentials,
        temp_dir: impl Into<PathBuf>,
        objects: S,
        timeout: Duration,
    ) -> Result<Self, LomoError> {
        let transport = S3Transport::new(endpoint, credentials, temp_dir, timeout)?;
        Ok(Self {
            transport,
            objects,
            force_incomplete: false,
            multipart_threshold: S3_MULTIPART_PART_BYTES,
            multipart_sessions: std::sync::Mutex::new(BTreeMap::new()),
            durable_workspace_root: None,
        })
    }

    /// Test-only: mark the next snapshot incomplete regardless of listing outcome.
    #[must_use]
    pub const fn with_force_incomplete(mut self, force: bool) -> Self {
        self.force_incomplete = force;
        self
    }

    /// Test-only: lower multipart threshold so small bodies exercise multipart resume.
    #[must_use]
    pub const fn with_multipart_threshold(mut self, threshold: usize) -> Self {
        self.multipart_threshold = threshold;
        self
    }

    /// Enables durable on-disk multipart sessions under `{workspace}/.lomo/sync/v1/multipart/`.
    ///
    /// Process death can reload confirmed parts without re-uploading. Corrupt records fail closed
    /// (`CorruptState`) and never clean-slate other durable sync state.
    #[must_use]
    pub fn with_durable_multipart_root(mut self, workspace_root: impl Into<PathBuf>) -> Self {
        self.durable_workspace_root = Some(workspace_root.into());
        self
    }

    /// Test-only: pin `SigV4` `amz-date`.
    #[must_use]
    pub fn with_fixed_amz_date(mut self, amz_date: impl Into<String>) -> Self {
        self.transport = self.transport.with_fixed_amz_date(amz_date);
        self
    }

    /// Returns a snapshot of tracked multipart sessions (memory + durable disk when enabled).
    #[must_use]
    pub fn multipart_sessions_snapshot(&self) -> Vec<MultipartSession> {
        let mut by_path: BTreeMap<String, MultipartSession> = match self.multipart_sessions.lock() {
            Ok(guard) => guard.clone(),
            Err(_poisoned) => BTreeMap::new(),
        };
        if let Ok(disk) = self.load_all_durable_multipart_sessions() {
            for session in disk {
                by_path.entry(session.path.clone()).or_insert(session);
            }
        }
        by_path.into_values().collect()
    }

    fn multipart_dir(&self) -> Option<PathBuf> {
        self.durable_workspace_root
            .as_ref()
            .map(|root| root.join(".lomo").join("sync").join("v1").join("multipart"))
    }

    fn durable_session_path(dir: &Path, sync_path: &str) -> PathBuf {
        let digest = format!("{:x}", Sha256::digest(sync_path.as_bytes()));
        dir.join(format!("{digest}.rec"))
    }

    fn ensure_multipart_layout(&self) -> Result<Option<PathBuf>, LomoError> {
        let Some(dir) = self.multipart_dir() else {
            return Ok(None);
        };
        fs::create_dir_all(&dir).map_err(|err| {
            storage(
                "s3_multipart_dir_create_failed",
                &format!("cannot create {}: {err}", dir.display()),
            )
        })?;
        Ok(Some(dir))
    }

    fn load_durable_multipart_session(
        &self,
        path: &str,
    ) -> Result<Option<MultipartSession>, LomoError> {
        let Some(dir) = self.multipart_dir() else {
            return Ok(None);
        };
        let file = Self::durable_session_path(&dir, path);
        if !file.exists() {
            return Ok(None);
        }
        let bytes = fs::read(&file).map_err(|err| {
            storage(
                "s3_multipart_session_open_failed",
                &format!("cannot open {}: {err}", file.display()),
            )
        })?;
        if bytes.len() > MAX_DURABLE_RECORD_BYTES + 64 {
            return Err(corrupt_state(
                "s3_multipart_session_too_large",
                "multipart durable session exceeds hard size limit",
            ));
        }
        // Magic check surfaces CorruptState before decode for truncated partial writes.
        if bytes.len() < 4 || bytes.get(0..4) != Some(SYNC_RECORD_MAGIC.as_slice()) {
            return Err(corrupt_state(
                "s3_multipart_session_corrupt",
                "multipart durable session magic/header is corrupt",
            ));
        }
        let (_schema, body) = decode_sync_record(&bytes).map_err(|err| {
            corrupt_state(
                "s3_multipart_session_corrupt",
                &format!("multipart durable session decode failed: {}", err.code()),
            )
        })?;
        let record: DurableMultipartRecord = serde_json::from_str(&body).map_err(|err| {
            corrupt_state(
                "s3_multipart_session_payload_invalid",
                &format!("cannot decode multipart session payload: {err}"),
            )
        })?;
        if record.schema != SYNC_DURABLE_SCHEMA {
            return Err(corrupt_state(
                "s3_multipart_session_unknown_schema",
                "unsupported multipart durable schema version",
            ));
        }
        if record.path != path {
            return Err(corrupt_state(
                "s3_multipart_session_path_mismatch",
                "multipart durable session path does not match file key",
            ));
        }
        ContentDigest::parse(&record.content_digest)?;
        Ok(Some(MultipartSession {
            path: record.path,
            key: record.key,
            upload_id: record.upload_id,
            confirmed_parts: record.confirmed_parts,
            content_digest: record.content_digest,
        }))
    }

    fn load_all_durable_multipart_sessions(&self) -> Result<Vec<MultipartSession>, LomoError> {
        let Some(dir) = self.multipart_dir() else {
            return Ok(Vec::new());
        };
        if !dir.exists() {
            return Ok(Vec::new());
        }
        let mut out = Vec::new();
        let entries = fs::read_dir(&dir).map_err(|err| {
            storage(
                "s3_multipart_dir_read_failed",
                &format!("cannot read {}: {err}", dir.display()),
            )
        })?;
        for entry in entries {
            let entry = entry.map_err(|err| {
                storage(
                    "s3_multipart_dir_entry_failed",
                    &format!("cannot read multipart dir entry: {err}"),
                )
            })?;
            let path = entry.path();
            if path.extension().and_then(|ext| ext.to_str()) != Some("rec") {
                continue;
            }
            let bytes = fs::read(&path).map_err(|err| {
                storage(
                    "s3_multipart_session_open_failed",
                    &format!("cannot open {}: {err}", path.display()),
                )
            })?;
            if bytes.len() < 4 || bytes.get(0..4) != Some(SYNC_RECORD_MAGIC.as_slice()) {
                return Err(corrupt_state(
                    "s3_multipart_session_corrupt",
                    "multipart durable session magic/header is corrupt",
                ));
            }
            let (_schema, body) = decode_sync_record(&bytes).map_err(|err| {
                corrupt_state(
                    "s3_multipart_session_corrupt",
                    &format!("multipart durable session decode failed: {}", err.code()),
                )
            })?;
            let record: DurableMultipartRecord = serde_json::from_str(&body).map_err(|err| {
                corrupt_state(
                    "s3_multipart_session_payload_invalid",
                    &format!("cannot decode multipart session payload: {err}"),
                )
            })?;
            if record.schema != SYNC_DURABLE_SCHEMA {
                return Err(corrupt_state(
                    "s3_multipart_session_unknown_schema",
                    "unsupported multipart durable schema version",
                ));
            }
            ContentDigest::parse(&record.content_digest)?;
            out.push(MultipartSession {
                path: record.path,
                key: record.key,
                upload_id: record.upload_id,
                confirmed_parts: record.confirmed_parts,
                content_digest: record.content_digest,
            });
        }
        Ok(out)
    }

    fn persist_durable_multipart_session(
        &self,
        session: &MultipartSession,
    ) -> Result<(), LomoError> {
        let Some(dir) = self.ensure_multipart_layout()? else {
            return Ok(());
        };
        let record = DurableMultipartRecord {
            schema: SYNC_DURABLE_SCHEMA,
            path: session.path.clone(),
            key: session.key.clone(),
            upload_id: session.upload_id.clone(),
            confirmed_parts: session.confirmed_parts.clone(),
            content_digest: session.content_digest.clone(),
        };
        let body = serde_json::to_string(&record).map_err(|err| {
            validation(
                "s3_multipart_session_encode_failed",
                &format!("cannot serialize multipart session: {err}"),
            )
        })?;
        // Keep encode_sync_record in the graph for architecture ownership of LSYN framing.
        let _framed = encode_sync_record(SYNC_DURABLE_SCHEMA, &body)?;
        let file = Self::durable_session_path(&dir, &session.path);
        write_sync_record_atomic(&file, SYNC_DURABLE_SCHEMA, &body)
    }

    fn clear_durable_multipart_session(&self, path: &str) {
        let Some(dir) = self.multipart_dir() else {
            return;
        };
        let file = Self::durable_session_path(&dir, path);
        // behavior-contract: silent-result-ok: missing durable session on clear is already cleared.
        let _removed: Result<(), std::io::Error> = fs::remove_file(file);
    }

    /// Single-shot list capped at [`MAX_S3_SNAPSHOT_ENTRIES`] (≤512). Extra remote keys mark Incomplete.
    fn list_all(&self) -> (Vec<RemotePathEntry>, bool) {
        let (pages, incomplete) = self.list_into_pages(MAX_S3_SNAPSHOT_ENTRIES);
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
        let mut continuation: Option<String> = None;
        let mut hit_entry_cap = false;

        for _list_page in 0..MAX_S3_LIST_PAGES {
            let page = match self.transport.list_page(continuation.as_deref(), 1000) {
                Ok(page) => page,
                Err(_error) => {
                    incomplete = true;
                    break;
                }
            };
            for object in page.objects {
                if total_entries >= max_entries {
                    incomplete = true;
                    hit_entry_cap = true;
                    break;
                }
                match self.snapshot_object(&object.key, object.etag.as_deref()) {
                    Ok(entry) => {
                        current_page.push(entry);
                        total_entries = total_entries.saturating_add(1);
                        if current_page.len() >= MAX_ACTION_PAGE_ITEMS {
                            pages.push(std::mem::take(&mut current_page));
                        }
                    }
                    Err(_error) => {
                        incomplete = true;
                    }
                }
            }
            if hit_entry_cap {
                break;
            }
            if page.is_truncated {
                if let Some(token) = page.next_continuation_token {
                    continuation = Some(token);
                } else {
                    incomplete = true;
                    break;
                }
            } else {
                continuation = None;
                break;
            }
        }
        if continuation.is_some() {
            incomplete = true;
        }
        if !current_page.is_empty() {
            pages.push(current_page);
        }
        (pages, incomplete)
    }

    fn snapshot_object(
        &self,
        key: &str,
        list_etag: Option<&str>,
    ) -> Result<RemotePathEntry, LomoError> {
        let relative = self.transport.endpoint().relative_from_key(key)?;
        let path = SyncPath::parse(&relative)?;
        let (temp_path, etag, digest_hex) = self.transport.get_to_temp(key)?;
        let _removed: Result<(), std::io::Error> = fs::remove_file(&temp_path);
        let digest = ContentDigest::parse(&digest_hex)?;
        let revision_token = etag
            .or_else(|| list_etag.map(str::to_owned))
            .unwrap_or_else(|| {
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
        let key = match self.transport.endpoint().object_key(path.as_str()) {
            Ok(key) => key,
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
        if bytes.len() > self.multipart_threshold {
            return self.publish_multipart(&key, path, digest, &bytes, expected_remote_token);
        }
        let result = match expected_remote_token {
            Some(token) => self.transport.put_bytes(&key, bytes, Some(token), false),
            None => self.transport.put_bytes(&key, bytes, None, true),
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
            Err(error) if error.code() == "s3_precondition_failed" => {
                PathPublishStatus::PreconditionFailed
            }
            Err(error) => PathPublishStatus::Failed {
                code: error.code().to_owned(),
            },
        }
    }

    fn publish_multipart(
        &self,
        key: &str,
        path: &SyncPath,
        digest: &ContentDigest,
        bytes: &[u8],
        expected_remote_token: Option<&str>,
    ) -> PathPublishStatus {
        if let Some(status) = self.multipart_precondition(key, expected_remote_token) {
            return status;
        }
        let (upload_id, mut confirmed) = match self.multipart_begin(key, path, digest) {
            Ok(started) => started,
            Err(error) => {
                return PathPublishStatus::Failed {
                    code: error.code().to_owned(),
                };
            }
        };

        let part_size = self.multipart_threshold.max(1);
        let total_parts = bytes.len().div_ceil(part_size);
        for part_number in 1..=total_parts {
            let part_number_u32 = u32::try_from(part_number).unwrap_or(u32::MAX);
            if confirmed
                .iter()
                .any(|part| part.part_number == part_number_u32)
            {
                continue;
            }
            let start = (part_number - 1).saturating_mul(part_size);
            let end = (start + part_size).min(bytes.len());
            let Some(chunk) = bytes.get(start..end).map(<[u8]>::to_vec) else {
                return PathPublishStatus::Failed {
                    code: "s3_multipart_part_range".to_owned(),
                };
            };
            match self
                .transport
                .upload_part(key, &upload_id, part_number_u32, chunk)
            {
                Ok(etag) => {
                    confirmed.push(MultipartConfirmedPart {
                        part_number: part_number_u32,
                        etag,
                        size_bytes: end - start,
                    });
                    if let Err(error) =
                        self.store_multipart_session(path, key, &upload_id, &confirmed, digest)
                    {
                        return PathPublishStatus::Failed {
                            code: error.code().to_owned(),
                        };
                    }
                }
                Err(error) => {
                    return PathPublishStatus::Failed {
                        code: error.code().to_owned(),
                    };
                }
            }
        }

        confirmed.sort_by_key(|part| part.part_number);
        let parts: Vec<(u32, String)> = confirmed
            .iter()
            .map(|part| (part.part_number, part.etag.clone()))
            .collect();
        match self
            .transport
            .complete_multipart_upload(key, &upload_id, &parts)
        {
            Ok(etag) => {
                self.clear_multipart_session(path.as_str());
                let new_token = etag.unwrap_or_else(|| {
                    let mut token = String::from("sha256:");
                    token.push_str(digest.as_str());
                    token
                });
                PathPublishStatus::Applied { new_token }
            }
            Err(error) => PathPublishStatus::Failed {
                code: error.code().to_owned(),
            },
        }
    }

    fn multipart_precondition(
        &self,
        key: &str,
        expected_remote_token: Option<&str>,
    ) -> Option<PathPublishStatus> {
        if let Some(token) = expected_remote_token {
            return match self.transport.head(key) {
                Ok(Some(etag)) if etag != token && strip_quotes(&etag) != strip_quotes(token) => {
                    Some(PathPublishStatus::PreconditionFailed)
                }
                Ok(None) => Some(PathPublishStatus::PreconditionFailed),
                Err(error) if error.code() == "s3_not_found" => {
                    Some(PathPublishStatus::PreconditionFailed)
                }
                Err(error) => Some(PathPublishStatus::Failed {
                    code: error.code().to_owned(),
                }),
                Ok(Some(_)) => None,
            };
        }
        if let Ok(Some(_etag)) = self.transport.head(key) {
            // If-None-Match semantics for create-only: object already present.
            return Some(PathPublishStatus::PreconditionFailed);
        }
        None
    }

    fn lookup_multipart_session(&self, path: &str) -> Result<Option<MultipartSession>, LomoError> {
        if let Ok(guard) = self.multipart_sessions.lock()
            && let Some(session) = guard.get(path).cloned()
        {
            return Ok(Some(session));
        }
        self.load_durable_multipart_session(path)
    }

    fn clear_multipart_session(&self, path: &str) {
        if let Ok(mut guard) = self.multipart_sessions.lock() {
            guard.remove(path);
        }
        self.clear_durable_multipart_session(path);
    }

    fn multipart_begin(
        &self,
        key: &str,
        path: &SyncPath,
        digest: &ContentDigest,
    ) -> Result<(String, Vec<MultipartConfirmedPart>), LomoError> {
        let existing = self.lookup_multipart_session(path.as_str())?;
        if let Some(session) = existing {
            if session.content_digest.as_str() == digest.as_str() && session.key == key {
                // Hydrate memory cache from durable/disk session for subsequent store updates.
                if let Ok(mut guard) = self.multipart_sessions.lock() {
                    guard.insert(path.as_str().to_owned(), session.clone());
                }
                return Ok((session.upload_id, session.confirmed_parts));
            }
            // Stale session for different content — abort and restart.
            let _aborted: Result<(), LomoError> = self
                .transport
                .abort_multipart_upload(&session.key, &session.upload_id);
            self.clear_multipart_session(path.as_str());
        }
        let upload_id = self.transport.create_multipart_upload(key)?;
        Ok((upload_id, Vec::new()))
    }

    fn store_multipart_session(
        &self,
        path: &SyncPath,
        key: &str,
        upload_id: &str,
        confirmed: &[MultipartConfirmedPart],
        digest: &ContentDigest,
    ) -> Result<(), LomoError> {
        let session = MultipartSession {
            path: path.as_str().to_owned(),
            key: key.to_owned(),
            upload_id: upload_id.to_owned(),
            confirmed_parts: confirmed.to_vec(),
            content_digest: digest.as_str().to_owned(),
        };
        if let Ok(mut guard) = self.multipart_sessions.lock() {
            guard.insert(path.as_str().to_owned(), session.clone());
        }
        // When durable root is configured, disk write is authoritative for process-death resume.
        self.persist_durable_multipart_session(&session)
    }

    fn publish_ensure_absent(
        &self,
        path: &SyncPath,
        expected_remote_token: &str,
    ) -> PathPublishStatus {
        let key = match self.transport.endpoint().object_key(path.as_str()) {
            Ok(key) => key,
            Err(error) => {
                return PathPublishStatus::Failed {
                    code: error.code().to_owned(),
                };
            }
        };
        match self.transport.delete(&key, Some(expected_remote_token)) {
            Ok(()) => PathPublishStatus::Applied {
                new_token: String::new(),
            },
            Err(error) if error.code() == "s3_precondition_failed" => {
                PathPublishStatus::PreconditionFailed
            }
            Err(error) if error.code() == "s3_not_found" => PathPublishStatus::Applied {
                new_token: String::new(),
            },
            Err(error) => PathPublishStatus::Failed {
                code: error.code().to_owned(),
            },
        }
    }

    fn verify_path(&self, path: &SyncPath) -> VerifyStatus {
        let key = match self.transport.endpoint().object_key(path.as_str()) {
            Ok(key) => key,
            Err(error) => {
                return VerifyStatus::Failed {
                    path: path.clone(),
                    code: error.code().to_owned(),
                };
            }
        };
        match self.transport.get_to_temp(&key) {
            Ok((temp_path, etag, digest_hex)) => {
                let _removed: Result<(), std::io::Error> = fs::remove_file(temp_path);
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
            Err(error) if error.code() == "s3_not_found" => {
                VerifyStatus::AbsentVerified { path: path.clone() }
            }
            Err(error) => VerifyStatus::Failed {
                path: path.clone(),
                code: error.code().to_owned(),
            },
        }
    }
}

impl<S: S3ObjectSource> RemoteSyncPort for S3Adapter<S> {
    fn list_remote(&self) -> Result<RemoteSnapshot, LomoError> {
        let (entries, incomplete) = self.list_all();
        if entries.len() > MAX_S3_SNAPSHOT_ENTRIES {
            return Err(resource_limit(
                "s3_snapshot_too_large",
                "s3 snapshot exceeds the action page entry ceiling",
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
                    "s3 streaming remote page exceeds the 512-item action page limit",
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
                "s3_batch_atomicity",
                "s3 adapter only executes PerPath batches",
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

/// Parameters for the hermetic map-source S3 adapter constructor.
#[derive(Clone, Debug)]
pub struct MapS3ConnectParams<'a> {
    pub endpoint_url: &'a str,
    pub bucket: &'a str,
    pub prefix: &'a str,
    pub region: &'a str,
    pub access_key_id: &'a str,
    pub secret_access_key: &'a str,
    pub temp_dir: &'a Path,
    pub objects: MapS3ObjectSource,
    pub timeout: Duration,
}

/// Convenience constructor using a filesystem temp directory path.
///
/// # Errors
///
/// Endpoint / credential / transport construction errors.
pub fn connect_map_s3_source(
    params: MapS3ConnectParams<'_>,
) -> Result<S3Adapter<MapS3ObjectSource>, LomoError> {
    use crate::s3::endpoint::S3AddressingStyle;
    let endpoint = S3Endpoint::parse(
        params.endpoint_url,
        params.bucket,
        params.prefix,
        params.region,
        S3AddressingStyle::PathStyle,
    )?;
    let credentials = S3Credentials::new(params.access_key_id, params.secret_access_key)?;
    S3Adapter::connect(
        endpoint,
        credentials,
        params.temp_dir,
        params.objects,
        params.timeout,
    )
}

fn strip_quotes(token: &str) -> &str {
    token.trim_matches('"')
}

/// Production object source: loads user file bytes from the Direct workspace filesystem.
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

impl S3ObjectSource for WorkspaceFileObjectSource {
    fn load_bytes(
        &self,
        path: &SyncPath,
        expected_digest: &ContentDigest,
    ) -> Result<Vec<u8>, LomoError> {
        let absolute = self.workspace_root.join(path.as_str());
        let bytes = fs::read(&absolute).map_err(|err| {
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
}

/// Connects a production path-style S3 adapter over workspace file object source.
///
/// # Errors
///
/// Endpoint / credential / transport construction errors.
#[expect(
    clippy::too_many_arguments,
    reason = "workspace S3 connect mirrors adapter construction fields without inventing a secret-bearing DTO"
)]
pub fn connect_workspace_s3(
    endpoint_url: &str,
    bucket: &str,
    prefix: &str,
    region: &str,
    access_key_id: &str,
    secret_access_key: &str,
    temp_dir: &Path,
    objects: WorkspaceFileObjectSource,
    timeout: Duration,
) -> Result<S3Adapter<WorkspaceFileObjectSource>, LomoError> {
    use crate::s3::endpoint::S3AddressingStyle;
    let endpoint = S3Endpoint::parse(
        endpoint_url,
        bucket,
        prefix,
        region,
        S3AddressingStyle::PathStyle,
    )?;
    let credentials = S3Credentials::new(access_key_id, secret_access_key)?;
    S3Adapter::connect(endpoint, credentials, temp_dir, objects, timeout)
}
