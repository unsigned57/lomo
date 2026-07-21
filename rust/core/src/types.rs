use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::LomoError;

const MAX_ID_BYTES: usize = 128;
const MAX_RELATIVE_PATH_BYTES: usize = 4_096;
const MAX_PATH_SEGMENT_BYTES: usize = 255;
const MAX_METADATA_PAGE_SIZE: u32 = 256;

macro_rules! constrained_id {
    ($name:ident, $code:literal) => {
        #[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
        pub struct $name(String);

        impl $name {
            /// Parses an opaque protocol identifier.
            ///
            /// # Errors
            ///
            /// Returns a validation error when the value is empty, exceeds 128 UTF-8 bytes, or
            /// contains characters outside the protocol identifier alphabet.
            pub fn parse(raw: &str) -> Result<Self, LomoError> {
                if raw.is_empty()
                    || raw.len() > MAX_ID_BYTES
                    || !raw
                        .bytes()
                        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b':'))
                {
                    return Err(LomoError::validation(
                        $code,
                        "identifier must be 1..=128 UTF-8 bytes using ASCII letters, digits, '-', '_', '.', or ':'",
                    ));
                }
                Ok(Self(raw.to_owned()))
            }

            #[must_use]
            pub fn as_str(&self) -> &str {
                &self.0
            }
        }
    };
}

constrained_id!(WorkspaceId, "invalid_workspace_id");
constrained_id!(OperationId, "invalid_operation_id");
constrained_id!(JobId, "invalid_job_id");
constrained_id!(BatchId, "invalid_batch_id");
constrained_id!(ActionId, "invalid_action_id");
constrained_id!(CapabilityToken, "invalid_capability_token");
constrained_id!(ExchangeToken, "invalid_exchange_token");

