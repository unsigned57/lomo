//! Content-addressed file payloads referenced only by durable pending-operation metadata.

use crate::{
    error::{corruption, resource_limit},
    private_io::{read_optional, remove_durable, write_atomic},
    resource::MAX_FILE_BYTES,
    transaction::PlannedFile,
};
use lomo_core::{LomoError, RelativeWorkspacePath, Sha256Digest, StagedArtifactSource};
use lomo_workspace::SourceFingerprint;
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeSet,
    path::{Path, PathBuf},
};

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PayloadRef {
    digest: SourceFingerprint,
    length: u64,
}

impl PayloadRef {
    pub fn name(&self) -> &str {
        self.digest.as_str()
    }
    pub const fn length(&self) -> u64 {
        self.length
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(tag = "action", deny_unknown_fields)]
pub enum StoredFile {
    Write {
        path: RelativeWorkspacePath,
        before: Option<PayloadRef>,
        after: PayloadRef,
    },
    Delete {
        path: RelativeWorkspacePath,
        before: PayloadRef,
    },
    /// Media identity plus the durable staged source the executor streams from. No payload
    /// reference: the bytes live in stage storage under lease, never in this store.
    ArtifactWrite {
        path: RelativeWorkspacePath,
        source_path: String,
        digest: String,
        length: u64,
    },
}

impl StoredFile {
    pub fn references(&self) -> Vec<&PayloadRef> {
        match self {
            Self::Write { before, after, .. } => {
                before.iter().chain(std::iter::once(after)).collect()
            }
            Self::Delete { before, .. } => vec![before],
            Self::ArtifactWrite { .. } => Vec::new(),
        }
    }
}

#[derive(Debug)]
pub struct PayloadStore {
    directory: PathBuf,
}

impl PayloadStore {
    pub fn new(state: &Path) -> Self {
        Self {
            directory: state.join("intent-payloads"),
        }
    }

    pub fn store(&self, bytes: &[u8]) -> Result<PayloadRef, LomoError> {
        let length = u64::try_from(bytes.len())
            .map_err(|error| resource_limit("payload_too_large", error.to_string()))?;
        if length > MAX_FILE_BYTES {
            return Err(resource_limit(
                "payload_too_large",
                "frozen file exceeds its byte budget",
            ));
        }
        let digest = SourceFingerprint::of_bytes(bytes);
        let path = self.directory.join(digest.as_str());
        match read_optional(&path)? {
            Some(existing) if existing == bytes => {}
            Some(_) => {
                return Err(corruption(
                    "intent_payload_corrupt",
                    "content-addressed payload differs from its digest",
                ));
            }
            None => write_atomic(&path, bytes)?,
        }
        Ok(PayloadRef { digest, length })
    }

    pub fn load(&self, reference: &PayloadRef) -> Result<Vec<u8>, LomoError> {
        if reference.length > MAX_FILE_BYTES {
            return Err(resource_limit(
                "payload_too_large",
                "payload reference exceeds its byte budget",
            ));
        }
        let bytes = read_optional(&self.directory.join(reference.name()))?.ok_or_else(|| {
            corruption(
                "intent_payload_missing",
                "pending operation lost its immutable payload",
            )
        })?;
        if u64::try_from(bytes.len())
            .map_err(|error| resource_limit("payload_too_large", error.to_string()))?
            != reference.length
            || SourceFingerprint::of_bytes(&bytes) != reference.digest
        {
            return Err(corruption(
                "intent_payload_corrupt",
                "pending operation payload fails length or digest verification",
            ));
        }
        Ok(bytes)
    }

    pub fn freeze(&self, file: &PlannedFile) -> Result<StoredFile, LomoError> {
        match file {
            PlannedFile::Write {
                path,
                before,
                after,
            } => Ok(StoredFile::Write {
                path: path.clone(),
                before: before
                    .as_deref()
                    .map(|bytes| self.store(bytes))
                    .transpose()?,
                after: self.store(after)?,
            }),
            PlannedFile::Delete { path, before } => Ok(StoredFile::Delete {
                path: path.clone(),
                before: self.store(before)?,
            }),
            PlannedFile::ArtifactWrite { path, source } => Ok(StoredFile::ArtifactWrite {
                path: path.clone(),
                source_path: source.path().to_owned(),
                digest: source.digest().as_str().to_owned(),
                length: source.length(),
            }),
        }
    }

    pub fn thaw(&self, file: &StoredFile) -> Result<PlannedFile, LomoError> {
        match file {
            StoredFile::Write {
                path,
                before,
                after,
            } => Ok(PlannedFile::Write {
                path: path.clone(),
                before: before
                    .as_ref()
                    .map(|reference| self.load(reference))
                    .transpose()?,
                after: self.load(after)?,
            }),
            StoredFile::Delete { path, before } => Ok(PlannedFile::Delete {
                path: path.clone(),
                before: self.load(before)?,
            }),
            StoredFile::ArtifactWrite {
                path,
                source_path,
                digest,
                length,
            } => Ok(PlannedFile::ArtifactWrite {
                path: path.clone(),
                source: StagedArtifactSource::new(
                    source_path,
                    *length,
                    Sha256Digest::parse(digest)?,
                )?,
            }),
        }
    }

    pub fn reclaim(
        &self,
        candidates: &BTreeSet<String>,
        retained: &BTreeSet<String>,
    ) -> Result<(), LomoError> {
        for candidate in candidates.difference(retained) {
            SourceFingerprint::parse(candidate)?;
            let path = self.directory.join(candidate);
            if path
                .try_exists()
                .map_err(|error| crate::error::storage("payload_stat_failed", error.to_string()))?
            {
                remove_durable(&path)?;
            }
        }
        Ok(())
    }
}
