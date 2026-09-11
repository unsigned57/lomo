//! Persistent identity and physical location are separate, validated protocol values.

use crate::limits::{conflict, validation};
use lomo_core::LomoError;
use serde::{Deserialize, Deserializer, Serialize};

use crate::{SourceFingerprint, WorkspaceDocument, WorkspaceMemo, WorkspaceRelativePath};

/// Opaque durable ID. Existing safe IDs are preserved without interpreting their format.
#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize)]
pub struct MemoId(String);

impl MemoId {
    /// Validates a durable identifier, including the filename boundary used by history heads.
    ///
    /// # Errors
    /// Rejects empty, oversized, control-bearing or path-bearing IDs.
    pub fn parse(raw: &str) -> Result<Self, LomoError> {
        if raw.is_empty()
            || raw.len() > 255
            || matches!(raw, "." | "..")
            || raw.contains(['/', '\\'])
            || raw.chars().any(char::is_control)
        {
            return Err(validation(
                "invalid_memo_id",
                "memo ID must be a bounded opaque filename-safe value",
            ));
        }
        Ok(Self(raw.to_owned()))
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl<'de> Deserialize<'de> for MemoId {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        Self::parse(&String::deserialize(deserializer)?).map_err(serde::de::Error::custom)
    }
}

/// Logical roots are device independent; their physical paths belong to private configuration.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WorkspaceRootId {
    Notes,
    Images,
    Audio,
}

/// An address is valid only for one exact source version. Offsets retain u64 width on the wire.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(try_from = "LocatorWire")]
pub struct MemoLocator {
    root_id: WorkspaceRootId,
    path: WorkspaceRelativePath,
    fingerprint: SourceFingerprint,
    time_token: String,
    occurrence_index: u32,
    block_index: u32,
    byte_start: u64,
    byte_end: u64,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct LocatorWire {
    root_id: WorkspaceRootId,
    path: String,
    fingerprint: String,
    time_token: String,
    occurrence_index: u32,
    block_index: u32,
    byte_start: u64,
    byte_end: u64,
}

impl TryFrom<LocatorWire> for MemoLocator {
    type Error = LomoError;

    fn try_from(wire: LocatorWire) -> Result<Self, Self::Error> {
        let header = crate::header::parse_memo_header_line(&format!("- {}", wire.time_token));
        if wire.byte_start >= wire.byte_end
            || wire.occurrence_index > wire.block_index
            || !header.is_some_and(|header| {
                header.time_part() == wire.time_token && header.content_part().is_empty()
            })
        {
            return Err(validation(
                "invalid_memo_locator",
                "locator requires a valid time token, ordered byte offsets and an occurrence within its block index",
            ));
        }
        Ok(Self {
            root_id: wire.root_id,
            path: WorkspaceRelativePath::parse(&wire.path)?,
            fingerprint: SourceFingerprint::parse(&wire.fingerprint)?,
            time_token: wire.time_token,
            occurrence_index: wire.occurrence_index,
            block_index: wire.block_index,
            byte_start: wire.byte_start,
            byte_end: wire.byte_end,
        })
    }
}

impl MemoLocator {
    /// Captures a physical block from validated original bytes.
    ///
    /// # Errors
    /// Rejects an absent block or offsets that cannot cross the protocol boundary.
    pub fn new(
        root_id: WorkspaceRootId,
        path: WorkspaceRelativePath,
        document: &WorkspaceDocument,
        block_index: u32,
    ) -> Result<Self, LomoError> {
        let memo = document.memos().get(block_index as usize).ok_or_else(|| {
            validation(
                "memo_block_not_found",
                "block index is outside this document",
            )
        })?;
        let byte_start = u64::try_from(memo.memo_span().start())
            .map_err(|error| validation("invalid_memo_locator", &error.to_string()))?;
        let byte_end = u64::try_from(memo.memo_span().end())
            .map_err(|error| validation("invalid_memo_locator", &error.to_string()))?;
        Ok(Self {
            root_id,
            path,
            fingerprint: document.source().fingerprint().clone(),
            time_token: memo.time_part().to_owned(),
            occurrence_index: memo.identity().ordinal(),
            block_index,
            byte_start,
            byte_end,
        })
    }

    /// Resolves a locator only against the named root, path and complete original source version.
    ///
    /// # Errors
    /// Rejects cross-document addresses, stale versions and forged block coordinates.
    pub fn resolve<'a>(
        &self,
        root_id: WorkspaceRootId,
        path: &WorkspaceRelativePath,
        document: &'a WorkspaceDocument,
    ) -> Result<&'a WorkspaceMemo, LomoError> {
        if self.root_id != root_id || &self.path != path {
            return Err(validation(
                "memo_locator_document_mismatch",
                "locator belongs to another root or document",
            ));
        }
        if &self.fingerprint != document.source().fingerprint() {
            return Err(conflict(
                "stale_memo_locator",
                "source bytes changed since this locator was captured",
            ));
        }
        let actual = Self::new(root_id, path.clone(), document, self.block_index)?;
        if &actual != self {
            return Err(validation(
                "invalid_memo_locator",
                "locator coordinates do not identify this physical block",
            ));
        }
        document
            .memos()
            .get(self.block_index as usize)
            .ok_or_else(|| {
                validation(
                    "memo_block_not_found",
                    "block index is outside this document",
                )
            })
    }

    #[must_use]
    pub const fn root_id(&self) -> WorkspaceRootId {
        self.root_id
    }

    #[must_use]
    pub const fn path(&self) -> &WorkspaceRelativePath {
        &self.path
    }

    #[must_use]
    pub const fn fingerprint(&self) -> &SourceFingerprint {
        &self.fingerprint
    }

    #[must_use]
    pub fn time_token(&self) -> &str {
        &self.time_token
    }

    #[must_use]
    pub const fn occurrence_index(&self) -> u32 {
        self.occurrence_index
    }

    #[must_use]
    pub const fn block_index(&self) -> u32 {
        self.block_index
    }

    #[must_use]
    pub const fn byte_start(&self) -> u64 {
        self.byte_start
    }

    #[must_use]
    pub const fn byte_end(&self) -> u64 {
        self.byte_end
    }
}