impl CapabilityToken {
    pub(crate) fn direct_root() -> Self {
        Self("direct-root".to_owned())
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub struct CoreRevision(u64);

impl CoreRevision {
    #[must_use]
    pub const fn initial() -> Self {
        Self(0)
    }

    #[must_use]
    pub const fn get(self) -> u64 {
        self.0
    }

    /// Reconstructs a revision from a durable counter.
    #[must_use]
    pub const fn from_raw(value: u64) -> Self {
        Self(value)
    }

    pub(crate) const fn from_persisted(value: u64) -> Self {
        Self(value)
    }

    /// Returns the next monotonic revision, or `None` on overflow.
    #[must_use]
    pub const fn checked_next(self) -> Option<Self> {
        match self.0.checked_add(1) {
            Some(value) => Some(Self(value)),
            None => None,
        }
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub struct EventSequence(u64);

impl EventSequence {
    #[must_use]
    pub const fn initial() -> Self {
        Self(0)
    }

    #[must_use]
    pub const fn get(self) -> u64 {
        self.0
    }

    /// Reconstructs an event sequence from a durable counter.
    #[must_use]
    pub const fn from_raw(value: u64) -> Self {
        Self(value)
    }

    pub(crate) const fn from_persisted(value: u64) -> Self {
        Self(value)
    }

    /// Returns the next monotonic event sequence, or `None` on overflow.
    #[must_use]
    pub const fn checked_next(self) -> Option<Self> {
        match self.0.checked_add(1) {
            Some(value) => Some(Self(value)),
            None => None,
        }
    }
}

/// Bounded invalidation domains for local-data consumers.
///
/// Events never carry row payloads. Consumers map scopes to `PagingSource` / snapshot reloads.
/// An `EventSequence` gap requires [`InvalidationScope::Full`].
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub enum InvalidationScope {
    MemoList,
    Search,
    Trash,
    Pin,
    Tags,
    Stats,
    Reminder,
    /// Full invalidate: used when `EventSequence` gaps are detected (lost events).
    Full,
}

/// Returns true when the consumer observed a non-contiguous event sequence and must full-invalidate.
#[must_use]
pub const fn event_sequence_requires_full_invalidate(
    last_seen: EventSequence,
    incoming: EventSequence,
) -> bool {
    let last = last_seen.get();
    let next = incoming.get();
    // Contiguous advance is `last + 1`. Equal (duplicate delivery) does not force full invalidate.
    // Any larger jump or regression is treated as event loss → full invalidate.
    if next == last {
        return false;
    }
    match last.checked_add(1) {
        Some(expected) => next != expected,
        None => true,
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct PageSize(u32);

impl PageSize {
    /// Creates a bounded metadata page size.
    ///
    /// # Errors
    ///
    /// Returns a resource-limit error unless `value` is within 1..=256.
    pub fn new(value: u32) -> Result<Self, LomoError> {
        if !(1..=MAX_METADATA_PAGE_SIZE).contains(&value) {
            return Err(LomoError::resource_limit(
                "invalid_page_size",
                "page size must be within 1..=256",
            ));
        }
        Ok(Self(value))
    }

    #[must_use]
    pub const fn get(self) -> u32 {
        self.0
    }
}

#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct RelativeWorkspacePath(String);

impl RelativeWorkspacePath {
    /// Parses a canonical workspace-relative path without normalization.
    ///
    /// # Errors
    ///
    /// Returns a validation error for empty/absolute paths, ambiguous segments, backslashes,
    /// controls, or paths that exceed the protocol byte limits.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        let has_windows_prefix = raw.as_bytes().get(1).is_some_and(|byte| *byte == b':');
        let invalid_segment = raw.split('/').any(|segment| {
            segment.is_empty()
                || matches!(segment, "." | "..")
                || segment.len() > MAX_PATH_SEGMENT_BYTES
        });
        if raw.is_empty()
            || raw.len() > MAX_RELATIVE_PATH_BYTES
            || raw.starts_with('/')
            || has_windows_prefix
            || raw.contains('\\')
            || raw.chars().any(char::is_control)
            || invalid_segment
        {
            return Err(LomoError::validation(
                "invalid_workspace_path",
                "workspace path must be a bounded canonical relative UTF-8 path",
            ));
        }
        Ok(Self(raw.to_owned()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum WorkspaceDescriptor {
    Direct {
        canonical_root: PathBuf,
        identity: WorkspaceId,
    },
    Saf {
        capability: CapabilityToken,
        identity: WorkspaceId,
    },
}

impl WorkspaceDescriptor {
    /// Validates and canonicalizes a direct workspace root.
    ///
    /// # Errors
    ///
    /// Returns a storage or validation error when the root cannot be canonicalized, is not a
    /// directory, or cannot be represented as the UTF-8 path accepted at the FFI boundary.
    pub fn direct(root: impl AsRef<Path>) -> Result<Self, LomoError> {
        let canonical_root = root.as_ref().canonicalize().map_err(|error| {
            LomoError::storage(
                "workspace_root_unavailable",
                format!("direct workspace root cannot be canonicalized: {error}"),
            )
        })?;
        if !canonical_root.is_dir() {
            return Err(LomoError::validation(
                "workspace_root_not_directory",
                "direct workspace root must be a directory",
            ));
        }
        let root_text = canonical_root.to_str().ok_or_else(|| {
            LomoError::validation(
                "workspace_root_not_utf8",
                "direct workspace root must be valid UTF-8",
            )
        })?;
        let identity = workspace_identity(b"direct\0", root_text.as_bytes());
        Ok(Self::Direct {
            canonical_root,
            identity,
        })
    }

    #[must_use]
    pub fn saf(capability: CapabilityToken) -> Self {
        let identity = workspace_identity(b"saf\0", capability.as_str().as_bytes());
        Self::Saf {
            capability,
            identity,
        }
    }

    #[must_use]
    pub const fn identity(&self) -> &WorkspaceId {
        match self {
            Self::Direct { identity, .. } | Self::Saf { identity, .. } => identity,
        }
    }
}

fn workspace_identity(mode: &[u8], identity_material: &[u8]) -> WorkspaceId {
    let mut hasher = Sha256::new();
    hasher.update(mode);
    hasher.update(identity_material);
    let digest = hasher.finalize();
    WorkspaceId(format!("ws-{digest:x}"))
}
