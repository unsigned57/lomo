//! Attachment reference identity and cross-source refcount.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::identity::ContentDigest;

/// Where a digest reference was observed.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub enum ReferenceSource {
    CurrentMemo,
    TrashMemo,
    HistoryVersion,
}

/// One attachment reference observation.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct AttachmentRef {
    pub digest: ContentDigest,
    pub source: ReferenceSource,
    /// Opaque memo/history key for diagnostics (not parsed here).
    pub owner_key: String,
}

/// Aggregate refcount across sources for orphan decisions.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct DigestRefcount {
    counts: BTreeMap<ReferenceSource, u64>,
}

impl DigestRefcount {
    #[must_use]
    pub fn total(&self) -> u64 {
        self.counts.values().sum()
    }

    #[must_use]
    pub fn count(&self, source: ReferenceSource) -> u64 {
        self.counts.get(&source).copied().unwrap_or(0)
    }

    pub fn add(&mut self, source: ReferenceSource) {
        *self.counts.entry(source).or_insert(0) = self
            .counts
            .get(&source)
            .copied()
            .unwrap_or(0)
            .saturating_add(1);
    }

    /// True when any current, trash, or in-window history reference remains.
    #[must_use]
    pub fn is_live(&self) -> bool {
        self.total() > 0
    }
}

/// Builds refcounts from an observed reference set.
#[must_use]
pub fn build_refcounts(refs: &[AttachmentRef]) -> BTreeMap<ContentDigest, DigestRefcount> {
    let mut map = BTreeMap::new();
    for item in refs {
        map.entry(item.digest.clone())
            .or_insert_with(DigestRefcount::default)
            .add(item.source);
    }
    map
}
