//! Promote plans: media identity + recoverable staged source for transaction payloads.
//!
//! Publication itself is owned by the capability-bound platform executor
//! (`PlatformAction::ArtifactWrite`), which streams the retained staged source through a
//! temporary sibling, verifies digest/length, and publishes atomically. There is no bare
//! workspace promote outside the transaction protocol.

use serde::{Deserialize, Serialize};

use crate::path::MediaRelativePath;
use crate::stage::MediaStaged;

/// Planned promote of one staged item into a final relative path.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct PromotePlan {
    pub operation_id: String,
    pub staged: MediaStaged,
    pub final_relative_path: MediaRelativePath,
}
